package chekhov.driver

import chekhov.*
import zio.*
import zio.test.*

import java.nio.file.Path

/** Live Vite + Playwright dogfood. Enable with `CHEKHOV_E2E=1` or `-Dchekhov.e2e=1`.
  *
  * Requires `npm install` in `examples/vite-fixture` (CI: run after root `npm ci` or install there).
  */
object ViteFixtureSpec extends ZIOSpecDefault:

  private val fixtureDir = Path.of("examples/vite-fixture").toAbsolutePath.normalize
  private val port       = 5173

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
    suite("vite fixture")(
      test("chromium: add todo via scoped Vite") {
        val config = ChekhovConfig(
          browser = ChekhovBrowser.Chromium,
          headless = true,
          artifactsDir = Path.of("target/chekhov"),
        )
        (for
          server <- ZIO.service[AppServer]
          page   <- ZIO.service[Page]
          _      <- page.goto(server.baseUrl + "/")
          _      <- page.fill("input#todo", "eggs")
          _      <- page.click("button#add")
          text   <- page.innerText("ul#list li")
        yield assertTrue(text.contains("eggs"))).provide(
          ZLayer.succeed(config),
          AppServer.viteLayer(fixtureDir, port),
          PlaywrightDriver.suiteLayers,
        )
      }
    ) @@ onlyIfE2E
end ViteFixtureSpec
