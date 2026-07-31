package chekhov.ziotest

import chekhov.*
import chekhov.driver.PlaywrightDriver
import zio.*
import zio.test.*

import java.nio.file.{Files, Path}

/** Specular/saferis-shaped suite: scoped Playwright driver + page, artifacts under target/chekhov. */
trait ChekhovSuite extends ZIOSpecDefault:

  /** Override to customize config (browser, baseUrl, artifactsDir, trace/video capture). */
  def chekhovConfig: ChekhovConfig = ChekhovConfig()

  /** Layers provided to every test: config, artifact session, browser type, shared browser, context, fresh page. */
  def chekhovLayer: ZLayer[
    Any,
    ChekhovError,
    ChekhovConfig & ArtifactSession & BrowserType & Browser & BrowserContext & Page,
  ] =
    ZLayer.succeed(chekhovConfig) >>> ChekhovSuite.fullStack

  override def aspects =
    Chunk(
      TestAspect.samples(1),
      TestAspect.withLiveClock,
      TestAspect.timeout(60.seconds),
    )
end ChekhovSuite

object ChekhovSuite:

  val fullStack: ZLayer[
    ChekhovConfig,
    ChekhovError,
    ChekhovConfig & ArtifactSession & BrowserType & Browser & BrowserContext & Page,
  ] =
    ZLayer.service[ChekhovConfig] ++ PlaywrightDriver.suiteLayers

  def forBrowsers[R, E](
      browsers: ChekhovBrowser*
  )(make: ChekhovBrowser => Spec[R, E]): Spec[R, E] =
    suite("chekhov browsers")(
      browsers.map(b => suite(b.channelName)(make(b)))*
    )

  def ensureArtifactsDir(dir: Path): UIO[Path] =
    ZIO.attempt(Files.createDirectories(dir)).orDie.as(dir)

  /** Apply when [[ChekhovConfig.traceCapture]] / [[ChekhovConfig.videoCapture]] is [[ArtifactCapture.OnFailure]] so
    * failures keep artifacts (`suite(...)(...) @@ retainArtifactsOnFailure`).
    */
  val retainArtifactsOnFailure: TestAspect[Nothing, ArtifactSession, Nothing, Any] =
    new TestAspect.PerTest.AtLeastR[ArtifactSession]:
      def perTest[R <: ArtifactSession, E](
          test: ZIO[R, TestFailure[E], TestSuccess]
      )(using Trace) =
        test.tapError(_ => ArtifactSession.markFailed.ignore)

  /** On failure, write `artifactsDir/failures/<timestamp>-<label>.png` when Page + ChekhovConfig are in scope. */
  def screenshotOnFailure(label: String = "failure"): TestAspect[Nothing, Page & ChekhovConfig, Nothing, Any] =
    new TestAspect.PerTest.AtLeastR[Page & ChekhovConfig]:
      def perTest[R <: Page & ChekhovConfig, E](
          test: ZIO[R, TestFailure[E], TestSuccess]
      )(using Trace) =
        test.tapError { _ =>
          (for
            config <- ZIO.service[ChekhovConfig]
            page   <- ZIO.service[Page]
            dir = config.artifactsDir.resolve("failures")
            _ <- ensureArtifactsDir(dir)
            path = dir.resolve(s"${java.lang.System.currentTimeMillis()}-$label.png")
            _ <- page.screenshot(path).ignore
          yield ()).ignore
        }
end ChekhovSuite
