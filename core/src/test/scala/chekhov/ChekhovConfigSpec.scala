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
      test("ArtifactCapture fromString") {
        assertTrue(
          ArtifactCapture.fromString("off").contains(ArtifactCapture.Off),
          ArtifactCapture.fromString("on-failure").contains(ArtifactCapture.OnFailure),
          ArtifactCapture.fromString("always").contains(ArtifactCapture.Always),
        )
      },
      test("default layer") {
        for c <- ZIO.service[ChekhovConfig]
        yield assertTrue(
          c.artifactsDir.toString.contains("chekhov"),
          c.traceCapture == ArtifactCapture.Off,
          c.videoCapture == ArtifactCapture.Off,
        )
      }.provideLayer(ChekhovConfig.layer),
    ) @@ TestAspect.timeout(10.seconds)
end ChekhovConfigSpec
