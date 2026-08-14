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
the matching Chromium / Firefox / WebKit revisions. `PLAYWRIGHT_DRIVER_CLI` is an override only;
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
    section("Pick an engine")(
      md"""
Defaults come from `ChekhovConfig` (`-Dchekhov.browser` / `CHEKHOV_BROWSER`, headless flags,
`artifactsDir` under `target/chekhov`):

```scala
ChekhovConfig(browser = ChekhovBrowser.Firefox, headless = true)
```

`sbt-chekhov` mirrors that with `chekhovBrowser` / `chekhovHeadless` settings. Multi-engine
suites can fan out with `ChekhovSuite.forBrowsers(ChekhovBrowser.Chromium, …)`.
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
  )
end BrowsersAndE2E
