import scala.collection.immutable.ListMap
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*
import complete.DefaultParsers.*
import chekhov.protocol.PlaywrightVendor

val scala3Version  = "3.8.4"
val zioVersion     = "2.1.26"
val zioJsonVersion = "0.10.0"

scalaVersion         := scala3Version
organization         := "rocks.earlyeffect"
organizationName     := "Early Effect"
organizationHomepage := Some(uri("https://www.earlyeffect.rocks"))
versionScheme        := Some("early-semver")

homepage := Some(uri("https://github.com/early-effect/chekhov"))
licenses := Seq("Apache-2.0" -> uri("http://www.apache.org/licenses/LICENSE-2.0.txt"))
scmInfo  := Some(
  ScmInfo(
    uri("https://github.com/early-effect/chekhov"),
    "scm:git@github.com:early-effect/chekhov.git",
  )
)
developers := List(
  Developer(
    "russwyte",
    "Russ White",
    "356303+russwyte@users.noreply.github.com",
    uri("https://github.com/russwyte"),
  )
)

description := "ZIO-first Playwright client and Scala.js browser test toolkit (Chekhov's gun)."

publishTo := {
  val centralSnapshots = "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}
publishMavenStyle    := true
pomIncludeRepository := { _ => false }

usePgpKeyHex(sys.env.getOrElse("PGP_KEY_HEX", "MISSING_KEY_HEX"))

zipxJavaVersion      := "25"
zipxWorkflowDispatch := true
zipxScalaSteward     := true

val ciVerify = "scalafmtCheckAll; zipxWorkflowCheck; testFull; jsenv-smoke/testFull; dom/testFull"
zipxTestTask := ciVerify

val chekhovBrowserSetup: StepContext => List[Step] = ctx =>
  List(
    Step(
      name = Some("Set up Node"),
      uses = Some("actions/setup-node@48b55a011bda9f5d6aeb4c2d9c7362e8dae4041e"), // v6.4.0
      `with` = ListMap(
        "node-version" -> "24",
        "cache"        -> "npm",
      ),
    ),
    Step(
      name = Some("npm ci"),
      run = Some("npm ci"),
    ),
    Step(
      name = Some("npm ci (vite fixture)"),
      run = Some("npm ci --prefix examples/vite-fixture"),
    ),
    Step(
      name = Some("npm ci (ascent fixture)"),
      run = Some("npm ci --prefix examples/ascent-fixture"),
    ),
    Step(
      name = Some("Cache Playwright browsers"),
      uses = Some(ctx.actions.cache),
      `with` = ListMap(
        "path"         -> "~/.cache/ms-playwright",
        "key"          -> "${{ runner.os }}-playwright-${{ hashFiles('package-lock.json') }}",
        "restore-keys" -> "${{ runner.os }}-playwright-",
      ),
    ),
    Step(
      name = Some("Install Playwright browsers"),
      run = Some(
        """|set -euo pipefail
           |chmod +x ./scripts/install-browsers.sh
           |./scripts/install-browsers.sh chromium chromium-headless-shell firefox webkit
           |echo "Playwright $(node -p "require('playwright/package.json').version") browsers ready"
           |""".stripMargin
      ),
    ),
  )

zipxCapabilities += Capability.test.copy(
  command = _ => ciVerify,
  extraSteps = chekhovBrowserSetup,
  env = Map("CHEKHOV_E2E" -> EnvValue.plain("1")),
)
zipxCapabilities += ZipxCentral.release
zipxCapabilities += ZipxDocs.pages()

addCommandAlias("ci", s"; $ciVerify")
addCommandAlias("release", "; publishSigned; sonaRelease")

lazy val playwrightVersion    = settingKey[String]("Pinned Playwright npm + protocol.yml version")
lazy val playwrightBump       = inputKey[Unit]("Bump Playwright pin + vendor protocol. Usage: playwrightBump 1.62.1")
lazy val playwrightBumpLatest = taskKey[Unit]("Bump Playwright to latest npm release")
lazy val playwrightVendorProtocol  = taskKey[Unit]("Re-vendor protocol.yml for current pin")
lazy val playwrightRegenMeta       = taskKey[Unit]("Regen ProtocolMeta.scala from on-disk protocol.yml")
lazy val playwrightCodegen         = taskKey[Unit]("Regen SharedTypes + ProtocolSurface + Commands")
lazy val playwrightInstallBrowsers = taskKey[Unit]("Install browsers via ./scripts/install-browsers.sh")

addCommandAlias("pwBump", "playwrightBump")
addCommandAlias("pwBumpLatest", "playwrightBumpLatest")
addCommandAlias("pwVendor", "playwrightVendorProtocol")
addCommandAlias("pwCodegen", "playwrightCodegen")
addCommandAlias("pwInstall", "playwrightInstallBrowsers")

semanticdbEnabled := true

val commonScalacOptions = Seq(
  "-deprecation",
  "-feature",
  "-Wunused:all",
  "-language:implicitConversions",
)

val zioTestSettings = Def.settings(
  libraryDependencies ++= Seq(
    "dev.zio" %% "zio-test"     % zioVersion % Test,
    "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
  ),
  Test / mainClass := None,
  // Playwright channel + Vite fixtures share Node / ports; parallel suites race on CI.
  Test / parallelExecution := false,
)

// One test task at a time across projects (protocol / driver / jsenv all spawn run-driver).
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)

