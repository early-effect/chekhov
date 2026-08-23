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

  /** Stack for one config (override to add static serve or `AppServer.layer`). Fanned out per `chekhovBrowsers`. */
  def chekhovLayerFor(cfg: ChekhovConfig): ZLayer[Any, ChekhovError, ChekhovSuite.Env] =
    ZLayer.succeed(cfg) >>> ChekhovSuite.fullStack

  /** Layers for [[chekhovConfig]] (first / only browser if you provide this yourself). */
  def chekhovLayer: ZLayer[Any, ChekhovError, ChekhovSuite.Env] =
    chekhovLayerFor(chekhovConfig)

  override def aspects =
    Chunk(
      TestAspect.samples(1),
      TestAspect.withLiveClock,
      TestAspect.timeout(60.seconds),
      ChekhovSuite.onBrowsers(chekhovConfig, chekhovLayerFor),
    )
end ChekhovSuite

object ChekhovSuite:

  type Env = ChekhovConfig & ArtifactSession & BrowserType & Browser & BrowserContext & Page

  val fullStack: ZLayer[ChekhovConfig, ChekhovError, Env] =
    ZLayer.service[ChekhovConfig] ++ PlaywrightDriver.suiteLayers

  /** Run `make` once per browser. Empty `browsers` uses `-Dchekhov.browsers` / `chekhovBrowsers`. */
  def forBrowsers[R, E](
      browsers: ChekhovBrowser*
  )(make: ChekhovBrowser => Spec[R, E]): Spec[R, E] =
    val list = if browsers.isEmpty then ChekhovBrowser.configured() else browsers.toList
    suite("chekhov browsers")(
      list.map(b => suite(b.channelName)(make(b)))*
    )

  /** One copy of the spec per configured browser, each with its own driver stack. */
  def onBrowsers(
      base: ChekhovConfig,
      layerFor: ChekhovConfig => ZLayer[Any, ChekhovError, Env] = cfg => ZLayer.succeed(cfg) >>> fullStack,
      browsers: List[ChekhovBrowser] = ChekhovBrowser.listed(),
  ): TestAspect[Nothing, Any, Nothing, Any] =
    new TestAspect[Nothing, Any, Nothing, Any]:
      def some[R, E](spec: Spec[R, E])(using Trace): Spec[R, E] =
        val run   = if browsers.isEmpty then List(base.browser) else browsers
        val parts = run.map { b =>
          val cfg   = base.copy(browser = b)
          val inner = spec.provideSomeLayer[R](layerFor(cfg)).asInstanceOf[Spec[R, E]]
          if run.tail.isEmpty then inner else suite(b.channelName)(inner)
        }
        if parts.tail.isEmpty then parts.head
        else suite("chekhov")(parts*)

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
