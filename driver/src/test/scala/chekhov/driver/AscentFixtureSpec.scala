package chekhov.driver

import chekhov.*
import zio.*
import zio.test.*

import java.nio.file.Path

/** E2E against `examples/ascent-fixture`, served from the staged `target/serve` directory. */
object AscentFixtureSpec extends ZIOSpecDefault:

  private val serveDir = Path.of("examples/ascent-fixture/target/serve").toAbsolutePath.normalize

  override def aspects =
    Chunk(
      TestAspect.withLiveClock,
      TestAspect.timeout(90.seconds),
      TestAspect.sequential,
    )

  private def waitForInc(page: Page): IO[ChekhovError, Unit] =
    waitForTrue(
      page,
      """() => !!document.getElementById('inc')""",
      "ascent fixture #inc did not appear within 30s",
      30.seconds,
    )

  private def waitForCount(page: Page, expected: String): IO[ChekhovError, Unit] =
    waitForTrue(
      page,
      s"""() => (document.getElementById('count')?.textContent || '') === '$expected'""",
      s"ascent fixture #count did not become '$expected' within 15s",
      15.seconds,
    )

  private def waitForTrue(
      page: Page,
      expression: String,
      timeoutMsg: String,
      timeout: Duration,
  ): IO[ChekhovError, Unit] =
    def attempt: IO[ChekhovError, Boolean] =
      page.evaluate(expression, isFunction = true).map(raw => raw.contains("\"b\":true"))
    def loop: IO[ChekhovError, Unit] =
      attempt.flatMap {
        case true  => ZIO.unit
        case false => ZIO.sleep(250.millis) *> loop
      }
    loop.timeoutFail(ChekhovError.Timeout(timeoutMsg))(timeout)
  end waitForTrue

  private def runOn(browser: ChekhovBrowser) =
    test(s"${browser.channelName}: increment counter") {
      val config = ChekhovConfig(
        browser = browser,
        headless = true,
        artifactsDir = Path.of("target/chekhov"),
      )
      (for
        server <- ZIO.service[AppServer]
        page   <- ZIO.service[Page]
        _      <- page.goto(server.baseUrl + "/")
        _      <- waitForInc(page)
        before <- page.innerText("#count")
        _      <- page.click("#inc")
        _      <- waitForCount(page, "1")
        after  <- page.innerText("#count")
      yield assertTrue(before == "0", after == "1")).provide(
        ZLayer.succeed(config),
        StaticFileServer.layer(serveDir),
        PlaywrightDriver.suiteLayers,
      )
    }

  def spec =
    suite("ascent fixture")(
      runOn(ChekhovBrowser.Chromium),
      runOn(ChekhovBrowser.Firefox),
      runOn(ChekhovBrowser.WebKit),
    )
end AscentFixtureSpec
