package chekhov.driver

import chekhov.*
import zio.*
import zio.json.ast.Json
import zio.test.*

/** System browser launch path: param helpers and the pinned-revision skip. */
object SystemBrowserSpec extends ZIOSpecDefault:

  override def aspects =
    Chunk(
      TestAspect.withLiveClock,
      TestAspect.timeout(60.seconds),
      TestAspect.sequential,
    )

  /** The `env` a launch request carries, as a map. */
  private def sentEnv(params: chekhov.protocol.generated.Commands.BrowserTypeLaunch): Map[String, String] =
    params.env.toList.flatMap {
      case Json.Arr(items) =>
        items.toList.flatMap {
          case Json.Obj(fields) =>
            (fields.collectFirst { case ("name", Json.Str(n)) => n } zip
              fields.collectFirst { case ("value", Json.Str(v)) => v }).toList
          case _ => Nil
        }
      case _ => Nil
    }.toMap

  private def chekhovMessages[E](cause: Cause[E]): List[String] =
    cause.failures.collect { case e: ChekhovError => e.getMessage }.toList

  def spec =
    suite("system browser")(
      test("usesSystemBrowser is true for executablePath or channel") {
        val none = ChekhovConfig()
        assertTrue(
          !PlaywrightDriver.usesSystemBrowser(none),
          PlaywrightDriver.usesSystemBrowser(none.copy(executablePath = Some("/usr/bin/chromium"))),
          PlaywrightDriver.usesSystemBrowser(none.copy(channel = Some("chrome"))),
        )
      },
      test("launchParams carries executablePath, channel, and args") {
        val params = PlaywrightDriver.launchParams(
          ChekhovConfig(
            headless = false,
            executablePath = Some("/usr/bin/chromium"),
            launchArgs = List("--no-sandbox", "--disable-gpu"),
          ),
          ci = true,
        )
        assertTrue(
          params.headless.contains(false),
          params.chromiumSandbox.contains(false),
          params.executablePath.contains("/usr/bin/chromium"),
          params.channel.isEmpty,
          params.args.contains(Json.Arr(Json.Str("--no-sandbox"), Json.Str("--disable-gpu"))),
        )
      },
      test("launchParams omits args and sandbox outside CI") {
        val params = PlaywrightDriver.launchParams(ChekhovConfig(channel = Some("chrome")), ci = false)
        assertTrue(
          params.headless.contains(true),
          params.chromiumSandbox.isEmpty,
          params.channel.contains("chrome"),
          params.args.isEmpty,
        )
      },
      test("launchParams sends no env unless the browser needs one, so it keeps Chekhov's environment") {
        val quiet = Gen.fromIterable(
          List(ChekhovBrowser.Chromium -> true, ChekhovBrowser.WebKit -> true) ++
            ChekhovBrowser.values.toList.map(_ -> false)
        )
        check(quiet, Gen.mapOf(Gen.alphaNumericStringBounded(1, 8), Gen.alphaNumericString)) { case ((b, mac), env) =>
          assertTrue(PlaywrightDriver.launchParams(ChekhovConfig(browser = b), ci = false, env, mac).env.isEmpty)
        }
      },
      test("Firefox on macOS gets its own TMPDIR and MOZ_APP_DATA, on top of Chekhov's environment") {
        val config = ChekhovConfig(browser = ChekhovBrowser.Firefox)
        val sent   = sentEnv(PlaywrightDriver.launchParams(config, ci = false, Map("PATH" -> "/bin"), mac = true))
        assertTrue(
          sent.get("TMPDIR").contains("/tmp"),
          sent.get("MOZ_APP_DATA").exists(_.endsWith("firefox-app-data")),
          sent.get("PATH").contains("/bin"),
        )
      },
      test("browserEnv reaches the browser on top of everything else, winning every clash and dropping nothing") {
        // Playwright's env replaces the browser's whole environment, so an extra variable alone would drop PATH.
        val names = Gen.elements("PATH", "HOME", "TMPDIR", "MOZ_APP_DATA", "LANG")
        check(
          Gen.mapOf(names, Gen.alphaNumericString),
          Gen.mapOf(names, Gen.alphaNumericString).filter(_.nonEmpty),
          Gen.elements(ChekhovBrowser.values.toList*),
          Gen.boolean,
        ) { (inherited, extra, browser, mac) =>
          val config = ChekhovConfig(browser = browser, browserEnv = extra)
          val own    =
            if mac && browser == ChekhovBrowser.Firefox then PlaywrightDriver.firefoxOnMac(config) else Map.empty
          val sent = sentEnv(PlaywrightDriver.launchParams(config, ci = false, inherited, mac))
          assertTrue(sent == inherited ++ own ++ extra)
        }
      },
      test("system browser skips the pinned-revision check") {
        val config = ChekhovConfig(executablePath = Some("/nonexistent/chekhov-fake-browser"))
        (ZIO
          .service[Page]
          .unit)
          .provide(ZLayer.succeed(config), PlaywrightDriver.suiteLayers)
          .exit
          .flatMap { exit =>
            val messages = exit.causeOption.map(chekhovMessages).getOrElse(Nil)
            assertTrue(
              exit.isFailure,
              messages.exists(_.contains("/nonexistent/chekhov-fake-browser")),
              !messages.exists(_.contains("revision is not installed")),
            )
          }
      },
    )
end SystemBrowserSpec
