package chekhov.driver

import chekhov.*
import zio.*
import zio.test.*

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Live tracing + video capture. `CHEKHOV_E2E=1` or `-Dchekhov.e2e=1`. */
object TraceVideoSpec extends ZIOSpecDefault:

  private def e2eEnabled: Boolean =
    sys.env.get("CHEKHOV_E2E").contains("1") ||
      sys.props.get("chekhov.e2e").contains("1")

  private val onlyIfE2E: TestAspectPoly =
    new TestAspect.PerTest.AtLeastR[Any]:
      def perTest[R, E](test: ZIO[R, TestFailure[E], TestSuccess])(using Trace) =
        if e2eEnabled then test else ZIO.succeed(TestSuccess.Ignored())

  override def aspects =
    Chunk(
      TestAspect.withLiveClock,
      TestAspect.timeout(90.seconds),
      TestAspect.sequential,
    )

  private def listFiles(dir: Path): UIO[List[Path]] =
    ZIO.attempt {
      if !Files.isDirectory(dir) then Nil
      else
        val stream = Files.list(dir)
        try stream.iterator().asScala.filter(Files.isRegularFile(_)).toList
        finally stream.close()
    }.orDie

  def spec =
    suite("trace and video capture")(
      test("Always writes a trace zip under artifactsDir/traces") {
        val artifacts = Path.of("target/chekhov/trace-video-spec/always-trace")
        val config    = ChekhovConfig(
          browser = ChekhovBrowser.Chromium,
          headless = true,
          artifactsDir = artifacts,
          traceCapture = ArtifactCapture.Always,
        )
        (for
          page  <- ZIO.service[Page]
          _     <- page.goto("about:blank")
          title <- page.title
        // Scope closes via provide; wait until after layers tear down by nesting provide.
        yield title).provide(
          ZLayer.succeed(config),
          PlaywrightDriver.suiteLayers,
        ) *> listFiles(artifacts.resolve("traces")).map { files =>
          val zips = files.filter(_.getFileName.toString.endsWith(".zip"))
          assertTrue(zips.nonEmpty, zips.forall(p => Files.size(p) > 0L))
        }
      },
      test("OnFailure discards trace on pass") {
        val artifacts = Path.of("target/chekhov/trace-video-spec/onfailure-pass")
        val config    = ChekhovConfig(
          browser = ChekhovBrowser.Chromium,
          headless = true,
          artifactsDir = artifacts,
          traceCapture = ArtifactCapture.OnFailure,
        )
        (for
          page <- ZIO.service[Page]
          _    <- page.goto("about:blank")
        yield ()).provide(
          ZLayer.succeed(config),
          PlaywrightDriver.suiteLayers,
        ) *> listFiles(artifacts.resolve("traces")).map { files =>
          assertTrue(files.isEmpty)
        }
      },
      test("OnFailure keeps trace when ArtifactSession is marked failed") {
        val artifacts = Path.of("target/chekhov/trace-video-spec/onfailure-fail")
        val config    = ChekhovConfig(
          browser = ChekhovBrowser.Chromium,
          headless = true,
          artifactsDir = artifacts,
          traceCapture = ArtifactCapture.OnFailure,
        )
        (for
          _    <- ArtifactSession.markFailed
          page <- ZIO.service[Page]
          _    <- page.goto("about:blank")
        yield ()).provide(
          ZLayer.succeed(config),
          PlaywrightDriver.suiteLayers,
        ) *> listFiles(artifacts.resolve("traces")).map { files =>
          val zips = files.filter(_.getFileName.toString.endsWith(".zip"))
          assertTrue(zips.nonEmpty)
        }
      },
      test("Always records video under artifactsDir/videos") {
        val artifacts = Path.of("target/chekhov/trace-video-spec/always-video")
        val config    = ChekhovConfig(
          browser = ChekhovBrowser.Chromium,
          headless = true,
          artifactsDir = artifacts,
          videoCapture = ArtifactCapture.Always,
        )
        (for
          page <- ZIO.service[Page]
          _    <- page.goto("about:blank")
          _    <- page.evaluate("1+1")
        yield ()).provide(
          ZLayer.succeed(config),
          PlaywrightDriver.suiteLayers,
        ) *> listFiles(artifacts.resolve("videos")).map { files =>
          assertTrue(files.nonEmpty, files.forall(p => Files.size(p) > 0L))
        }
      },
    ) @@ onlyIfE2E
end TraceVideoSpec
