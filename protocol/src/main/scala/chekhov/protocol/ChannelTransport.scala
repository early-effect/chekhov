package chekhov.protocol

import chekhov.ChekhovError
import chekhov.protocol.generated.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

import java.io.{BufferedInputStream, BufferedOutputStream, InputStream, OutputStream}
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Framed JSON pipe to the Playwright Node driver (`run-driver`). */
trait ChannelTransport:
  def send(request: ClientRequest)(using Trace): IO[ChekhovError, Unit]
  def sendAndWait(request: ClientRequest)(using Trace): IO[ChekhovError, ServerResponse]

  /** Subscribe to server events (Hub). Call before sending work that emits events. */
  def subscribeEvents(using Trace): ZIO[Scope, Nothing, Dequeue[ServerEvent]]

  /** Stream view of a fresh Hub subscription (acquires Scope while running). */
  def events(using Trace): ZStream[Any, Nothing, ServerEvent]

  def pollEvent(using Trace): UIO[Option[ServerEvent]]
  def receive(using Trace): IO[ChekhovError, InboundMessage]
end ChannelTransport

object ChannelTransport:

  final case class Pipe(
      process: Process,
      in: InputStream,
      out: OutputStream,
      waiters: Ref[Map[Int, Promise[ChekhovError, ServerResponse]]],
      eventHub: Hub[ServerEvent],
      eventBuffer: Queue[ServerEvent],
  ) extends ChannelTransport:

    def send(request: ClientRequest)(using Trace): IO[ChekhovError, Unit] =
      ZIO
        .attemptBlocking {
          val bytes = request.toJson.getBytes(StandardCharsets.UTF_8)
          val len   = ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(bytes.length).array()
          out.write(len)
          out.write(bytes)
          out.flush()
        }
        .mapError(e => ChekhovError.Protocol(s"Failed to send ${request.method}", Some(e)))

    def sendAndWait(request: ClientRequest)(using Trace): IO[ChekhovError, ServerResponse] =
      for
        promise <- Promise.make[ChekhovError, ServerResponse]
        _       <- waiters.update(_ + (request.id -> promise))
        _       <- send(request)
        resp    <- promise.await
          .timeoutFail(
            ChekhovError.Timeout(s"No response for ${request.method} (id=${request.id}) within 30s")
          )(30.seconds)
          .ensuring(waiters.update(_ - request.id).unit)
      yield resp

    def subscribeEvents(using Trace): ZIO[Scope, Nothing, Dequeue[ServerEvent]] =
      eventHub.subscribe

    def events(using Trace): ZStream[Any, Nothing, ServerEvent] =
      ZStream.unwrapScoped {
        eventHub.subscribe.map(q => ZStream.fromQueue(q))
      }

    def pollEvent(using Trace): UIO[Option[ServerEvent]] =
      eventBuffer.poll

    def receive(using Trace): IO[ChekhovError, InboundMessage] =
      eventBuffer.take.map(InboundMessage.Event.apply)

  end Pipe

  /** Spawn Playwright's Node driver and own it in Scope. */
  def live(using Trace): ZIO[Scope, ChekhovError, ChannelTransport] =
    for
      driver   <- resolveDriver
      acquired <- ZIO.acquireRelease {
        ZIO
          .attemptBlocking {
            val pb = new ProcessBuilder((driver.node :: driver.cli :: "run-driver" :: Nil).asJava)
            pb.redirectError(ProcessBuilder.Redirect.INHERIT)
            pb.environment().put("PW_LANG_NAME", "scala")
            pb.environment().put("PW_LANG_NAME_VERSION", "3")
            // Use Playwright's default OS browser cache. Do not force
            // PLAYWRIGHT_BROWSERS_PATH=0 (stores under node_modules and
            // races with npm / Cursor sandbox caches / dirlocks).
            // If a host injects an ephemeral sandbox cache path, drop it
            // so install and run-driver agree on ~/Library/Caches/ms-playwright
            // (or the platform equivalent).
            Option(pb.environment().get("PLAYWRIGHT_BROWSERS_PATH"))
              .filter(p => p == "0" || p.contains("cursor-sandbox-cache"))
              .foreach(_ => pb.environment().remove("PLAYWRIGHT_BROWSERS_PATH"))
            val process = pb.start()
            val in      = new BufferedInputStream(process.getInputStream)
            val out     = new BufferedOutputStream(process.getOutputStream)
            (process, in, out)
          }
          .mapError(e => ChekhovError.Driver("Failed to spawn Playwright driver", Some(e)))
      } { case (p, in, out) =>
        // Kill the driver first. Closing the pipe before destroy can block forever when
        // the child is stuck writing (full stdout buffer) and no longer reading stdin.
        ZIO
          .attemptBlocking {
            p.destroyForcibly()
            try out.close()
            catch case _: Throwable => ()
            try in.close()
            catch case _: Throwable => ()
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            ()
          }
          .orDie
          .timeout(3.seconds)
          .unit
      }
      (process, in, out) = acquired
      waiters     <- Ref.make(Map.empty[Int, Promise[ChekhovError, ServerResponse]])
      eventHub    <- Hub.unbounded[ServerEvent]
      eventBuffer <- Queue.unbounded[ServerEvent]
      // Early Hub subscriber so pollEvent/receive never miss events.
      bufferQ <- eventHub.subscribe
      _       <- ZStream.fromQueue(bufferQ).runForeach(eventBuffer.offer).forkScoped
      _       <- framedInbound(in)
        .mapZIO(dispatch(_, waiters, eventHub))
        .runDrain
        .forkScoped
    yield Pipe(process, in, out, waiters, eventHub, eventBuffer)

  val layer: ZLayer[Any, ChekhovError, ChannelTransport] =
    ZLayer.scoped(live)

  private final case class DriverPaths(node: String, cli: String)

  private def resolveDriver(using Trace): IO[ChekhovError, DriverPaths] =
    ZIO
      .attemptBlocking {
        val fromEnv    = sys.env.get("PLAYWRIGHT_DRIVER_CLI")
        val candidates = List(
          fromEnv,
          sys.env.get("npm_config_prefix").map(_ + "/lib/node_modules/playwright/cli.js"),
          Some("node_modules/playwright/cli.js"),
          Some("node_modules/playwright/lib/cli/cli.js"),
        ).flatten.map(Path.of(_))

        val cli = candidates
          .find(p => Files.isRegularFile(p))
          .getOrElse(Path.of("playwright-cli-missing"))

        val node = sys.env.getOrElse("PLAYWRIGHT_NODEJS_PATH", "node")
        if !Files.isRegularFile(cli) && fromEnv.isEmpty then
          val pb = new ProcessBuilder("node", "-p", "require.resolve('playwright/cli.js')")
          pb.redirectErrorStream(true)
          val p    = pb.start()
          val out  = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
          val code = p.waitFor()
          if code == 0 && Files.isRegularFile(Path.of(out)) then DriverPaths(node, out)
          else
            throw new IllegalStateException(
              "Playwright CLI not found. Install with `npm i -D playwright` or set PLAYWRIGHT_DRIVER_CLI."
            )
        else DriverPaths(node, cli.toAbsolutePath.toString)
        end if
      }
      .mapError(e => ChekhovError.Driver(e.getMessage, Some(e)))

  /** Length-prefixed JSON frames as a pull-based stream (cancelable via InputStream.close). */
  private def framedInbound(in: InputStream)(using Trace): ZStream[Any, ChekhovError, Json] =
    ZStream
      .repeatZIO(readFrame(in))
      .catchAll(_ => ZStream.empty)

  private def readFrame(in: InputStream)(using Trace): IO[ChekhovError, Json] =
    for
      lenBytes <- readExact(in, 4)
      len = ByteBuffer.wrap(lenBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt()
      body <- readExact(in, len)
      text = new String(body, StandardCharsets.UTF_8)
      json <- ZIO
        .fromEither(text.fromJson[Json])
        .mapError(e => ChekhovError.Protocol(s"Invalid JSON from driver: $e"))
    yield json

  private def readExact(in: InputStream, n: Int)(using Trace): IO[ChekhovError, Array[Byte]] =
    ZIO
      .attemptBlockingCancelable {
        val buf = new Array[Byte](n)
        var off = 0
        while off < n do
          val r = in.read(buf, off, n - off)
          if r < 0 then throw new java.io.EOFException("driver pipe closed")
          off += r
        buf
      }(ZIO.attempt(in.close()).ignore)
      .mapError(e => ChekhovError.Protocol("read from driver failed", Some(e)))

  private def dispatch(
      json: Json,
      waiters: Ref[Map[Int, Promise[ChekhovError, ServerResponse]]],
      eventHub: Hub[ServerEvent],
  )(using Trace): UIO[Unit] =
    json.asObject match
      case Some(obj) if obj.contains("id") =>
        ZIO
          .fromEither(json.as[ServerResponse])
          .foldZIO(
            _ => ZIO.unit,
            resp =>
              waiters.get.flatMap { map =>
                map.get(resp.id) match
                  case Some(p) =>
                    waiters.update(_ - resp.id) *>
                      (resp.error match
                        case Some(err) => p.fail(ChekhovError.Protocol(err.toString))
                        case None      => p.succeed(resp)
                      ).unit
                  case None => ZIO.unit
              },
          )
      case Some(obj) if obj.contains("method") =>
        ZIO
          .fromEither(json.as[ServerEvent])
          .foldZIO(
            _ => ZIO.unit,
            event => eventHub.publish(event).unit,
          )
      case _ =>
        ZIO.unit
end ChannelTransport
