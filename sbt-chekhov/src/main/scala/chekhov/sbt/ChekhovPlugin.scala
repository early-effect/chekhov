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
    val chekhovBrowser      = settingKey[String]("Playwright browser: chromium | firefox | webkit")
    val chekhovHeadless     = settingKey[Boolean]("Run Playwright headless")
    val chekhovArtifactsDir = settingKey[File]("Directory for Chekhov screenshots/traces/serve logs")
    val chekhovInstall      = taskKey[Unit](
      s"Install the pinned Playwright ${PinnedPlaywright.version} CLI and matching browser binaries"
    )

    /** Playwright-backed JSEnv. On Scala.js projects: `Test / jsEnv := chekhovJSEnv.value`. */
    val chekhovJSEnv = settingKey[JSEnv]("ChekhovJSEnv from chekhovBrowser / chekhovHeadless")
  end autoImport

  import autoImport.*

  override def projectSettings: Seq[Setting[?]] = Seq(
    chekhovBrowser      := "chromium",
    chekhovHeadless     := true,
    chekhovArtifactsDir := (Test / target).value / "chekhov",
    chekhovJSEnv        := {
      val browser =
        ChekhovBrowser.fromString(chekhovBrowser.value).getOrElse(ChekhovBrowser.Chromium)
      ChekhovJSEnv(browser = browser, headless = chekhovHeadless.value)
    },
    Test / javaOptions ++= Seq(
      s"-Dchekhov.browser=${chekhovBrowser.value}",
      s"-Dchekhov.headless=${chekhovHeadless.value}",
      s"-Dchekhov.artifactsDir=${chekhovArtifactsDir.value.getAbsolutePath}",
    ),
    Test / fork    := true,
    chekhovInstall := {
      val log = streams.value.log
      PinnedPlaywright.install(log = msg => log.info(msg)) match
        case Left(err)  => sys.error(err)
        case Right(cli) =>
          log.info(s"Pinned Playwright ${PinnedPlaywright.version} CLI: $cli")
    },
  )
end ChekhovPlugin
