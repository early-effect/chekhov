package chekhov.docs

import chekhov.*
import specular.*
import specular.ziotest.DocSpecSuite
import zio.test.*

/** Local and CI browser install, E2E gates, engine selection. */
object BrowsersAndE2E extends DocSpecSuite:

  def doc = page("Browsers and E2E")(
    md"""
Chekhov drives real Chromium, Firefox, and WebKit. Install matching browser binaries for the
pinned Playwright npm version, then opt into live suites with an E2E gate.
""",
    section("Install browsers")(
      md"""
From the Chekhov repo (or any consumer that has `scripts/install-browsers.sh`):

```bash
./scripts/install-browsers.sh          # or: sbt pwInstall / npm run playwright:install
```

Locally, browsers land in Playwright’s OS cache (`~/.cache/ms-playwright` on Linux,
`~/Library/Caches/ms-playwright` on macOS). The install script clears Cursor sandbox
`PLAYWRIGHT_BROWSERS_PATH` overrides so install and run agree.

**zipx CI** sets `PLAYWRIGHT_BROWSERS_PATH` to `target/ms-playwright` so browsers ride the
LocalDir sbt `actions/cache` key (same epoch / `run_id` restore chain as compile products).
"""
    ),
    section("The E2E gate")(
      md"""
Many suites skip unless you ask for live browsers:

```bash
export CHEKHOV_E2E=1
# or: sbt -Dchekhov.e2e=1 …
```

Without the gate, `sbt test` / `testFull` stay green on machines without browsers (unit and
protocol coverage still run). With the gate, driver dogfood, JSEnv smoke, and `chekhov-dom`
live suites execute.

This repo’s Verify job already exports `CHEKHOV_E2E=1`.
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
