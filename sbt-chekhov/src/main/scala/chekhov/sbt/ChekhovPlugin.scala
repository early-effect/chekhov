package chekhov.sbt

import sbt.*
import sbt.Keys.*

object ChekhovPlugin extends AutoPlugin:
  override def trigger = allRequirements

  object autoImport:
    val chekhovBrowser      = settingKey[String]("Playwright browser: chromium | firefox | webkit")
    val chekhovHeadless     = settingKey[Boolean]("Run Playwright headless")
    val chekhovArtifactsDir = settingKey[File]("Directory for Chekhov screenshots/traces/serve logs")
    val chekhovInstall      = taskKey[Unit]("Install Playwright browsers via scripts/install-browsers.sh")

  import autoImport.*

  override def projectSettings: Seq[Setting[?]] = Seq(
    chekhovBrowser      := "chromium",
    chekhovHeadless     := true,
    chekhovArtifactsDir := (Test / target).value / "chekhov",
    Test / javaOptions ++= Seq(
      s"-Dchekhov.browser=${chekhovBrowser.value}",
      s"-Dchekhov.headless=${chekhovHeadless.value}",
      s"-Dchekhov.artifactsDir=${chekhovArtifactsDir.value.getAbsolutePath}",
    ),
    Test / fork    := true,
    chekhovInstall := {
      import scala.sys.process.*
      val root   = (ThisBuild / baseDirectory).value
      val script = root / "scripts" / "install-browsers.sh"
      val code   =
        if script.isFile then Seq("bash", script.getAbsolutePath).!
        else Seq("npx", "--yes", "playwright", "install", "chromium", "firefox", "webkit").!
      if code != 0 then sys.error("chekhovInstall failed")
    },
  )
end ChekhovPlugin
