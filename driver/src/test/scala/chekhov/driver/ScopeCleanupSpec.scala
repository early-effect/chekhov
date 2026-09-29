package chekhov.driver

import chekhov.*
import zio.*
import zio.test.*

import scala.jdk.CollectionConverters.*

/** Every process a Chekhov scope starts ends with it: the Playwright driver and each browser it launched. */
object ScopeCleanupSpec extends ZIOSpecDefault:

  override def aspects =
    Chunk(
      TestAspect.withLiveClock,
      TestAspect.timeout(120.seconds),
      TestAspect.sequential,
    )

  /** Every process this JVM has started, all the way down. */
  private val descendants: UIO[Set[ProcessHandle]] =
    ZIO.succeed(ProcessHandle.current().descendants().iterator().asScala.toSet)

  /** A browser this scope launched, alive while it is open. */
  private def browserProcesses(before: Set[ProcessHandle]): UIO[Set[ProcessHandle]] =
    descendants.map(_ -- before).map(_.filter(_.info().command().orElse("").contains("ms-playwright")))

  /** Whether any of `ps` is still running, once they have had a moment to exit. */
  private def survivors(ps: Set[ProcessHandle]): UIO[Set[ProcessHandle]] =
    ZIO
      .succeed(ps.filter(_.isAlive))
      .repeat(Schedule.spaced(200.millis) *> Schedule.recurUntil(_.isEmpty))
      .timeout(10.seconds)
      .someOrElse(ps.filter(_.isAlive))

  private val opened: ZIO[Page, ChekhovError, Unit] = ZIO.serviceWithZIO[Page](_.goto("about:blank")).unit

  private def cleanup(browser: ChekhovBrowser) =
    test(s"${browser.channelName}: closing the scope ends the browser, not just the driver") {
      val config = ChekhovConfig(browser = browser, headless = true)
      for
        before  <- descendants
        started <- Promise.make[Nothing, Set[ProcessHandle]]
        _       <- ZIO.scoped(
          (opened *> browserProcesses(before).flatMap(started.succeed))
            .provide(ZLayer.succeed(config), PlaywrightDriver.suiteLayers)
        )
        ran  <- started.await
        left <- survivors(ran)
      yield assertTrue(ran.nonEmpty, left.isEmpty)
      end for
    }

  private def interrupted(browser: ChekhovBrowser) =
    test(s"${browser.channelName}: interrupting the scope ends the browser too") {
      val config = ChekhovConfig(browser = browser, headless = true)
      for
        before  <- descendants
        started <- Promise.make[Nothing, Set[ProcessHandle]]
        fiber   <- ZIO
          .scoped(
            (opened *> browserProcesses(before).flatMap(started.succeed) *> ZIO.never)
              .provide(ZLayer.succeed(config), PlaywrightDriver.suiteLayers)
          )
          .fork
        ran  <- started.await
        _    <- fiber.interrupt
        left <- survivors(ran)
      yield assertTrue(ran.nonEmpty, left.isEmpty)
      end for
    }

  /** Firefox's app data where macOS 27 guards it with `com.apple.macl`, so its launch never answers (Mozilla 2060476).
    * Chekhov gives Firefox its own directory; `browserEnv` wins clashes, so this puts the guarded one back.
    */
  private val guardedAppData: Map[String, String] =
    Map("MOZ_APP_DATA" -> s"${java.lang.System.getProperty("user.home")}/Library/Application Support/Firefox")

  /** A launch that never answers fails the scope before any `Browser` exists to close, so only the driver's release is
    * left to end what it started.
    */
  private val failedLaunch =
    test("a launch that never answers still ends the browser the driver started") {
      val config = ChekhovConfig(browser = ChekhovBrowser.Firefox, headless = true, browserEnv = guardedAppData)
      for
        before <- descendants
        seen   <- Ref.make(Set.empty[ProcessHandle])
        // Record whatever browser processes appear while the launch hangs.
        watch  <- browserProcesses(before).flatMap(ps => seen.update(_ ++ ps)).repeat(Schedule.spaced(250.millis)).fork
        result <- ZIO.scoped(opened.provide(ZLayer.succeed(config), PlaywrightDriver.suiteLayers)).either
        _      <- watch.interrupt
        ran    <- seen.get
        left   <- survivors(ran)
      yield assertTrue(result.isLeft, ran.nonEmpty, left.isEmpty)
      end for
    } @@ TestAspect.ifProp("os.name")(_.startsWith("Mac"))

  def spec =
    suite("scope cleanup")(
      cleanup(ChekhovBrowser.Chromium),
      interrupted(ChekhovBrowser.Chromium),
      failedLaunch,
    )
end ScopeCleanupSpec
