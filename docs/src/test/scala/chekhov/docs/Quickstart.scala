package chekhov.docs

import chekhov.*
import chekhov.protocol.generated.{ProtocolMeta, ProtocolSurface}
import specular.*
import specular.ziotest.DocSpecSuite
import zio.test.*

/** First-run consumer path: suite shape, stack, and a live protocol assert. */
object Quickstart extends DocSpecSuite:

  def doc = page("Quickstart")(
    md"""
Chekhov is a **ZIO-first** Playwright client: pinned `protocol.yml` becomes a typed Scala AST,
a Node driver speaks the channel, and suites compose browser / context / page as layers.

If the UI shows a control, a test should be able to fire it.
""",
    section("A suite in one screenful")(
      md"""
Add the suite stack, install this Chekhov version's Playwright, then run the tests:

```scala
// build.sbt
libraryDependencies ++= Seq(
  "rocks.earlyeffect" %% "chekhov-zio-test" % "<version>" % Test,
  "rocks.earlyeffect" %% "chekhov-driver"   % "<version>" % Test,
)

// project/plugins.sbt (optional, for chekhovInstall + JSEnv)
addSbtPlugin("rocks.earlyeffect" % "sbt-chekhov" % "<version>")
```

```bash
sbt chekhovInstall
sbt 'Test/testOnly …'
```

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

No consumer `package.json`. A `ChekhovSuite` in a module just runs when you test that
module. Keep browser suites in their own sbt project if `core/test` must stay cheap
(see **Browsers and E2E**). `chekhovInstall` extracts Playwright
`${ProtocolMeta.playwrightProtocolVersion}` into the Chekhov cache and installs matching
Chromium / Firefox / WebKit. A leftover `npx` CLI or a different Playwright on
`NODE_PATH` is a hard error, not a silent driver.

`ChekhovSuite` wires config + driver + a fresh page per test (fresh context + page; the browser
process is shared across the spec, so concurrent tests never share a page). Override
`chekhovConfig` when you need a different browser, `baseUrl`, or `artifactsDir`. Point `baseUrl`
at a running app (or use StaticFileServer or AppServer.serve; see **Serving the app**).
"""
    ),
    section("How the stack fits")(
      md"""
```mermaid
flowchart LR
  Suite[ChekhovSuite] --> Driver[PlaywrightDriver]
  Driver --> Channel[channel transport]
  Channel --> Node[Node run-driver]
  Node --> Browser[Chromium Firefox WebKit]
```

There is **no** `com.microsoft.playwright` JAR. Chekhov claims a curated set of channel methods
and interprets them over the official Node driver. Scala.js DOM tests take a different path
(`ChekhovJSEnv` + `chekhov-dom`); see **JSEnv and chekhov-dom**.
"""
    ),
    section("Claimed surface stays honest")(
      md"""
`ProtocolSurface` is generated from the pinned protocol inventory. Navigation and input are
**Frame** channel methods on the wire (public algebra still looks like `page.goto` /
`page.click`). Coverage gates assert the allowlist against this inventory.
""",
      exampleValue {
        (
          ProtocolSurface.has("Frame", "goto"),
          ProtocolSurface.has("Frame", "click"),
          ProtocolSurface.has("Frame", "fill"),
          ProtocolSurface.has("Page", "screenshot"),
        )
      }.assert { case (goto, click, fill, shot) =>
        assertTrue(goto, click, fill, shot)
      },
    ),
  )
end Quickstart
