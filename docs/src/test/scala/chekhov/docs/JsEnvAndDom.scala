package chekhov.docs

import chekhov.*
import specular.*
import specular.ziotest.DocSpecSuite
import zio.test.*

/** Published ChekhovJSEnv + in-page chekhov-dom helpers. */
object JsEnvAndDom extends DocSpecSuite:

  def doc = page("JSEnv and chekhov-dom")(
    md"""
JVM E2E drives the app from outside the page. **JSEnv** runs Scala.js tests *inside* a real
Playwright browser so you can assert on live DOM without `js.Dynamic` ceremony.
""",
    section("Wire ChekhovJSEnv")(
      md"""
Published consumers: add the plugin (it depends on `chekhov-jsenv`) and set `jsEnv`:

```scala
// project/plugins.sbt
addSbtPlugin("rocks.earlyeffect" % "sbt-chekhov" % "<version>")

// build.sbt
import chekhov.sbt.ChekhovPlugin.autoImport.*

libraryDependencies += "rocks.earlyeffect" %%% "chekhov-dom" % "<version>" % Test
Test / jsEnv := chekhovJSEnv.value
// or: Test / jsEnv := ChekhovJSEnv()
// or: Test / jsEnv := ChekhovJSEnv(ChekhovBrowser.Firefox)
```

Prefer `ModuleKind.ESModule` (or otherwise materializable scripts) for linked test output.
`ChekhovJSEnv` materializes `Input.Script` / `Input.ESModule` onto a localhost page and bridges
`scalajsCom` via frame `evaluateExpression`.

**This monorepo** still uses an internal classpath bridge for `jsenv-smoke` / `dom` so CI need
not publish first. Consumers should not copy that bridge.
"""
    ),
    section("chekhov-dom helpers")(
      md"""
```scala
import chekhov.dom.ChekhovDom.*
import zio.test.*

test("focus") {
  withRoot { root =>
    for
      _     <- ZIO.succeed(/* mount UI under root */)
      _     <- getByRole("textbox", root).click
      start <- getByRole("textbox", root).selectionStart
    yield assertTrue(start >= 0)
  }
}
```

`withRoot` installs a scoped throwaway parent under `document.body`. Locators cover
testid / role / CSS; `waitFor` polls until the node appears. Depend on
`org.scala-js:::scalajs-dom` only (not ascent’s DOM facade).
"""
    ),
    section("Isolation: one page per module")(
      md"""
`ChekhovJSEnv` hosts the whole module's compiled test bundle in **one Playwright page**,
shared by every spec and test in the module (ZIO Test runs them concurrently).

- `withRoot` installs each scope inside its own **iframe**, so scoped DOM work is isolated
  from sibling scopes. Pass the scoped root to locators (`getByTestId("inc", root)`), not
  the whole document.
- The **parent document is still shared**: whole-document assertions (element counts, global
  state) can observe a sibling test mid-flight and flake. Assert on your own element: capture
  the root inside the scope and check it after exit.

```scala
test("count starts at zero") {
  withRoot { root =>
    for
      _ <- ZIO.succeed(mount(ui))
      t <- getByTestId("count", root).innerText
    yield assertTrue(t == "0")
  }
}
```
"""
    ),
    section("chekhov-ascent")(
      md"""
Ascent UI under JSEnv lives in **`chekhov-ascent`** (`ChekhovAscent.withMounted`), not
core. It mounts into a `chekhov-dom` root and tears down via ascent’s subscription bag:

```scala
libraryDependencies += "rocks.earlyeffect" %%% "chekhov-ascent" % "<version>" % Test

import chekhov.ascent.ChekhovAscent.withMounted
import chekhov.dom.*

withMounted(ui) { root =>
  getByTestId("inc", root).click
}
```

Use `chekhov-dom` alone when you do not need ascent.
"""
    ),
    section("Smoke in this repo")(
      md"""
```bash
sbt 'jsenv/testOnly chekhov.jsenv.JsEnvComSpec'
sbt jsenv-smoke/testFull
sbt dom/testFull
sbt ascent/testFull
```
""",
      exampleValue {
        ChekhovBrowser.fromString("firefox").map(_.channelName)
      }.assert(name => assertTrue(name.contains("firefox"))),
    ),
  )
end JsEnvAndDom
