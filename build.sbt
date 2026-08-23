import scala.collection.immutable.ListMap
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport.*
import complete.DefaultParsers.*
import chekhov.protocol.PlaywrightVendor
// sbt has its own `Exec` (a queued command), so the two wildcards collide. An explicit named
// import outranks both, and this is the one we mean: the shell AST's simple command.
import zipx.shell.Exec

MyVersions.settings

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

// The Playwright install, as a shell AST rather than a stripMargin block: quoting, globbing and
// command substitution are the model's business, so an unquoted "${apt_mirror}"/*.deb or a stray
// tab is a compile error. zipx's ConsumerStepsSpec asserts this renders byte-identically to the
// text it replaces, which is why the regenerated ci.yml diff stays empty.
val aptMirrorPath: Word = Word.dquote(Word.vBraced("HOME"), Word.lit("/.cache/chekhov-apt-archives"))
val aptMirror: Word     = Word.dquote(Word.vBraced("apt_mirror"))
val mirrorDebs: Word    = Word.cat(aptMirror, Word.lit("/*.deb"))
val cacheDebs: Word     = Word.lit("/var/cache/apt/archives/*.deb")

def debCount(glob: Word): Word.Subst =
  Word.subst(
    Exec("ls", Word.lit("-1"), glob) | Exec("wc", Word.lit("-l")) | Exec("tr", Word.lit("-d"), Word.squote(" "))
  )

def anyFilesMatch(glob: Word): ShTest = ShTest.succeeds(Exec("ls", glob).silenced)

// `|| true` so a copy that finds nothing to do does not trip `set -e`. Trailing `||` because
// .sbt files parse under the scala213source3 dialect, which rejects a leading infix operator.
def orTrue(command: zipx.shell.InlineCommand): zipx.shell.InlineCommand = command || Exec("true")

val installBrowsers: Script =
  Script
    .strict(
      Assign("apt_mirror", aptMirrorPath),
      Exec("mkdir", Word.lit("-p"), aptMirror),
      Exec("sudo", Word.lit("mkdir"), Word.lit("-p"), Word.lit("/var/cache/apt/archives/partial")),
      If(
        anyFilesMatch(mirrorDebs),
        Block(
          Exec(
            "echo",
            Word.dquote(
              Word.lit("Seeding apt archives from "),
              Word.vBraced("apt_mirror"),
              Word.lit(" ("),
              debCount(mirrorDebs),
              Word.lit(" debs)"),
            ),
          ),
          orTrue(Exec("sudo", Word.lit("cp"), Word.lit("-n"), mirrorDebs, Word.lit("/var/cache/apt/archives/"))),
        ),
      ),
      Exec("mkdir", Word.lit("-p"), Word.dquote(Word.vBraced("PLAYWRIGHT_BROWSERS_PATH"))),
      Exec("chmod", Word.lit("+x"), Word.lit("./scripts/install-browsers.sh")),
      Exec.of(
        "./scripts/install-browsers.sh",
        List(Word.lit("chromium"), Word.lit("chromium-headless-shell"), Word.lit("firefox"), Word.lit("webkit")),
      ),
      If(
        anyFilesMatch(cacheDebs),
        Block(
          orTrue(
            Exec(
              "sudo",
              Word.lit("cp"),
              Word.lit("-n"),
              cacheDebs,
              Word.dquote(Word.vBraced("apt_mirror"), Word.lit("/")),
            )
          ),
          Exec(
            "sudo",
            Word.lit("chown"),
            Word.lit("-R"),
            Word.dquote(Word.subst(Exec("id", Word.lit("-u"))), Word.lit(":"), Word.subst(Exec("id", Word.lit("-g")))),
            aptMirror,
          ),
          Exec("echo", Word.dquote(Word.lit("Apt mirror now has "), debCount(mirrorDebs), Word.lit(" debs"))),
        ),
      ),
      Exec(
        "echo",
        Word.dquote(
          Word.lit("Playwright "),
          Word.subst(Exec("node", Word.lit("-p"), Word.dquote(Word.lit("require('playwright/package.json').version")))),
          Word.lit(" browsers ready"),
        ),
      ),
    )
    .withTrailingNewline(true)

/** Takes the pins rather than reading them off a StepContext, so Steps.built can collect any raw
  * escape hatch a future step introduces (Steps.buildingWith runs too late to report one).
  */
def chekhovBrowserSetup(pins: ActionPins): Steps =
  Steps.built("chekhov-browsers")(
    // usesRef, not uses: an ActionPins field is already a validated ActionRef.
    Step
      .usesRef(pins.cache)
      .named("Cache Playwright apt packages")
      .withInputs(
        ListMap(
          // User-writable mirror of /var/cache/apt/archives (needs sudo to seed apt).
          "path" -> "~/.cache/chekhov-apt-archives",
          "key"  -> Expr
            .concat(
              Expr.runner("os"),
              Expr.lit("-chekhov-apt-"),
              Expr.call("hashFiles", Expr.quoted("package-lock.json")),
            )
            .render,
          "restore-keys" -> Expr.concat(Expr.runner("os"), Expr.lit("-chekhov-apt-")).render,
        )
      ),
    Step
      .usesRef(pins.setupNode)
      .named("Set up Node")
      .withInputs(ListMap("node-version" -> "24", "cache" -> "npm")),
    Step.run(Script(Exec("npm", Word.lit("ci")))).named("npm ci"),
    Step
      .run(Script(Exec("npm", Word.lit("ci"), Word.lit("--prefix"), Word.lit("examples/vite-fixture"))))
      .named("npm ci (vite fixture)"),
    Step
      .run(Script(Exec("npm", Word.lit("ci"), Word.lit("--prefix"), Word.lit("examples/ascent-fixture"))))
      .named("npm ci (ascent fixture)"),
    // Browsers under target/ms-playwright (zipxEnv) ride LocalDir; apt .debs are mirrored
    // into ~/.cache/chekhov-apt-archives so install-deps can reuse them across runs.
    Step.run(installBrowsers).named("Install Playwright browsers"),
  )

