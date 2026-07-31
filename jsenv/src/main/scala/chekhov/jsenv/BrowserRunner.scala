package chekhov.jsenv

import chekhov.*
import chekhov.driver.PlaywrightDriver
import org.scalajs.jsenv.*
import zio.*
import zio.json.*
import zio.json.ast.Json

import java.io.{PipedInputStream, PipedOutputStream, PrintStream}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal

/** Shared browser run over Playwright channel + localhost materialization. */
private[jsenv] final class BrowserRunner(
    browser: ChekhovBrowser,
    headless: Boolean,
    onMessage: Option[String => Unit],
    runConfig: RunConfig,
):

  private val closed      = new AtomicBoolean(false)
  private val stopFlag    = new AtomicBoolean(false)
  private val sendQueue   = new ConcurrentLinkedQueue[String]()
  private val donePromise = Promise[Unit]()
  private val stopRef     = new AtomicReference[Option[() => Unit]](None)

  def future: Future[Unit] = donePromise.future

  def send(msg: String): Unit =
    if !closed.get() then sendQueue.offer(msg)

  def close(): Unit =
    if closed.compareAndSet(false, true) then
      stopFlag.set(true)
      stopRef.get().foreach(_())

  def start(inputs: Seq[Input]): Unit =
    val thread = new Thread(
      () =>
        try runBlocking(inputs)
        catch
          case NonFatal(e) =>
            if !donePromise.isCompleted then donePromise.failure(e)
      ,
      s"chekhov-jsenv-${browser.channelName}",
    )
    thread.setDaemon(true)
    thread.start()
  end start

  private def runBlocking(inputs: Seq[Input]): Unit =
    val materialized = HtmlMaterializer.materialize(inputs)
    val config       = ChekhovConfig(browser = browser, headless = headless)

    val outPipe = new PipedOutputStream()
    val errPipe = new PipedOutputStream()
    val outIn   = new PipedInputStream(outPipe, 64 * 1024)
    val errIn   = new PipedInputStream(errPipe, 64 * 1024)
    val outPs   = new PrintStream(outPipe, true, StandardCharsets.UTF_8)
    val errPs   = new PrintStream(errPipe, true, StandardCharsets.UTF_8)

    runConfig.onOutputStream.foreach { cb =>
      cb(
        if runConfig.inheritOutput then None else Some(outIn),
        if runConfig.inheritError then None else Some(errIn),
      )
    }

    val stopFlagLocal = stopFlag
    stopRef.set(Some(() =>
      stopFlagLocal.set(true); ()
    ))

    val program =
      ZIO
        .scoped {
          for
            server <- StaticFileServer.serve(materialized.dir)
            page   <- ZIO.service[Page]
            _      <- page.goto(server.baseUrl + "/")
            _      <- pollLoop(page, outPs, errPs)
          yield ()
        }
        .provide(
          ZLayer.succeed(config),
          PlaywrightDriver.suiteLayers,
        )

    try
      Unsafe.unsafe { implicit u =>
        Runtime.default.unsafe.run(program) match
          case Exit.Success(_) =>
            if !donePromise.isCompleted then donePromise.success(())
          case Exit.Failure(cause) =>
            if !donePromise.isCompleted then
              donePromise.failure(cause.failureOption.getOrElse(new RuntimeException(cause.prettyPrint)))
      }
    finally
      try outPs.close()
      catch case NonFatal(_) => ()
      try errPs.close()
      catch case NonFatal(_) => ()
      closed.set(true)
    end try
  end runBlocking

  private def pollLoop(
      page: Page,
      out: PrintStream,
      err: PrintStream,
  ): ZIO[Any, ChekhovError, Unit] =
    def drainSends: IO[ChekhovError, Unit] =
      def loop: IO[ChekhovError, Unit] =
        Option(sendQueue.poll()) match
          case None      => ZIO.unit
          case Some(msg) =>
            val lit = msg.toJson
            page.evaluate(s"() => window.__chekhovCom.push($lit)", isFunction = true).unit *> loop
      loop

    def writeLog(line: LogLine): Unit =
      val ps = if line.level == "error" then err else out
      ps.println(line.line)
      if runConfig.inheritOutput && line.level != "error" then java.lang.System.out.println(line.line)
      if runConfig.inheritError && line.level == "error" then java.lang.System.err.println(line.line)

    def once: IO[ChekhovError, Boolean] =
      for
        _       <- drainSends
        raw     <- page.evaluate("() => window.__chekhovCom.fetch()", isFunction = true)
        payload <- decodeFetch(raw)
        _       <- ZIO.succeed(payload.logs.foreach(writeLog))
        _       <- ZIO.foreachDiscard(payload.msgs) { m =>
          ZIO.succeed(onMessage.foreach(_(m)))
        }
        _ <- ZIO.when(payload.errs.nonEmpty) {
          ZIO.fail(ChekhovError.Protocol(s"JS errors: ${payload.errs.mkString("; ")}"))
        }
      yield stopFlag.get()

    def loop: IO[ChekhovError, Unit] =
      once.flatMap {
        case true  => ZIO.unit
        case false => ZIO.sleep(50.millis) *> loop
      }

    loop
  end pollLoop

  private final case class LogLine(level: String, line: String) derives JsonDecoder
  private final case class FetchPayload(
      msgs: List[String] = Nil,
      errs: List[String] = Nil,
      logs: List[LogLine] = Nil,
  ) derives JsonDecoder

  private def decodeFetch(raw: String): IO[ChekhovError, FetchPayload] =
    val s =
      raw.fromJson[Json] match
        case Right(j) => j.asObject.flatMap(_.get("s")).flatMap(_.asString).getOrElse("{}")
        case Left(_)  => "{}"
    ZIO
      .fromEither(s.fromJson[FetchPayload])
      .mapError(e => ChekhovError.Protocol(s"bad __chekhovCom.fetch payload: $e"))
end BrowserRunner
