package chekhov.jsenv.smoke

import zio.test.*

object SmokeSpec extends ZIOSpecDefault:
  def spec =
    suite("jsenv-smoke")(
      test("runs inside ChekhovJSEnv") {
        assertTrue(1 + 1 == 2)
      }
    )
end SmokeSpec
