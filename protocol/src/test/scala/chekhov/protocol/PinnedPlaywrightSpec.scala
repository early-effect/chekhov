package chekhov.protocol

import chekhov.ChekhovBrowser
import zio.*
import zio.test.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

object PinnedPlaywrightSpec extends ZIOSpecDefault:

  def spec =
    suite("PinnedPlaywright")(
      test("resolves PLAYWRIGHT_DRIVER_CLI when package.json matches the pin") {
        withTemp { tmp =>
          val cli    = writeCli(tmp.resolve("ok"), PinnedPlaywright.version)
          val lookup = lookupAt(tmp, env = Map("PLAYWRIGHT_DRIVER_CLI" -> cli.toString))
          val got    = PinnedPlaywright.resolve(lookup)
          assertTrue(
            got.exists(_.packageVersion == PinnedPlaywright.version),
            got.exists(_.cli.normalize == cli.normalize),
          )
        }
      },
      test("rejects PLAYWRIGHT_DRIVER_CLI when package.json is a different Playwright") {
        withTemp { tmp =>
          val cli    = writeCli(tmp.resolve("npx"), "1.61.0-alpha")
          val lookup = lookupAt(tmp, env = Map("PLAYWRIGHT_DRIVER_CLI" -> cli.toString))
          val got    = PinnedPlaywright.resolve(lookup)
          assertTrue(
            got == Left(PinnedPlaywright.Problem.Skew("1.61.0-alpha", cli.toAbsolutePath.normalize)),
            got.left.exists(_.message.contains(PinnedPlaywright.version)),
            got.left.exists(_.message.contains("1.61.0-alpha")),
            got.left.exists(_.message.contains(cli.toAbsolutePath.normalize.toString)),
          )
        }
      },
      test("rejects PLAYWRIGHT_DRIVER_CLI when cli.js is missing") {
        withTemp { tmp =>
          val missing = tmp.resolve("gone").resolve("cli.js")
          val lookup  = lookupAt(tmp, env = Map("PLAYWRIGHT_DRIVER_CLI" -> missing.toString))
          val got     = PinnedPlaywright.resolve(lookup)
          assertTrue(
            got == Left(PinnedPlaywright.Problem.MissingOverride(missing)),
            got.left.exists(_.message.contains(PinnedPlaywright.version)),
            got.left.exists(_.message.contains("sbt chekhovInstall")),
          )
        }
      },
      test("resolves the Chekhov cache for the pin") {
        withTemp { tmp =>
          val cache  = tmp.resolve("cache")
          val lookup = lookupAt(tmp, env = Map("CHEKHOV_CACHE" -> cache.toString))
          val cli    = writeCli(PinnedPlaywright.packageDir(lookup), PinnedPlaywright.version)
          val got    = PinnedPlaywright.resolve(lookup)
          assertTrue(got.exists(_.cli.normalize == cli.normalize))
        }
      },
      test("resolves ./node_modules/playwright when it matches the pin") {
        withTemp { tmp =>
          val cwd    = tmp.resolve("project")
          val cli    = writeCli(cwd, PinnedPlaywright.version)
          val lookup = lookupAt(tmp, cwd = cwd)
          val got    = PinnedPlaywright.resolve(lookup)
          assertTrue(got.exists(_.cli.normalize == cli.normalize))
        }
      },
      test("walks up from a forked module cwd to a matching repo-root node_modules") {
        withTemp { tmp =>
          val root   = tmp.resolve("repo")
          val module = root.resolve("driver")
          Files.createDirectories(module)
          val cli    = writeCli(root, PinnedPlaywright.version)
          val lookup = lookupAt(tmp, cwd = module)
          val got    = PinnedPlaywright.resolve(lookup)
          assertTrue(got.exists(_.cli.normalize == cli.normalize))
        }
      },
      test("rejects ./node_modules/playwright when it is a different version") {
        withTemp { tmp =>
          val cwd    = tmp.resolve("project")
          val cli    = writeCli(cwd, "1.50.0")
          val lookup = lookupAt(tmp, cwd = cwd)
          val got    = PinnedPlaywright.resolve(lookup)
          assertTrue(got == Left(PinnedPlaywright.Problem.Skew("1.50.0", cli.toAbsolutePath.normalize)))
        }
      },
      test("does not pick NODE_PATH, npm_config_prefix, or a leftover require.resolve CLI") {
        withTemp { tmp =>
          val prefix = tmp.resolve("prefix")
          val other  = writeCli(prefix.resolve("lib"), "1.61.0-alpha")
          val lookup = lookupAt(
            tmp,
            env = Map(
              "npm_config_prefix" -> prefix.toString,
              "NODE_PATH"         -> other.getParent.toString,
            ),
          )
          val got = PinnedPlaywright.resolve(lookup)
          assertTrue(
            got == Left(PinnedPlaywright.Problem.Missing),
            got.left.exists(_.message.contains(s"Playwright ${PinnedPlaywright.version}")),
            got.left.exists(_.message.contains("sbt chekhovInstall")),
            !got.left.exists(_.message.contains("npm i -D playwright")),
          )
        }
      },
      test("prefers the Chekhov cache over a mismatched cwd node_modules") {
        withTemp { tmp =>
          val cache   = tmp.resolve("cache")
          val project = tmp.resolve("project")
          writeCli(project, "1.61.0")
          val cachedLookup = lookupAt(tmp, cwd = project, env = Map("CHEKHOV_CACHE" -> cache.toString))
          val cached       = writeCli(PinnedPlaywright.packageDir(cachedLookup), PinnedPlaywright.version)
          val got          = PinnedPlaywright.resolve(cachedLookup)
          assertTrue(got.exists(_.cli.normalize == cached.normalize))
        }
      },
      test("missing CLI names the pin and chekhovInstall") {
        withTemp { tmp =>
          val msg = PinnedPlaywright.resolve(lookupAt(tmp)).swap.toOption.get.message
          assertTrue(
            msg.startsWith(s"chekhov: Playwright ${PinnedPlaywright.version} CLI not found"),
            msg.contains("`sbt chekhovInstall`"),
            msg.contains("PLAYWRIGHT_DRIVER_CLI"),
          )
        }
      },
      test("requireBrowser fails when the pin's revision is absent and names a sibling revision") {
        withTemp { tmp =>
          val cli      = writeCli(tmp.resolve("pw"), PinnedPlaywright.version)
          val browsers = tmp.resolve("ms-playwright")
          writeBrowsersJson(cli, ChekhovBrowser.Firefox, "1538")
          Files.createDirectories(browsers.resolve("firefox-1539"))
          val lookup = lookupAt(
            tmp,
            env = Map(
              "PLAYWRIGHT_DRIVER_CLI"    -> cli.toString,
              "PLAYWRIGHT_BROWSERS_PATH" -> browsers.toString,
            ),
          )
          val got = PinnedPlaywright.requireBrowser(ChekhovBrowser.Firefox, lookup)
          assertTrue(
            got == Left(
              PinnedPlaywright.Problem.BrowserMissing(ChekhovBrowser.Firefox, "1538", List("firefox-1539"))
            ),
            got.left.exists(_.message.contains("Firefox revision 1538")),
            got.left.exists(_.message.contains(PinnedPlaywright.version)),
            got.left.exists(_.message.contains("firefox-1539")),
            got.left.exists(_.message.contains("sbt chekhovInstall")),
          )
        }
      },
      test("requireBrowser succeeds when the pin's revision directory exists") {
        withTemp { tmp =>
          val cli      = writeCli(tmp.resolve("pw"), PinnedPlaywright.version)
          val browsers = tmp.resolve("ms-playwright")
          writeBrowsersJson(cli, ChekhovBrowser.Chromium, "1234")
          Files.createDirectories(browsers.resolve("chromium-1234"))
          val lookup = lookupAt(
            tmp,
            env = Map(
              "PLAYWRIGHT_DRIVER_CLI"    -> cli.toString,
              "PLAYWRIGHT_BROWSERS_PATH" -> browsers.toString,
            ),
          )
          val got = PinnedPlaywright.requireBrowser(ChekhovBrowser.Chromium, lookup)
          assertTrue(got.fold(_.message, _ => "ok") == "ok")
        }
      },
      test("cache layout is chekhov/playwright/<pin>") {
        withTemp { tmp =>
          val cache  = tmp.resolve("cache")
          val lookup = lookupAt(tmp, env = Map("CHEKHOV_CACHE" -> cache.toString))
          assertTrue(
            PinnedPlaywright.packageDir(lookup) == cache.resolve("playwright").resolve(PinnedPlaywright.version),
            PinnedPlaywright.cliInCache(lookup).endsWith(Path.of("node_modules", "playwright", "cli.js")),
            PinnedPlaywright.defaultInstallBrowsers == List(
              ChekhovBrowser.Chromium,
              ChekhovBrowser.Firefox,
              ChekhovBrowser.WebKit,
            ),
          )
        }
      },
    ) @@ TestAspect.timeout(10.seconds) @@ TestAspect.sequential

  private def lookupAt(
      tmp: Path,
      cwd: Path = null,
      env: Map[String, String] = Map.empty,
  ): PinnedPlaywright.Lookup =
    val home = tmp.resolve("home")
    Files.createDirectories(home)
    val work = if cwd == null then tmp.resolve("empty-project") else cwd
    Files.createDirectories(work)
    PinnedPlaywright.Lookup(env = env, cwd = work, userHome = home)
  end lookupAt

  private def writeCli(root: Path, version: String): Path =
    val pkgDir = root.resolve("node_modules").resolve("playwright")
    Files.createDirectories(pkgDir)
    Files.writeString(
      pkgDir.resolve("package.json"),
      s"""{"name":"playwright","version":"$version"}""",
      StandardCharsets.UTF_8,
    )
    val cli = pkgDir.resolve("cli.js")
    Files.writeString(cli, "#!/usr/bin/env node\n", StandardCharsets.UTF_8)
    cli
  end writeCli

  private def writeBrowsersJson(cli: Path, browser: ChekhovBrowser, revision: String): Unit =
    val core = cli.getParent.getParent.resolve("playwright-core")
    Files.createDirectories(core)
    Files.writeString(
      core.resolve("browsers.json"),
      s"""{"browsers":[{"name":"${browser.channelName}","revision":"$revision"}]}""",
      StandardCharsets.UTF_8,
    )

  private def withTemp[A](f: Path => A): A =
    val tmp = Files.createTempDirectory("chekhov-pin-")
    try f(tmp)
    finally deleteRecursively(tmp)

  private def deleteRecursively(path: Path): Unit =
    if Files.isDirectory(path) then
      val stream = Files.list(path)
      try stream.iterator().asScala.foreach(deleteRecursively)
      finally stream.close()
    Files.deleteIfExists(path)

end PinnedPlaywrightSpec
