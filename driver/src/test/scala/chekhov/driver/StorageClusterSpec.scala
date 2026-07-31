package chekhov.driver

import chekhov.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** Live storage / cookies / webStorage cluster. Enable with `CHEKHOV_E2E=1` or `-Dchekhov.e2e=1`. */
object StorageClusterSpec extends ZIOSpecDefault:

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

  private val config =
    ChekhovConfig(browser = ChekhovBrowser.Chromium, headless = true)

  def spec =
    suite("storage cluster")(
      test("webStorage set/get/items/remove/clear") {
        (for
          page    <- ZIO.service[Page]
          _       <- page.goto("https://example.com/")
          _       <- page.webStorageClear(WebStorageKind.Local)
          _       <- page.webStorageSetItem(WebStorageKind.Local, "chekhov", "gun")
          got     <- page.webStorageGetItem(WebStorageKind.Local, "chekhov")
          items   <- page.webStorageItems(WebStorageKind.Local)
          _       <- page.webStorageRemoveItem(WebStorageKind.Local, "chekhov")
          after   <- page.webStorageGetItem(WebStorageKind.Local, "chekhov")
          _       <- page.webStorageSetItem(WebStorageKind.Local, "tmp", "1")
          _       <- page.webStorageClear(WebStorageKind.Local)
          cleared <- page.webStorageItems(WebStorageKind.Local)
        yield assertTrue(
          got.contains("gun"),
          items.exists(i => i.name == "chekhov" && i.value == "gun"),
          after.isEmpty,
          cleared.isEmpty,
        )).provide(
          ZLayer.succeed(config),
          PlaywrightDriver.suiteLayers,
        )
      },
      test("storageState round-trips localStorage origins") {
        (for
          page  <- ZIO.service[Page]
          ctx   <- ZIO.service[BrowserContext]
          _     <- page.goto("https://example.com/")
          _     <- page.webStorageClear(WebStorageKind.Local)
          _     <- page.webStorageSetItem(WebStorageKind.Local, "hub", "mermoid")
          snap  <- ctx.storageState()
          _     <- page.webStorageClear(WebStorageKind.Local)
          empty <- page.webStorageGetItem(WebStorageKind.Local, "hub")
          _     <- ctx.setStorageState(snap)
          // Origin storage applies on next navigation for the origin.
          _        <- page.goto("https://example.com/")
          restored <- page.webStorageGetItem(WebStorageKind.Local, "hub")
          origins = snap
            .fromJson[Json]
            .toOption
            .flatMap(_.asObject)
            .flatMap(_.get("origins"))
            .flatMap(_.asArray)
        yield assertTrue(
          empty.isEmpty,
          restored.contains("mermoid"),
          origins.exists(_.nonEmpty),
        )).provide(
          ZLayer.succeed(config),
          PlaywrightDriver.suiteLayers,
        )
      },
      test("cookies add/list/clear") {
        (for
          page <- ZIO.service[Page]
          ctx  <- ZIO.service[BrowserContext]
          _    <- page.goto("https://example.com/")
          _    <- ctx.clearCookies
          _    <- ctx.addCookies(
            Chunk(
              CookieInit(
                name = "ck",
                value = "v1",
                url = Some("https://example.com/"),
              )
            )
          )
          listed <- ctx.cookies(Chunk("https://example.com/"))
          _      <- ctx.clearCookies
          after  <- ctx.cookies(Chunk("https://example.com/"))
        yield assertTrue(
          listed.exists(c => c.name == "ck" && c.value == "v1"),
          after.isEmpty,
        )).provide(
          ZLayer.succeed(config),
          PlaywrightDriver.suiteLayers,
        )
      },
    ) @@ onlyIfE2E
end StorageClusterSpec
