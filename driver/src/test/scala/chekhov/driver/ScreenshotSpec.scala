package chekhov.driver

import chekhov.*
import zio.*
import zio.test.*

import java.nio.file.{Files, Path}

/** Live Page.screenshot smoke. `CHEKHOV_E2E=1` or `-Dchekhov.e2e=1`. */
object ScreenshotSpec extends ZIOSpecDefault:

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
      TestAspect.timeout(20.seconds),
      TestAspect.sequential,
    )

  def spec =
    suite("page.screenshot")(
      test("writes a png under artifactsDir") {
        val artifacts = Path.of("target/chekhov")
        val config    = ChekhovConfig(browser = ChekhovBrowser.Chromium, headless = true, artifactsDir = artifacts)
        val out       = artifacts.resolve("screenshots").resolve("blank.png")
        (for
          page <- ZIO.service[Page]
          _    <- page.goto("about:blank")
          path <- page.screenshot(out)
          size <- ZIO.attempt(Files.size(path)).orDie
        yield assertTrue(Files.isRegularFile(path), size > 0L)).provide(
          ZLayer.succeed(config),
          PlaywrightDriver.suiteLayers,
        )
      }
    ) @@ onlyIfE2E
end ScreenshotSpec
