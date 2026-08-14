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
      test("fromList parses a comma-separated chekhovBrowsers value") {
        assertTrue(
          ChekhovBrowser.fromList("firefox") == List(ChekhovBrowser.Firefox),
          ChekhovBrowser.fromList("chromium, firefox") == List(ChekhovBrowser.Chromium, ChekhovBrowser.Firefox),
          ChekhovBrowser.fromList("firefox,netscape,webkit") == List(ChekhovBrowser.Firefox, ChekhovBrowser.WebKit),
        )
      },
      test("listed reads chekhov.browsers and ignores the single-browser fallback") {
        val two = ChekhovBrowser.listed(
          props = Map("chekhov.browsers" -> "firefox,webkit", "chekhov.browser" -> "chromium"),
          env = Map.empty,
        )
        val none = ChekhovBrowser.listed(props = Map("chekhov.browser" -> "firefox"), env = Map.empty)
        assertTrue(two == List(ChekhovBrowser.Firefox, ChekhovBrowser.WebKit), none.isEmpty)
      },
      test("configured falls back to chekhov.browser then Chromium") {
        assertTrue(
          ChekhovBrowser.configured(props = Map("chekhov.browser" -> "firefox"), env = Map.empty) ==
            List(ChekhovBrowser.Firefox),
          ChekhovBrowser.configured(props = Map.empty, env = Map.empty) == List(ChekhovBrowser.Chromium),
          ChekhovBrowser.configured(props = Map("chekhov.browsers" -> "webkit,firefox"), env = Map.empty) ==
            List(ChekhovBrowser.WebKit, ChekhovBrowser.Firefox),
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