lazy val root = (project in file("."))
  .aggregate(
    core,
    protocol,
    driver,
    `zio-test`,
    dom,
    jsenv,
    `sbt-chekhov`,
    docs,
  )
  .settings(
    name              := "chekhov",
    publish / skip    := true,
    playwrightVersion := PlaywrightVendor.readPackagePin(baseDirectory.value / "package.json"),
    playwrightBump    := Def.uncached {
      val args = spaceDelimited("<version>").parsed
      val ver  = args match {
        case Seq(v) => v
        case _      => sys.error("Usage: playwrightBump <version>   example: playwrightBump 1.62.1")
      }
      PlaywrightVendor.bump(ver, PlaywrightVendor.defaultPaths(baseDirectory.value), installBrowsers = false)
    },
    playwrightBumpLatest := Def.uncached {
      val ver = PlaywrightVendor.latestNpmVersion()
      PlaywrightVendor.bump(ver, PlaywrightVendor.defaultPaths(baseDirectory.value), installBrowsers = false)
    },
    playwrightVendorProtocol := Def.uncached {
      val ver   = playwrightVersion.value
      val paths = PlaywrightVendor.defaultPaths(baseDirectory.value)
      PlaywrightVendor.vendorProtocol(ver, paths.protocolYml)
      PlaywrightVendor.writeProtocolMeta(ver, paths.protocolYml, paths.protocolMeta)
      PlaywrightVendor.writeGeneratedFromYaml(paths.protocolYml, paths.protocolMeta.getParentFile)
    },
    playwrightRegenMeta := Def.uncached {
      val ver   = playwrightVersion.value
      val paths = PlaywrightVendor.defaultPaths(baseDirectory.value)
      PlaywrightVendor.writeProtocolMeta(ver, paths.protocolYml, paths.protocolMeta)
    },
    playwrightCodegen := Def.uncached {
      val paths = PlaywrightVendor.defaultPaths(baseDirectory.value)
      PlaywrightVendor.writeGeneratedFromYaml(paths.protocolYml, paths.protocolMeta.getParentFile)
    },
    playwrightInstallBrowsers := Def.uncached {
      val code = scala.sys.process.Process(Seq("bash", "./scripts/install-browsers.sh"), baseDirectory.value).!
      if (code != 0) then {
        sys.error(s"install-browsers.sh failed with exit code $code")
      }
    },
  )

lazy val core = (project in file("core"))
  .settings(
    name := "chekhov-core",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"         % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,
    ),
    zioTestSettings,
  )

lazy val protocol = (project in file("protocol"))
  .dependsOn(core)
  .settings(
    name := "chekhov-protocol",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"         % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,
      "dev.zio" %% "zio-json"    % zioJsonVersion,
    ),
    zioTestSettings,
  )

val ascentVersion         = "0.3.1"
lazy val `ascent-fixture` = (project in file("examples/ascent-fixture"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name           := "chekhov-ascent-fixture",
    publish / skip := true,
    scalacOptions ++= commonScalacOptions,
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    libraryDependencies ++= Seq(
      "rocks.earlyeffect" %% "ascent-js" % ascentVersion,
      "dev.zio"           %% "zio"       % zioVersion,
    ),
  )

lazy val writeAscentFixtureOut = taskKey[File]("Write ascent-fixture fastLinkJSOutput path for Vite")
writeAscentFixtureOut := Def.uncached {
  val out    = (`ascent-fixture` / Compile / fastLinkJSOutput).value
  val marker = (`ascent-fixture` / baseDirectory).value / "scalajs-out-dir"
  IO.write(marker, out.getAbsolutePath)
  marker
}

lazy val driver = (project in file("driver"))
  .dependsOn(core, protocol)
  .settings(
    name := "chekhov-driver",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"         % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion,
      "dev.zio" %% "zio-json"    % zioJsonVersion,
    ),
    zioTestSettings,
    Test / loadedTestFrameworks := Def.uncached {
      writeAscentFixtureOut.value
      (Test / loadedTestFrameworks).value
    },
  )

