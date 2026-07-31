package chekhov.driver

import chekhov.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** Live Frame.evaluateExpression smoke. Enable with `CHEKHOV_E2E=1` or `-Dchekhov.e2e=1`. */
object EvaluateSpec extends ZIOSpecDefault:

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
      TestAspect.timeout(60.seconds),
      TestAspect.sequential,
    )

  def spec =
    suite("page.evaluate")(
      test("returns SerializedValue for 1+1") {
        val config = ChekhovConfig(browser = ChekhovBrowser.Chromium, headless = true)
        (for
          page <- ZIO.service[Page]
          _    <- page.goto("about:blank")
          raw  <- page.evaluate("() => 1 + 1", isFunction = true)
          n = raw
            .fromJson[Json]
            .toOption
            .flatMap(_.asObject)
            .flatMap(_.get("n"))
            .flatMap(_.asNumber)
            .map(_.value.intValue)
        yield assertTrue(n.contains(2))).provide(
          ZLayer.succeed(config),
          PlaywrightDriver.suiteLayers,
        )
      },
      test("returns string SerializedValue") {
        val config = ChekhovConfig(browser = ChekhovBrowser.Chromium, headless = true)
        (for
          page <- ZIO.service[Page]
          _    <- page.goto("about:blank")
          raw  <- page.evaluate("""() => "chekhov"""", isFunction = true)
          s = raw
            .fromJson[Json]
            .toOption
            .flatMap(_.asObject)
            .flatMap(_.get("s"))
            .flatMap(_.asString)
        yield assertTrue(s.contains("chekhov"))).provide(
          ZLayer.succeed(config),
          PlaywrightDriver.suiteLayers,
        )
      },
    ) @@ onlyIfE2E
end EvaluateSpec
