# chekhov

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Docs](https://img.shields.io/badge/docs-earlyeffect.rocks-blue)](https://www.earlyeffect.rocks/chekhov/)

**ZIO-first** Playwright client for Scala 3, plus a Scala.js DOM helper / JSEnv path for real-browser component tests.
Named for *Chekhov's gun*: if the UI shows a control, a test should be able to fire it.

> **Status: early / pre-1.0.** Published under [early-semver](https://www.scala-sbt.org/1.x/docs/Publishing.html#Version+scheme).
> No `com.microsoft.playwright` JAR: Chekhov speaks Playwright's channel protocol from Scala/ZIO.

```scala
import chekhov.*
import chekhov.ziotest.ChekhovSuite
import zio.test.*

object TodoSpec extends ChekhovSuite:
  def spec = suite("todo")(
    test("add") {
      for
        page <- Chekhov.page
        _    <- page.goto("/")
        _    <- page.fill("input.new-todo", "milk")
        _    <- page.press("input.new-todo", "Enter")
        text <- page.innerText(".todo-list")
      yield assertTrue(text.contains("milk"))
    }
  )
```

Scala.js ascent component suite (JSEnv):

```scala
import chekhov.ascent.ChekhovAscent.withMounted
import chekhov.dom.*
import ascent.*, ascent.dsl.*
import zio.test.*

withMounted(ui) { root =>
  getByTestId("inc", root).click *>
    getByTestId("count", root).innerText.map(t => assertTrue(t == "1"))
}
```

## Modules

| Module | Artifact | Role |
|--------|----------|------|
| `core` | `chekhov-core` | Config, errors, ZIO algebras, scoped `AppServer` / static serve |
| `protocol` | `chekhov-protocol` | `protocol.yml` → Scala AST + zio-json codecs + pipe transport |
| `driver` | `chekhov-driver` | Channel interpreter (`PlaywrightDriver` layers) |
| `zio-test` | `chekhov-zio-test` | `ChekhovSuite`, multi-browser helpers |
| `dom` | `chekhov-dom` | Scala.js in-page helpers (`withRoot`, waits, testid/role/CSS) |
| `ascent` | `chekhov-ascent` | `withMounted` bridge for ascent UI + `chekhov-dom` (JSEnv) |
| `jsenv` | `chekhov-jsenv` | Playwright-backed `ChekhovJSEnv` (scripts in a real browser page) |
| `sbt-chekhov` | `sbt-chekhov` | Artifact dir / browser props + pinned `chekhovInstall` |

## Install

Suite stack (JVM):

```scala
libraryDependencies ++= Seq(
  "rocks.earlyeffect" %% "chekhov-zio-test" % "<version>" % Test,
  "rocks.earlyeffect" %% "chekhov-driver"   % "<version>" % Test,
)
```

Scala.js DOM helpers and real-browser JSEnv:

```scala
libraryDependencies += "rocks.earlyeffect" %%% "chekhov-dom" % "<version>" % Test
// project/plugins.sbt — pulls chekhov-jsenv onto the sbt classpath
addSbtPlugin("rocks.earlyeffect" % "sbt-chekhov" % "<version>")
```

Ascent component tests (pulls `chekhov-dom` transitively):

```scala
libraryDependencies += "rocks.earlyeffect" %%% "chekhov-ascent" % "<version>" % Test
```

```scala
import chekhov.sbt.ChekhovPlugin.autoImport.*
// or: import chekhov.jsenv.ChekhovJSEnv

Test / jsEnv := chekhovJSEnv.value
// equivalent: Test / jsEnv := ChekhovJSEnv()
```

### Playwright pin (consumers)

Chekhov speaks **this release's** Playwright channel (currently **1.62.1**). A leftover
`npx` CLI, Cursor MCP driver, or another project's `node_modules` on `NODE_PATH` is not
a valid substitute.

```bash
sbt chekhovInstall          # needs sbt-chekhov; no package.json in the consumer
sbt 'Test/testOnly …'
```

`chekhovInstall` extracts `playwright@1.62.1` into the Chekhov cache and installs the
matching Chromium / Firefox / WebKit revisions into Playwright's OS cache. Tests then
find that pin without `PLAYWRIGHT_DRIVER_CLI`.

`PLAYWRIGHT_DRIVER_CLI` remains an override, but the CLI's `package.json` version must
equal the pin. A mismatch fails **before** `run-driver` (a skew protocol looks like a
Chekhov bug). Missing browser revisions name the pin and the command to run.

There is no skip flag. If a module's `test` must stay cheap, put `ChekhovSuite`s in a
separate sbt project (`e2e`) and leave unit tests in `core`. `sbt core/test` never
launches browsers; `sbt e2e/test` does.

### Artifacts

Default `artifactsDir` is `target/chekhov` (override with `-Dchekhov.artifactsDir` / `CHEKHOV_ARTIFACTS_DIR`):

| Path | Contents |
|------|----------|
| `failures/` | PNGs from `ChekhovSuite.screenshotOnFailure` |
| `traces/` | Playwright trace zips when `traceCapture` is `Always` or kept `OnFailure` |
| `videos/` | Recorded videos when `videoCapture` is `Always` or kept `OnFailure` |
| `serve/` | Scoped Vite/static serve logs |

```scala
ChekhovConfig(
  traceCapture = ArtifactCapture.Always,   // Off | OnFailure | Always
  videoCapture = ArtifactCapture.OnFailure,
)
// For OnFailure, also: suite(...)(...) @@ ChekhovSuite.retainArtifactsOnFailure
```

Local browsers / E2E **in this repo**:

```bash
npm ci
npm ci --prefix examples/vite-fixture
npm ci --prefix examples/ascent-fixture
./scripts/install-browsers.sh   # or: npm run playwright:install / sbt pwInstall
sbt testFull
```

Browsers land in Playwright’s default OS cache (`~/.cache/ms-playwright` on Linux,
`~/Library/Caches/ms-playwright` on macOS). Consuming projects should run
`sbt chekhovInstall` instead of copying this script or adding `playwright` to a
`package.json`. zipx CI installs under `target/ms-playwright` so browsers share
the LocalDir sbt cache key.

## Bumping Playwright

Chekhov pins a Playwright **npm** version and vendors the matching channel
`protocol.yml` (merged from `packages/protocol/spec/*.yml` on modern tags). Keep
those in sync; do not edit the vendored YAML by hand.

**One-liner (recommended):**

```bash
sbt 'playwrightBump 1.62.1'          # or: sbt 'pwBump 1.62.1'
sbt playwrightBumpLatest             # or: sbt pwBumpLatest
sbt playwrightInstallBrowsers        # or: sbt pwInstall   (optional, after bump)
```

`playwrightBump <version>` / `playwrightBumpLatest` will:

1. Pin `devDependencies.playwright` in `package.json` and run `npm install`
2. Download the protocol for that GitHub tag and write
   `protocol/src/main/resources/playwright/protocol.yml`
3. Regenerate `protocol/.../generated/ProtocolMeta.scala` (version + definition inventory)

It regenerates `ProtocolMeta`, `SharedTypes`, `ProtocolSurface`, and allowlist
**`Commands`** (param ADTs from YAML `parameters:`). Envelopes stay small/stable unless
the wire shape changes. After a bump:

1. Skim the `protocol.yml` / `Commands.scala` diff for claimed-surface changes
2. Extend the allowlist + `PlaywrightDriver` if the dogfood path needs a new method, then `sbt pwCodegen`
3. `sbt pwInstall` if you need matching browser binaries locally
4. `sbt 'protocol/testOnly chekhov.protocol.DriverSmokeSpec' 'driver/testOnly chekhov.driver.MultiBrowserFixtureSpec'`

**CI / zipx:** Verify runs `npm ci`, then `./scripts/install-browsers.sh` with
`PLAYWRIGHT_BROWSERS_PATH` (build-wide `zipxEnv`, omitted from reusable-workflow
callers since zipx 0.1.3) set to `target/ms-playwright` so browsers land under the
LocalDir `target` path and share the **same** sbt `actions/cache` key (epoch +
`run_id`). Mid-PR pushes reuse that key. After merge, Verify is skipped;
`cache-rehydrate` runs the same browser `extraSteps` plus `compile` so `main`
saves digests **and** browsers for the next PR. Linux CI also caches Playwright
`install-deps` `.deb`s under `~/.cache/chekhov-apt-archives` (keyed on
`package-lock.json`) so apt mostly reuses local archives. After a `pwBump`,
install may fetch new browser/apt revisions once. Commit `package-lock.json`
with the bump. `zipxWorkflowCheck` is part of `sbt ci` so `build.sbt` setup
steps cannot drift from `.github/workflows/ci.yml`. Regenerate with
`sbt zipxWorkflowGenerate` only when you change zipx settings (Node version,
browser env, etc.), not on every Playwright pin bump.

**Granular tasks:**

| Task | Alias | What it does |
|------|-------|----------------|
| `playwrightBump <ver>` | `pwBump` | Full pin + vendor + ProtocolMeta + SharedTypes/Surface/Commands |
| `playwrightBumpLatest` | `pwBumpLatest` | Same, version from `npm view playwright version` |
| `playwrightVendorProtocol` | `pwVendor` | Re-vendor YAML + ProtocolMeta + SharedTypes/Surface/Commands |
| `playwrightRegenMeta` | | ProtocolMeta only (from on-disk YAML) |
| `playwrightCodegen` | `pwCodegen` | SharedTypes + ProtocolSurface + allowlist Commands |
| `playwrightInstallBrowsers` | `pwInstall` | `./scripts/install-browsers.sh` |
| `playwrightVersion` | | Show the pin read from `package.json` |

```bash
sbt 'show playwrightVersion'
```

## Extending the command allowlist

Chekhov does **not** implement every Playwright channel method. Claimed methods live in a
curated allowlist; param ADTs are **generated** from `protocol.yml`. Do not edit
`protocol/.../generated/Commands.scala` field lists by hand.

**When to add:** a dogfood suite or hub app (e.g. mermoid needing `storageState` /
IndexedDB / `webStorage*`) needs a channel method that is not yet claimed.

**How to add:**

1. Confirm the method exists for the pinned protocol:
   - `ProtocolSurface.has("<Channel>", "<method>")` (after `sbt pwCodegen`), or
   - search `protocol/src/main/resources/playwright/protocol.yml` under that channel’s `commands:`
2. Append a row to `ProtocolCodegen.commandAllowlist` in
   `project/ProtocolCodegen.scala`:

   ```scala
   CommandSpec("BrowserContext", "storageState", "BrowserContextStorageState"),
   //           ^channel           ^YAML method    ^Scala case class name
   ```

3. Regenerate:

   ```bash
   sbt pwCodegen
   ```

4. Wire the interpreter in `PlaywrightDriver` (guid + `conn.send(..., "method", Commands.YourType(...))`)
   and, if it is part of the public API, expose it on the ZIO algebra in `core`.
5. Extend coverage / a small test if the method is load-bearing (storage cluster, etc.).
6. Commit the allowlist change **and** the regenerated `Commands.scala` /
   `ProtocolSurface.scala` / `SharedTypes.scala` as needed.

**What stays curated (not generated from YAML fields):** allowlist membership, driver
wiring, algebra surface. **What is generated:** case class fields + codecs for each
allowlist entry (including `$mixin` expansion).

**Hub storage cluster:** `BrowserContext.storageState` / `setStorageState` (IndexedDB via
`indexedDB = true`), cookies (`cookies` / `addCookies` / `clearCookies`), page
`webStorage*` (`WebStorageKind.Local` / `Session`).

## ZIO layers (saferis-style)

Services are traits with `ZLayer` companions:

- `ChannelTransport.layer` — scoped Node `run-driver` pipe
- `ChannelConnection.layer` — initialize + request/response
- `PlaywrightDriver.withBrowserType` / `browserLayer` / `pageLayer`
- `AppServer.viteLayer(dir)` / `StaticFileServer.layer(dir)` — scoped serve + readiness
- `ChekhovConfig.layer` — env / `-Dchekhov.*` defaults; artifacts under `target/chekhov`

## JSEnv

`ChekhovJSEnv` runs Scala.js scripts inside a real Playwright browser (same channel
driver / browser install as the JVM client). Materializes `Input.Script` /
`Input.ESModule` onto a localhost page and bridges `scalajsCom` via frame
`evaluateExpression`.

**Consumers (published artifacts):** add `sbt-chekhov` (brings `chekhov-jsenv` onto the
sbt classpath) and set:

```scala
Test / jsEnv := chekhovJSEnv.value
// or: Test / jsEnv := chekhov.jsenv.ChekhovJSEnv()
// or: Test / jsEnv := ChekhovJSEnv(ChekhovBrowser.Firefox)
```

Use `ModuleKind.ESModule` (or ensure scripts are materializable) for linked test
output. This repo’s `jsenv-smoke` / `dom` projects still use an internal classpath
bridge so the monorepo need not publish to exercise CI.

Live smoke (from this repo, with browsers installed):

```bash
sbt 'jsenv/testOnly chekhov.jsenv.JsEnvComSpec'
sbt jsenv-smoke/testFull
sbt dom/testFull
```

## Engines

Chromium, Firefox, and WebKit are first-class. Use `ChekhovSuite.forBrowsers` or `-Dchekhov.browser=firefox`.

## Contributing

Once per clone, enable the scalafmt pre-commit hook:

```bash
./scripts/install-git-hooks
```

Formatting is enforced (`sbt scalafmtCheckAll`). CI workflows are generated by
[zipx](https://github.com/early-effect/zipx) (`sbt zipxWorkflowGenerate` after module changes).

## License

Apache-2.0
