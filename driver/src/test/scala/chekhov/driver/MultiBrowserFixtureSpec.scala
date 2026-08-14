package chekhov.driver

import chekhov.*
import zio.*
import zio.test.*

import java.nio.file.Path

/** Live Playwright E2E against the static fixture. */
object MultiBrowserFixtureSpec extends ZIOSpecDefault:

  private val fixtureDir = Path.of("examples/static-fixture").toAbsolutePath.normalize

  /** Live clock for the whole suite (RPC timeouts, sleeps); hard cap against hang. */
  override def aspects =
    Chunk(
      TestAspect.withLiveClock,
      TestAspect.timeout(60.seconds),
      TestAspect.sequential,
    )

  private def runOn(browser: ChekhovBrowser) =
    test(s"${browser.channelName}: add todo") {
      val config = ChekhovConfig(browser = browser, headless = true, artifactsDir = Path.of("target/chekhov"))
      (for
        server <- ZIO.service[AppServer]
        page   <- ZIO.service[Page]
        _      <- page.goto(server.baseUrl + "/")
        _      <- page.fill("input#todo", "milk")
        _      <- page.click("button#add")
        text   <- page.innerText("ul#list li")
      yield assertTrue(text.contains("milk"))).provide(
        ZLayer.succeed(config),
        StaticFileServer.layer(fixtureDir),
        PlaywrightDriver.suiteLayers,
      )
    }

  def spec =
    suite("multi-browser fixture")(
      runOn(ChekhovBrowser.Chromium),
      runOn(ChekhovBrowser.Firefox),
      runOn(ChekhovBrowser.WebKit),
    )
end MultiBrowserFixtureSpec
