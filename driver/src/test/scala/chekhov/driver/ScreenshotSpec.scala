package chekhov.driver

import chekhov.*
import zio.*
import zio.test.*

import java.nio.file.{Files, Path}

/** Live Page.screenshot smoke. */
object ScreenshotSpec extends ZIOSpecDefault:

  override def aspects =
    Chunk(
      TestAspect.withLiveClock,
      TestAspect.timeout(60.seconds),
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
    )
end ScreenshotSpec
