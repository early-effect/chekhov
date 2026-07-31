# Chekhov roadmap

Named for Chekhov's gun: if the UI shows a control, a test should be able to fire it.

Chekhov is an Early Effect **ZIO-first Playwright client**: pinned `protocol.yml` → Scala AST + zio-json codecs + framed channel transport, ZIO algebras, JSEnv / `chekhov-dom`, scoped Vite serve, multi-engine zipx CI, Specular hub, Apache-2.0.

**Not** a wrap of `com.microsoft.playwright`. **Not** a reimplementation of the Playwright server. Sibling niche to [DanHodges/scalajs-playwright](https://github.com/DanHodges/scalajs-playwright), but JVM/ZIO + official Node driver protocol.

Stack target: Scala **3.8.4**, ZIO **2.1.x**, sbt **2.x**, Scala.js **1.22**, Playwright driver pinned (currently **1.62.1**).

---

## Locked decisions

| Decision | Choice |
|----------|--------|
| Architecture | Approach B: ZIO algebras + interpreter over Playwright channel protocol |
| Wire format | Vendored `protocol.yml` → Scala AST + `zio-json` codecs |
| Runtime deps | Scala/ZIO/zio-json + Node `run-driver` + browser binaries; **zero** official binding JARs |
| Test runner | zio-test only |
| Engines | Chromium, Firefox, WebKit (first-class) |
| Lifecycle | ZIO `Scope` / `ZLayer.scoped` / `acquireRelease` (saferis-style capabilities) |
| Serve under test | Scoped process + URL readiness; primary recipe Vite |
| Artifacts | `target/chekhov` (screenshots, traces, video, serve logs) |
| DOM helpers | `chekhov-dom` on **scalajs-dom** (no ascent in core) |
| Org / license | `rocks.earlyeffect`, Apache-2.0, dynver, zipx, Specular docs |

---

## Modules

| Module | Artifact | Role |
|--------|----------|------|
| `core` | `chekhov-core` | Config, errors, algebras, `AppServer` / `StaticFileServer` |
| `protocol` | `chekhov-protocol` | AST + codecs + pipe transport + initialize handshake |
| `driver` | `chekhov-driver` | Interpreter + `PlaywrightDriver` layers |
| `zio-test` | `chekhov-zio-test` | `ChekhovSuite`, multi-browser helpers |
| `dom` | `chekhov-dom` | In-page Scala.js helpers |
| `ascent` | `chekhov-ascent` | `withMounted` for ascent UI + `chekhov-dom` (JSEnv) |
| `jsenv` | `chekhov-jsenv` | Real-browser `JSEnv` (launcher shares driver install) |
| `sbt-chekhov` | `sbt-chekhov` | `jsEnv`, `chekhovInstall`, artifact / browser props |
| `docs` | unpublished | Specular + hub metadata |

---

## Status legend

- **Done** — landed in tree and compiling
- **Partial** — skeleton or MVP slice; not yet complete vs plan
- **Next** — immediate follow-up
- **Later** — planned, not started

---

## Phase 0 — Early Effect skeleton

**Status: Done**

- Multi-module layout, Apache-2.0, dynver, zipx hooks, zio-test, README stub, Specular docs stub
- Hub-oriented metadata (`rocks.earlyeffect`, early-semver)

---

## Phase 1 — Protocol AST + codecs + transport MVP

**Status: Done (MVP)**

### Done

- Vendored `protocol.yml` (Playwright **1.62.1**, merged from `packages/protocol/spec/*.yml`)
- Hand-written MVP AST slice: envelopes, shared types, core commands (`initialize`, `launch`, `goto`, click/fill/press/text, `evaluateExpression`, close, …)
- Length-prefixed JSON pipe transport (`ChannelTransport`) spawning `node … run-driver`
- Request/response correlation with **real-time timeouts** (needs live Clock / `TestAspect.withLiveClock`)
- Initialize handshake: always send `metadata: {}`; collect Playwright `__create__` initializer for browser-type guids
- Saferis-style layers: `ChannelTransport` → `ChannelConnection` → `PlaywrightDriver` (`withBrowserType`, `browserLayer`, `pageLayer`, `suiteLayers`)
- Algebras + companions (`BrowserType`, `Browser`, `BrowserContext`, `Page`, `Locator`, `Keyboard`)
- `Page.evaluate` via Frame `evaluateExpression` (SerializedValue JSON string)
- `ChekhovSuite` with `withLiveClock` + suite timeout
- Static fixture serve (`StaticFileServer`) + `examples/static-fixture`
- Green multi-engine E2E (`CHEKHOV_E2E=1` or `-Dchekhov.e2e=1`) on Chromium / Firefox / WebKit
- Browser install via `./scripts/install-browsers.sh` + zipx Verify Node 24 / cache / `CHEKHOV_E2E=1`
- Unit tests for config, envelope codecs, protocol coverage inventory
- Playwright bump tooling (`pwBump` / `pwVendor` / `pwInstall`)

### Test hygiene (keep)

- Suite-level `TestAspect.timeout(10.seconds)` so runaway tests fail fast
- Any `ZIO.sleep` / `timeout` / readiness loops: `TestAspect.withLiveClock` (or advance `TestClock`)
- Live driver RPCs: transport-level timeout (today ~8s) under a live Clock

---

## Phase 1b — Full protocol AST + coverage

**Status: Partial**

### Decision (Commands)

**Generate command param ADTs from `protocol.yml`.** Field lists must not be hand-maintained: they change with every Playwright pin. Keep a **curated allowlist** of `(channel, method) → case class` (same set as the coverage gate / interpreter surface). On `pwBump` / `pwCodegen`, regenerate those types + codecs from YAML `parameters:`.

Still curated (not generated from YAML field lists):
- Allowlist membership (which methods Chekhov claims)
- Interpreter wiring (`PlaywrightDriver`: which guid/method)
- Envelope discrimination and rare codec edge cases (`emptyCodec` for zero-param commands, binary/guid oddities)

### Done

- `ProtocolCodegen`: SharedTypes + **ProtocolSurface** + **allowlist `Commands`** from YAML `parameters:` (mixin expansion, `emptyCodec` for zero-param)
- `sbt playwrightCodegen` / `pwCodegen` (and on `pwBump` / `pwVendor`): regen SharedTypes + ProtocolSurface + Commands
- Coverage gate: `Commands.allowlist` methods must exist on `ProtocolSurface`; codecs encode

### Next

1. Grow the allowlist / interpreter for dogfood and **hub apps**; prefer typed params over ad-hoc `Json` on the hot path
2. Later: broader public-channel codegen beyond the claimed set (still not all of `protocol.yml`)

### Storage cluster (hub / mermoid)

**Status: Done (MVP)**

- Allowlist: `BrowserContext.storageState` / `setStorageState`, `cookies` / `addCookies` / `clearCookies`, Page `webStorage*`
- Algebra: `Cookie` / `CookieInit` / `StorageItem` / `WebStorageKind`; storage state as Playwright JSON string
- `PlaywrightDriver` + `suiteLayers` now also provides `BrowserContext`
- Gated E2E: `StorageClusterSpec` (webStorage, storageState round-trip, cookies)

---

## Phase 1c — Scoped Vite

**Status: Done (MVP)**

### Done

- `AppServer` / `ServeConfig` / `AppServer.vite` / `viteLayer` (scoped process + readiness + log under `target/chekhov/serve`)
- `examples/vite-fixture` (Vite 7 + module script) + gated `ViteFixtureSpec` (Chromium)
- zipx Verify: `npm ci --prefix examples/vite-fixture` after root `npm ci`

### Next

- Optional: multi-engine Vite dogfood; suite-owned Vite in more than one driver suite

---

## Phase 2 — JSEnv MVP

**Status: Done (MVP)**

### Done

- Real Playwright-backed `ChekhovJSEnv` (channel driver + localhost materialization; no Playwright Java, no NodeJSEnv)
- `HtmlMaterializer` + `ComSetup` (`scalajsCom` / `__chekhovCom` poll bridge) + `BrowserRunner`
- `Page.evaluate` used for Com push/fetch on the main frame
- Static serve MIME for `.js` / `.mjs` / `.css` / `.json`
- Gated Com round-trip E2E on Chromium / Firefox / WebKit (`JsEnvComSpec`)
- `examples/jsenv-smoke`: trivial Scala.js zio-test suite via `ChekhovJsEnvBridge` + `jsenv-smoke/test` in `ciVerify`
- `chekhov-dom`: `withRoot`, `waitFor`, testid / role / CSS locators; fill/click without `js.Dynamic`; live suite under ChekhovJSEnv (`dom/test` in `ciVerify`, skipped unless `CHEKHOV_E2E=1`)
- `sbt-chekhov`: browser/headless/artifacts props; `chekhovInstall` prefers `./scripts/install-browsers.sh`

### Next

- Document ESM / `Input.ESModule` requirements for consuming projects (see README JSEnv)
- Harden console capture via `RunConfig.onOutputStream`

### Publish-friendly JSEnv

**Status: Done (MVP)**

- `ChekhovJSEnv.apply` / `create()` for reflective load
- `sbt-chekhov` depends on `chekhov-jsenv` and exposes `chekhovJSEnv` (`Test / jsEnv := chekhovJSEnv.value`)
- In-repo `ChekhovJsEnvBridge` remains monorepo-only for `jsenv-smoke` / `dom`

---

## Phase 3 — Ascent dogfood

**Status: Done (MVP)**

### Done

- `examples/ascent-fixture`: Maven `ascent-js` counter + Vite; `SCALAJS_OUT_DIR` avoids Vite↔sbt deadlock
- `AscentFixtureSpec`: Chromium / Firefox / WebKit (`driver/test` depends on `writeAscentFixtureOut` → `fastLinkJS`)
- `AppServer.vite`: host/port/`--open=false` + `BROWSER=none`
- `getByPlaceholder` via CSS; `Page.keyboardPress`; `ChekhovSuite` includes `BrowserContext`
- In-repo `chekhov-ascent` (`withMounted` over `chekhov-dom` + `ascent-js`)

### Next

- Richer ascent dogfood in the ascent repo once Chekhov is on Central

---

## Phase 4 — Hardening + hub

**Status: Partial**

### Done

- Artifact dir defaults under `target/chekhov`
- Specular docs site (`docs/specularSite`) + EE theme; Pages at earlyeffect.rocks/chekhov
- Hub catalog entry (`catalog-urls.txt` → earlyeffect.rocks)
- `Page.screenshot` + `ChekhovSuite.screenshotOnFailure` (PNG under `artifactsDir/failures`)
- Publish-friendly `chekhovJSEnv` via `sbt-chekhov` (see Phase 2)
- Specular consumer guide: Quickstart, Browsers and E2E, Serving the app, JSEnv and chekhov-dom, Algebras tour, Contributing
- Opt-in traces / video via `ArtifactCapture` (`Off` / `OnFailure` / `Always`) under `artifactsDir/traces` and `artifactsDir/videos`
- In-repo `chekhov-ascent` (`withMounted`) + README / Specular install notes

### Next

- First `v*` Central release (clears docs `0.0.0-ci` / `v<version>` display)

---

## Non-goals (v1)

- Reimplementing the Playwright server (CDP / selector engines / trace viewer fork)
- `com.microsoft.playwright` or other official binding JARs as deps or public API
- Replacing `@playwright/test` / UI Mode / Node codegen
- Bundling ascent or Specular into core; ascent DOM instead of scalajs-dom
- Driving `fastLinkJS` / replacing vite-plugin-scalajs
- Scala.js-on-Node npm `playwright` façade (DanHodges path)

---

## Success criteria

- ~15-line `ChekhovSuite` E2E with algebras + optional `Chekhov.vite`; classpath free of Playwright Java
- Pinned `protocol.yml` → AST + zio-json codecs; protocol coverage gate green for claimed surface
- JSEnv + `chekhov-dom` without `js.Dynamic` boilerplate for common queries
- CI: Chromium, Firefox, WebKit for driver dogfood and JSEnv smoke; scoped cleanup of serve + driver + browsers
- `sbt clean` clears `target/chekhov` artifacts
- Early Effect sibling: Apache-2.0, zipx, Specular hub metadata

---

## Suggested near-term order

1. First `v*` Central publish + docs regen (fix install chrome version)
2. Grow allowlist further as hub apps need it (IndexedDB-heavy paths already via `storageState(indexedDB = true)`)
3. Richer ascent dogfood in the ascent repo once Chekhov is on Central
