package chekhov.driver

import chekhov.*
import zio.*
import zio.test.*

import java.nio.file.Path

/** Live Vite + Playwright dogfood. Requires `npm install` in `examples/vite-fixture`. */
object ViteFixtureSpec extends ZIOSpecDefault:

  private val fixtureDir = Path.of("examples/vite-fixture").toAbsolutePath.normalize
  private val port       = 5173

  override def aspects =
    Chunk(
      TestAspect.withLiveClock,
      TestAspect.timeout(60.seconds),
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
    )
end ViteFixtureSpec