zipxJavaVersion      := JdkVersion("25")
zipxWorkflowDispatch := true
// LocalDir: after merge, skip full Verify but emit cache-rehydrate so main gets an
// actions/cache save later PRs can restore. Browser setup on rehydrate (0.1.2+);
// path on zipxEnv (0.1.3+ omits env from reusable-workflow callers like ZipxDocs).
zipxCacheRehydrateOnMerge    := true
zipxCacheRehydrateExtraSteps := chekhovBrowserSetup(zipxActions.value)
zipxEnv := Map(
  "PLAYWRIGHT_BROWSERS_PATH" -> EnvValue.typed(Expr.github("workspace") ++ Expr.lit("/target/ms-playwright")),
)

zipxCapabilities += ZipxCentral.release
zipxCapabilities += ZipxDocs.pages()

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
  MyVersions.zioTests,
  Test / mainClass := None,
  // Playwright channel + Vite fixtures share Node / ports; parallel suites race on CI.
  Test / parallelExecution := false,
)

// One task at a time across the build (Tags.Test alone still allows parallel project tests on sbt 2).
Global / concurrentRestrictions := Seq(Tags.limitAll(1))

lazy val root = (project in file("."))
  .aggregate(
    core,
    protocol,
    driver,
    `zio-test`,
    dom,
    ascent,
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
    MyVersions.zioCore,
    zioTestSettings,
  )

lazy val protocol = (project in file("protocol"))
  .dependsOn(core)
  .settings(
    name := "chekhov-protocol",
    scalacOptions ++= commonScalacOptions,
    MyVersions.zioProtocol,
    zioTestSettings,
  )

lazy val `ascent-fixture` = (project in file("examples/ascent-fixture"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name           := "chekhov-ascent-fixture",
    publish / skip := true,
    scalacOptions ++= commonScalacOptions,
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    MyVersions.ascentLib,
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
    MyVersions.zioProtocol,
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
    MyVersions.zioTestLib,
  )

lazy val dom = (project in file("dom"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "chekhov-dom",
    scalacOptions ++= commonScalacOptions,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    MyVersions.domLib,
    zioTestSettings,
    // Monorepo only: load ChekhovJSEnv via classpath file (consumers use chekhovJSEnv / ChekhovJSEnv()).
    Test / jsEnv := Def.uncached {
      val f = (jsenv / writeJsenvClasspath).value
      new chekhov.build.ChekhovJsEnvBridge(f)
    },
  )

lazy val ascent = (project in file("ascent"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(dom)
  .settings(
    name := "chekhov-ascent",
    scalacOptions ++= commonScalacOptions,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    MyVersions.ascentLib,
    zioTestSettings,
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
    MyVersions.jsenvLib,
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
    MyVersions.zioOnly,
    zioTestSettings,
    // Monorepo only: see ChekhovJsEnvBridge. Consumers: Test / jsEnv := ChekhovJSEnv().
    Test / jsEnv := Def.uncached {
      val f = (jsenv / writeJsenvClasspath).value
      new chekhov.build.ChekhovJsEnvBridge(f)
    },
  )

lazy val `sbt-chekhov` = (project in file("sbt-chekhov"))
  .enablePlugins(SbtPlugin)
  .dependsOn(jsenv)
  .settings(
    name := "sbt-chekhov",
    scalacOptions ++= commonScalacOptions,
  )

lazy val docs = (project in file("docs"))
  .dependsOn(`zio-test`)
  .enablePlugins(SpecularPlugin)
  .settings(
    name           := "chekhov-docs",
    publish / skip := true,
    scalacOptions ++= commonScalacOptions,
    MyVersions.docsTest,
    // Pin docs to the catalog zio-json in case specular-site's transitive pin drifts from it.
    dependencyOverrides += MyVersions.moduleID(MyVersions.zioJson),
    zioTestSettings,
    specularBuildMain     := "chekhov.docs.BuildSite",
    specularMetaProject   := Some(LocalProject("core")),
    specularArtifactKind  := "library",
    specularSiteDirectory := (ThisBuild / baseDirectory).value / "target" / "site",
    // CI docs builds are dynver `-ci`; stripCi drops the suffix so install snippets show the last published tag.
    specularDisplayVersion := stripCi,
  )

// Aggregate `testFull` still fans out across modules; run Playwright-heavy suites one at a time.
// Builtin fmt / workflow-check / advisories stay parallel; do not make test wait on fmt.
// Overriding the builtin `test` capability by name replaces its command too, so restate it here.
lazy val ciVerify: SbtCommand = zipxTasks.session(
  core / testFull,
  protocol / testFull,
  driver / testFull,
  jsenv / testFull,
  `zio-test` / testFull,
  docs / testFull,
  `jsenv-smoke` / testFull,
  dom / testFull,
)

zipxTestTask := ciVerify
zipxCapabilities += Capability.once(
  name = Capability.TestName,
  command = ciVerify,
  extraSteps = chekhovBrowserSetup(zipxActions.value),
)

addCommandAlias("ci", s"; ${ciVerify.text}")

