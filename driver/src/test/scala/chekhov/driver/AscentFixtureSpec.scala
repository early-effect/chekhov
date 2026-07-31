package chekhov.driver

import chekhov.*
import zio.*
import zio.test.*

import java.nio.file.{Files, Path}

/** E2E against `examples/ascent-fixture`. `CHEKHOV_E2E=1` or `-Dchekhov.e2e=1`. */
object AscentFixtureSpec extends ZIOSpecDefault:

  private val port       = 5175
  private val fixtureDir = Path.of("examples/ascent-fixture").toAbsolutePath.normalize
  private val outMarker  = fixtureDir.resolve("scalajs-out-dir")

  private def e2eEnabled: Boolean =
    sys.env.get("CHEKHOV_E2E").contains("1") ||
      sys.props.get("chekhov.e2e").contains("1")

  private def fixturePresent: Boolean =
    Files.isRegularFile(fixtureDir.resolve("package.json"))

  private val onlyIfReady: TestAspectPoly =
    new TestAspect.PerTest.AtLeastR[Any]:
      def perTest[R, E](test: ZIO[R, TestFailure[E], TestSuccess])(using Trace) =
        if e2eEnabled && fixturePresent then test
        else ZIO.succeed(TestSuccess.Ignored())

  override def aspects =
    Chunk(
      TestAspect.withLiveClock,
      TestAspect.timeout(180.seconds),
      TestAspect.sequential,
    )

  private def scalajsOutDir: IO[ChekhovError, Path] =
    ZIO
      .attempt(Path.of(Files.readString(outMarker).trim))
      .mapError(e =>
        ChekhovError.Serve(
          s"Missing $outMarker (driver/test should depend on writeAscentFixtureOut): $e",
          Some(e),
        )
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

  private def viteLayer: ZLayer[ChekhovConfig, ChekhovError, AppServer] =
    ZLayer.scoped {
      for
        outDir <- scalajsOutDir
        server <- AppServer.vite(fixtureDir, port, env = Map("SCALAJS_OUT_DIR" -> outDir.toString))
      yield server
    }

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
        viteLayer,
        PlaywrightDriver.suiteLayers,
      )
    }

  def spec =
    suite("ascent fixture")(
      runOn(ChekhovBrowser.Chromium),
      runOn(ChekhovBrowser.Firefox),
      runOn(ChekhovBrowser.WebKit),
    ) @@ onlyIfReady
end AscentFixtureSpec
