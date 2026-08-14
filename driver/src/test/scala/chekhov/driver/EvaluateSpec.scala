package chekhov.driver

import chekhov.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** Live Frame.evaluateExpression smoke. */
object EvaluateSpec extends ZIOSpecDefault:

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
    )
end EvaluateSpec
