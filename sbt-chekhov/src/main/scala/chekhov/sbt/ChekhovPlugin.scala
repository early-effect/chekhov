package chekhov.sbt

import chekhov.ChekhovBrowser
import chekhov.jsenv.ChekhovJSEnv
import chekhov.protocol.PinnedPlaywright
import org.scalajs.jsenv.JSEnv
import sbt.*
import sbt.Keys.*

/** Browser / artifact props for JVM suites, plus `chekhovJSEnv` for Scala.js `Test / jsEnv`. */
object ChekhovPlugin extends AutoPlugin:
  override def requires = plugins.JvmPlugin
  override def trigger  = allRequirements

  object autoImport:
    val chekhovBrowser  = settingKey[String]("Single Playwright browser: chromium | firefox | webkit")
    val chekhovBrowsers = settingKey[Seq[ChekhovBrowser]](
      "Browsers to install and run ChekhovSuite against (one suite copy each)"
    )
    val chekhovHeadless        = settingKey[Boolean]("Run Playwright headless")
    val chekhovBrowserKeepOpen = settingKey[Boolean]("Keep the headed browser open after tests; press Enter to exit")
    val chekhovArtifactsDir    = settingKey[File]("Directory for Chekhov screenshots/traces/serve logs")
    val chekhovInstall         = taskKey[Unit](
      s"Install the pinned Playwright ${PinnedPlaywright.version} CLI and chekhovBrowsers binaries"
    )

    /** Playwright-backed JSEnv. On Scala.js projects: `Test / jsEnv := chekhovJSEnv.value`. */
    val chekhovJSEnv = settingKey[JSEnv]("ChekhovJSEnv from chekhovBrowsers.head / chekhovHeadless")
  end autoImport

  import autoImport.*

  override def projectSettings: Seq[Setting[?]] = Seq(
    chekhovBrowser         := "chromium",
    chekhovBrowsers        := ChekhovBrowser.fromString(chekhovBrowser.value).toSeq,
    chekhovHeadless        := true,
    chekhovBrowserKeepOpen := false,
    chekhovArtifactsDir    := (Test / target).value / "chekhov",
    chekhovJSEnv           := Def.uncached {
      val browser = chekhovBrowsers.value.headOption.getOrElse(ChekhovBrowser.Chromium)
      ChekhovJSEnv(browser = browser, headless = chekhovHeadless.value, keepOpen = chekhovBrowserKeepOpen.value)
    },
    Test / javaOptions ++= Def.uncached {
      val browsers = chekhovBrowsers.value
      val primary  = browsers.headOption.getOrElse(ChekhovBrowser.Chromium)
      Seq(
        s"-Dchekhov.browser=${primary.channelName}",
        s"-Dchekhov.browsers=${browsers.map(_.channelName).mkString(",")}",
        s"-Dchekhov.headless=${chekhovHeadless.value}",
        s"-Dchekhov.keepOpen=${chekhovBrowserKeepOpen.value}",
        s"-Dchekhov.artifactsDir=${chekhovArtifactsDir.value.getAbsolutePath}",
      )
    },
    Test / fork    := true,
    chekhovInstall := Def.uncached {
      val log      = streams.value.log
      val browsers = chekhovBrowsers.value.toList
      if browsers.isEmpty then sys.error("chekhovBrowsers is empty")
      PinnedPlaywright.install(browsers = browsers, log = msg => log.info(msg)) match
        case Left(err)  => sys.error(err)
        case Right(cli) =>
          log.info(
            s"Pinned Playwright ${PinnedPlaywright.version} CLI: $cli (${browsers.map(_.channelName).mkString(", ")})"
          )
    },
  )
end ChekhovPlugin
