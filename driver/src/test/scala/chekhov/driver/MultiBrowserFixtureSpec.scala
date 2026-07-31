package chekhov.driver

import chekhov.*
import zio.*
import zio.test.*

import java.nio.file.Path

/** Live Playwright E2E against the static fixture.
  *
  * Enable with `CHEKHOV_E2E=1` (process env) or `-Dchekhov.e2e=1` (works with a long-lived sbt server). Requires
  * `npm ci` and `./scripts/install-browsers.sh` (or `npm run playwright:install`).
  */
object MultiBrowserFixtureSpec extends ZIOSpecDefault:

  private val fixtureDir = Path.of("examples/static-fixture").toAbsolutePath.normalize

  private def e2eEnabled: Boolean =
    sys.env.get("CHEKHOV_E2E").contains("1") ||
      sys.props.get("chekhov.e2e").contains("1")

  private val onlyIfE2E: TestAspectPoly =
    new TestAspect.PerTest.AtLeastR[Any]:
      def perTest[R, E](test: ZIO[R, TestFailure[E], TestSuccess])(using Trace) =
        if e2eEnabled then test else ZIO.succeed(TestSuccess.Ignored())

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
    ) @@ onlyIfE2E
end MultiBrowserFixtureSpec
