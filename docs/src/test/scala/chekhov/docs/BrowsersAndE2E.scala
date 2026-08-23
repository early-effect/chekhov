package chekhov.docs

import chekhov.*
import chekhov.protocol.generated.ProtocolMeta
import specular.*
import specular.ziotest.DocSpecSuite
import zio.test.*

/** Local and CI browser install, engine selection. */
object BrowsersAndE2E extends DocSpecSuite:

  def doc = page("Browsers and E2E")(
    md"""
Chekhov drives real Chromium, Firefox, and WebKit. Install matching browser binaries for the
pinned Playwright npm version. A `ChekhovSuite` runs with ordinary `sbt test`.
""",
    section("Keep expensive tests in their own module")(
      md"""
There is no skip flag. If `sbt test` on a library module must stay cheap (no Node, no
browsers), do not put a `ChekhovSuite` there. Give the browser suites their own sbt
project and leave the fast tests where they are:

```scala
lazy val core = (project in file("core"))
  // unit tests only

lazy val e2e = (project in file("e2e"))
  .dependsOn(core)
  .settings(
    libraryDependencies ++= Seq(
      "rocks.earlyeffect" %% "chekhov-zio-test" % "<version>" % Test,
      "rocks.earlyeffect" %% "chekhov-driver"   % "<version>" % Test,
    ),
  )
```

`sbt core/test` never launches Playwright. `sbt e2e/test` (or `e2e/testFull`) does.
CI that should exercise the UI depends on the e2e module; scripted / publish jobs
depend only on `core`.
"""
    ),
    section("Install browsers")(
      md"""
In a consuming repo (no `package.json` required):

```scala
addSbtPlugin("rocks.earlyeffect" % "sbt-chekhov" % "<version>")
```

```bash
sbt chekhovInstall
```

That installs **Playwright ${ProtocolMeta.playwrightProtocolVersion}** (the protocol pin) and
**only** the browsers in `chekhovBrowsers`. `PLAYWRIGHT_DRIVER_CLI` is an override only;
a different version is a hard error that names the pin.

Locally, browsers land in Playwright’s OS cache (`~/.cache/ms-playwright` on Linux,
`~/Library/Caches/ms-playwright` on macOS). The pinned CLI is cached under the Chekhov cache
(`~/Library/Caches/chekhov` on macOS, `~/.cache/chekhov` on Linux), not whatever `npx` last
downloaded.

**This Chekhov repo** still uses `./scripts/install-browsers.sh` (or `sbt pwInstall`) next to
its own `package.json` pin. **zipx CI** sets `PLAYWRIGHT_BROWSERS_PATH` to `target/ms-playwright`
so browsers ride the LocalDir sbt `actions/cache` key (same epoch / `run_id` restore chain as
compile products).
"""
    ),
    section("Pick engines")(
      md"""
One sbt list is both **installed** and **executed**. `ChekhovSuite` runs the spec once per
entry (a labeled copy each). `chekhovInstall` installs that list only: no extra engines,
no ffmpeg.

```scala
chekhovBrowsers := Seq(ChekhovBrowser.Firefox)
// or several:
chekhovBrowsers := Seq(ChekhovBrowser.Chromium, ChekhovBrowser.Firefox)
```

`chekhovBrowser := "firefox"` still works and sets a one-element list. `ChekhovJSEnv` uses
the first entry. Without the plugin, `-Dchekhov.browsers=firefox,chromium` or
`ChekhovSuite.forBrowsers(ChekhovBrowser.Firefox, …)` does the same fan-out.
""",
      exampleValue {
        (
          ChekhovBrowser.fromString("chromium"),
          ChekhovBrowser.fromString("firefox"),
          ChekhovBrowser.fromString("webkit"),
          ChekhovBrowser.fromString("netscape"),
        )
      }.assert { case (cr, ff, wk, bad) =>
        assertTrue(
          cr.contains(ChekhovBrowser.Chromium),
          ff.contains(ChekhovBrowser.Firefox),
          wk.contains(ChekhovBrowser.WebKit),
          bad.isEmpty,
        )
      },
    ),
    section("Use a system browser")(
      md"""
Point Chekhov at a browser already on the machine instead of the revision `sbt chekhovInstall`
downloaded. Three keys do it; each reads a `-Dchekhov.*` system property first, then a
`CHEKHOV_*` environment variable (props win):

| Key | Property | Env var |
|-----|----------|---------|
| Browser binary | `chekhov.executablePath` | `CHEKHOV_EXECUTABLE_PATH` |
| Installed channel (Chromium only) | `chekhov.channel` | `CHEKHOV_CHANNEL` |
| Extra process args | `chekhov.launchArgs` | `CHEKHOV_LAUNCH_ARGS` |

Setting either `executablePath` or `channel` skips the pinned-browser-revision check. The
Playwright driver CLI is still required and must match the pin, so keep running
`sbt chekhovInstall`; only the downloaded browser binary is bypassed.

```bash
CHEKHOV_EXECUTABLE_PATH=/usr/bin/chromium CHEKHOV_LAUNCH_ARGS="--no-sandbox" sbt e2e/testFull
```

`launchArgs` is a flag list: comma or whitespace separates arguments, and an argument that
takes a value uses `--flag=value`. An empty string is treated as unset for every key, so
`CHEKHOV_HEADLESS=""` leaves headless at its default. The example resolves the keys exactly
the way the driver does: the property wins over the env var, the empty value stays unset, and
the flag list splits into two args.
""",
      exampleValue {
        val config = ChekhovConfig.fromProps(
          props = Map("chekhov.executablePath" -> "/usr/bin/chromium", "chekhov.headless" -> ""),
          env = Map("CHEKHOV_EXECUTABLE_PATH" -> "/opt/ignored", "CHEKHOV_LAUNCH_ARGS" -> "--no-sandbox, --disable-gpu"),
        )
        (config.executablePath, config.headless, config.launchArgs)
      }.assert { case (path, headless, args) =>
        assertTrue(
          path.contains("/usr/bin/chromium"),           // property wins over env var
          headless == true,                             // empty string is unset; default holds
          args == List("--no-sandbox", "--disable-gpu"), // comma / whitespace split
        )
      },
    ),
  )
end BrowsersAndE2E
