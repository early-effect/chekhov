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
