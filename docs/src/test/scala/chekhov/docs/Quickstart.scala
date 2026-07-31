package chekhov.docs

import chekhov.*
import chekhov.protocol.generated.ProtocolSurface
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
Add the suite stack (`chekhov-zio-test` + `chekhov-driver`), install browsers, set `CHEKHOV_E2E=1`,
then:

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

`ChekhovSuite` wires config + driver + a fresh page per test. Override `chekhovConfig` when you
need a different browser, `baseUrl`, or `artifactsDir`. Point `baseUrl` at a running app (or use
scoped Vite / static serve; see **Serving the app**).
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
