package chekhov.ziotest

import zio.*
import zio.test.*

/** A browser keeps wall time, so a ChekhovSuite builds the stack its tests share on the live clock, where the driver
  * handshake's timeouts can fire. The clock comes from the suite's environment; Chekhov never names one.
  */
object ChekhovSuiteSpec extends ZIOSpecDefault:
  /** The clock the shared stack was built with, standing in for the browser a ChekhovSuite would start. */
  final case class BuiltWith(clock: Clock)

  private val stack = TestAspect.fromLayerShared(ZLayer(ZIO.clock.map(BuiltWith(_))))

  override def aspects = ChekhovSuite.aspectsAround(stack)

  def spec = suite("ChekhovSuite")(
    test("builds the stack its tests share on the live clock, and runs each test on it") {
      for
        built <- ZIO.environmentWith[Any](_.getDynamic[BuiltWith])
        now   <- ZIO.clock
      yield assertTrue(built.map(_.clock).contains(Clock.ClockLive), now == Clock.ClockLive)
    }
  )
end ChekhovSuiteSpec
