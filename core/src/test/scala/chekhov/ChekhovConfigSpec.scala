package chekhov

import zio.*
import zio.test.*

object ChekhovConfigSpec extends ZIOSpecDefault:
  def spec =
    suite("ChekhovConfig")(
      test("browser fromString") {
        assertTrue(
          ChekhovBrowser.fromString("chromium").contains(ChekhovBrowser.Chromium),
          ChekhovBrowser.fromString("firefox").contains(ChekhovBrowser.Firefox),
          ChekhovBrowser.fromString("webkit").contains(ChekhovBrowser.WebKit),
        )
      },
      test("default layer") {
        for c <- ZIO.service[ChekhovConfig]
        yield assertTrue(c.artifactsDir.toString.contains("chekhov"))
      }.provideLayer(ChekhovConfig.layer),
    ) @@ TestAspect.timeout(10.seconds)
end ChekhovConfigSpec
