package chekhov

import zio.*
import zio.test.*

import java.nio.file.Path

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
      test("fromProps prefers system properties over env vars") {
        val propWins = ChekhovConfig.fromProps(
          props = Map("chekhov.executablePath" -> "/opt/prop"),
          env = Map("CHEKHOV_EXECUTABLE_PATH" -> "/opt/env"),
        )
        val envOnly = ChekhovConfig.fromProps(props = Map.empty, env = Map("CHEKHOV_EXECUTABLE_PATH" -> "/opt/env"))
        assertTrue(
          propWins.executablePath.contains("/opt/prop"),
          envOnly.executablePath.contains("/opt/env"),
        )
      },
      test("fromProps reads channel from sysprop or env") {
        assertTrue(
          ChekhovConfig
            .fromProps(props = Map("chekhov.channel" -> "chrome"), env = Map.empty)
            .channel
            .contains("chrome"),
          ChekhovConfig
            .fromProps(props = Map.empty, env = Map("CHEKHOV_CHANNEL" -> "msedge"))
            .channel
            .contains("msedge"),
        )
      },
      test("fromProps treats empty strings as unset") {
        val config = ChekhovConfig.fromProps(
          props = Map(
            "chekhov.headless"       -> "",
            "chekhov.baseUrl"        -> "",
            "chekhov.artifactsDir"   -> "",
            "chekhov.executablePath" -> "",
            "chekhov.channel"        -> "",
            "chekhov.launchArgs"     -> "",
          ),
          env = Map.empty,
        )
        assertTrue(
          config.headless,
          config.baseUrl.isEmpty,
          config.artifactsDir == Path.of("target", "chekhov"),
          config.executablePath.isEmpty,
          config.channel.isEmpty,
          config.launchArgs.isEmpty,
        )
      },
      test("fromProps splits launchArgs on commas and whitespace") {
        assertTrue(
          ChekhovConfig
            .fromProps(props = Map.empty, env = Map("CHEKHOV_LAUNCH_ARGS" -> "--no-sandbox --disable-gpu"))
            .launchArgs == List("--no-sandbox", "--disable-gpu"),
          ChekhovConfig.fromProps(props = Map.empty, env = Map("CHEKHOV_LAUNCH_ARGS" -> "--a=1,--b=2")).launchArgs ==
            List("--a=1", "--b=2"),
          ChekhovConfig.fromProps(props = Map("chekhov.launchArgs" -> "  --x ,  --y "), env = Map.empty).launchArgs ==
            List("--x", "--y"),
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