lazy val `zio-test` = (project in file("zio-test"))
  .dependsOn(core, driver)
  .settings(
    name := "chekhov-zio-test",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"          % zioVersion,
      "dev.zio" %% "zio-test"     % zioVersion,
      "dev.zio" %% "zio-test-sbt" % zioVersion,
    ),
  )

lazy val dom = (project in file("dom"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "chekhov-dom",
    scalacOptions ++= commonScalacOptions,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    libraryDependencies ++= Seq(
      "dev.zio"      %% "zio"          % zioVersion,
      "org.scala-js" %% "scalajs-dom"  % "2.8.0",
      "dev.zio"      %% "zio-test"     % zioVersion % Test,
      "dev.zio"      %% "zio-test-sbt" % zioVersion % Test,
    ),
    Test / mainClass    := None,
    Test / definedTests := Def.uncached {
      val enabled =
        sys.env.get("CHEKHOV_E2E").contains("1") ||
          sys.props.get("chekhov.e2e").contains("1")
      if (enabled) then {
        (Test / definedTests).value
      }
      else
        streams.value.log.info("chekhov-dom tests skipped (set CHEKHOV_E2E=1 or -Dchekhov.e2e=1)")
      Seq.empty
    },
    Test / jsEnv := Def.uncached {
      val f = (jsenv / writeJsenvClasspath).value
      new chekhov.build.ChekhovJsEnvBridge(f)
    },
  )

lazy val writeJsenvClasspath = taskKey[File]("Write jsenv classpath for ChekhovJsEnvBridge")

lazy val jsenv = (project in file("jsenv"))
  .dependsOn(protocol, driver)
  .settings(
    name := "chekhov-jsenv",
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= Seq(
      ("org.scala-js" %% "scalajs-js-envs" % "1.6.0").cross(CrossVersion.for3Use2_13),
      "dev.zio"       %% "zio"             % zioVersion,
      "dev.zio"       %% "zio-json"        % zioJsonVersion,
    ),
    zioTestSettings,
    writeJsenvClasspath := Def.uncached {
      given FileConverter = fileConverter.value
      val out = target.value / "chekhov-jsenv.classpath"
      val cp  = (Compile / fullClasspath).value.files
        .map(_.toAbsolutePath.toString)
        .mkString(java.io.File.pathSeparator)
      IO.write(out, cp)
      out
    },
  )

lazy val `jsenv-smoke` = (project in file("examples/jsenv-smoke"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name           := "chekhov-jsenv-smoke",
    publish / skip := true,
    scalacOptions ++= commonScalacOptions,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"          % zioVersion,
      "dev.zio" %% "zio-test"     % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
    ),
    Test / mainClass := None,
    Test / jsEnv     := Def.uncached {
      val f = (jsenv / writeJsenvClasspath).value
      new chekhov.build.ChekhovJsEnvBridge(f)
    },
  )

lazy val `sbt-chekhov` = (project in file("sbt-chekhov"))
  .enablePlugins(SbtPlugin)
  .settings(
    name := "sbt-chekhov",
    scalacOptions ++= commonScalacOptions,
  )

val specularVersion = "0.11.0"

lazy val docs = (project in file("docs"))
  .dependsOn(`zio-test`)
  .enablePlugins(SpecularPlugin)
  .settings(
    name           := "chekhov-docs",
    publish / skip := true,
    scalacOptions ++= commonScalacOptions,
    libraryDependencies ++= Seq(
      "rocks.earlyeffect" %% "specular-core"           % specularVersion % Test,
      "rocks.earlyeffect" %% "specular-zio-test"       % specularVersion % Test,
      "rocks.earlyeffect" %% "specular-site"           % specularVersion % Test,
      "rocks.earlyeffect" %% "early-effect-docs-theme" % specularVersion % Test,
    ),
    // specular-site still declares zio-json 0.9.x
    dependencyOverrides += "dev.zio" %% "zio-json" % zioJsonVersion,
    zioTestSettings,
    specularBuildMain     := "chekhov.docs.BuildSite",
    specularMetaProject   := Some(LocalProject("core")),
    specularArtifactKind  := "library",
    specularSiteDirectory := (ThisBuild / baseDirectory).value / "target" / "site",
    // Docs-only (workflow_dispatch) builds are dynver `-ci`; don't advertise that as a Central coord.
    specularDisplayVersion := {
      val v = (ThisBuild / version).value
      if (v.endsWith("-ci") || v.endsWith("-SNAPSHOT")) then {
        previousStableVersion.value.getOrElse("<version>")
      }
      else {
        ""
      }
    },
  )
