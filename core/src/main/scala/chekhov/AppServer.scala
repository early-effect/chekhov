package chekhov

import zio.*

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Configuration for a scoped subprocess that serves the app under test. */
final case class ServeConfig(
    command: List[String],
    cwd: Path,
    readyUrl: String,
    readyTimeout: Duration = 45.seconds,
    env: Map[String, String] = Map.empty,
)

/** Capability: a running server whose lifecycle is owned by Scope. */
trait AppServer:
  def baseUrl: String

object AppServer:

  /** Start a process, wait until `readyUrl` responds, kill on scope exit. */
  def serve(config: ServeConfig)(using Trace): ZIO[Scope & ChekhovConfig, ChekhovError, AppServer] =
    for
      chekhov <- ZIO.service[ChekhovConfig]
      _       <- ZIO.attempt(Files.createDirectories(chekhov.artifactsDir.resolve("serve"))).orDie
      logFile = chekhov.artifactsDir.resolve("serve").resolve(s"serve-${java.lang.System.currentTimeMillis()}.log")
      process <- ZIO.acquireRelease {
        ZIO
          .attemptBlocking {
            val pb = new ProcessBuilder(config.command.asJava)
            pb.directory(config.cwd.toFile)
            pb.redirectErrorStream(true)
            pb.redirectOutput(logFile.toFile)
            config.env.foreach { case (k, v) => pb.environment().put(k, v) }
            pb.start()
          }
          .mapError(e => ChekhovError.Serve(s"Failed to start ${config.command.mkString(" ")}", Some(e)))
      } { p =>
        ZIO.attemptBlocking {
          p.destroyForcibly()
          p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
          ()
        }.ignore
      }
      _ <- waitUntilReady(config.readyUrl, config.readyTimeout, process, logFile)
    yield new AppServer:
      val baseUrl = config.readyUrl

  def layer(config: ServeConfig): ZLayer[ChekhovConfig, ChekhovError, AppServer] =
    ZLayer.scoped(serve(config))

  private def waitUntilReady(url: String, timeout: Duration, process: Process, logFile: Path)(using
      Trace
  ): IO[ChekhovError, Unit] =
    def failWithLog(why: String): IO[ChekhovError, Nothing] =
      val tail =
        try Files.readString(logFile).takeRight(2000)
        catch case _: Throwable => ""
      ZIO.fail(ChekhovError.Serve(s"$why at $url (alive=${process.isAlive}). Log:\n$tail"))

    def attempt: UIO[Boolean] =
      ZIO
        .attemptBlocking {
          val conn = java.net.URI.create(url).toURL.openConnection().asInstanceOf[java.net.HttpURLConnection]
          conn.setConnectTimeout(500)
          conn.setReadTimeout(500)
          conn.setRequestMethod("GET")
          try
            conn.getResponseCode
            true
          catch case _: Throwable => false
          finally conn.disconnect()
        }
        .orElseSucceed(false)

    def loop: IO[ChekhovError, Boolean] =
      if !process.isAlive then failWithLog("Server process exited before ready")
      else
        attempt.flatMap {
          case true  => ZIO.succeed(true)
          case false => ZIO.sleep(200.millis) *> loop
        }

    loop
      .timeout(timeout)
      .flatMap {
        case Some(true) => ZIO.unit
        case _          => failWithLog(s"Server not ready within $timeout")
      }
  end waitUntilReady
end AppServer
