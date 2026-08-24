package chekhov.docs

import chekhov.*
import specular.*
import specular.ziotest.DocSpecSuite
import zio.test.*

import java.nio.file.Path

/** Public algebras, layers, locators, and failure screenshots. */
object AlgebrasTour extends DocSpecSuite:

  def doc = page("Algebras tour")(
    md"""
Capabilities are ZIO services (saferis-style): traits plus companions, composed with
`ZLayer`. `ChekhovSuite` already stacks config → driver → browser → context → page.
""",
    section("Page and locators")(
      md"""
```scala
for
  page <- Chekhov.page
  _    <- page.goto("/login")
  _    <- page.fill("input[name=email]", "a@b.c")
  _    <- page.getByRole(Role.Button, name = Some("Sign in")).click
  text <- page.innerText("h1")
yield assertTrue(text.contains("Welcome"))
```

Public helpers include CSS `locator`, `getByPlaceholder`, `getByRole`, `getByTestId`,
keyboard, title / text, and the storage cluster (`webStorage*`, cookies,
`storageState(indexedDB = true)` for hub apps). See the README allowlist section when you
need a channel method that is not claimed yet.
"""
    ),
    section("Layers you compose")(
      md"""
| Layer | Role |
|-------|------|
| `ChekhovConfig.layer` | browser, headless, `baseUrl`, `artifactsDir` |
| `PlaywrightDriver.processLayers` | transport + shared Node run-driver + one browser per spec / browser fan-out |
| `PlaywrightDriver.pageLayers` | fresh `BrowserContext` + `Page` per test |
| `PlaywrightDriver.suiteLayers` | one-shot composition of process + page layers |
| `StaticFileServer.layer` / `AppServer.layer` | scoped serve |
| `ChekhovSuite.fullStack` | config service ++ driver suite layers |

`ChekhovSuite` runs on the split: the browser process is shared across the spec
(`processLayers`, provided with `provideSomeLayerShared`) while every test gets a fresh
context + page from `pageLayers`. Concurrent tests in one suite therefore each get their
own page.

Override `chekhovLayerFor` when you need serve + stack together: add
`StaticFileServer.layer` / `AppServer.layer` on the **shared process** so serve is not
restarted per test (it is applied once per `chekhovBrowsers` entry).
"""
    ),
    section("Screenshots on failure")(
      md"""
```scala
import chekhov.ziotest.ChekhovSuite

test("…") { … } @@ ChekhovSuite.screenshotOnFailure("login")
```

On failure, writes `artifactsDir/failures/<timestamp>-login.png` when `Page` and
`ChekhovConfig` are in scope. Default `artifactsDir` is `target/chekhov` (cleared by
`sbt clean`). Call `page.screenshot(path)` yourself for intentional captures.
""",
      exampleValue {
        val cfg = ChekhovConfig(artifactsDir = Path.of("target", "chekhov"))
        cfg.artifactsDir.resolve("failures").getFileName.toString
      }.assert(name => assertTrue(name == "failures")),
    ),
    section("Traces and video")(
      md"""
Opt-in via `ArtifactCapture` on `ChekhovConfig` (`Off`, `OnFailure`, or `Always`):

```scala
ChekhovConfig(
  traceCapture = ArtifactCapture.Always,
  videoCapture = ArtifactCapture.OnFailure,
)
```

Traces land under `artifactsDir/traces`; videos under `artifactsDir/videos`. For
`OnFailure`, also apply `ChekhovSuite.retainArtifactsOnFailure` so a failing test marks
the session before the context closes.
""",
      exampleValue {
        ArtifactCapture.fromString("on-failure")
      }.assert(c => assertTrue(c.contains(ArtifactCapture.OnFailure))),
    ),
  )
end AlgebrasTour
