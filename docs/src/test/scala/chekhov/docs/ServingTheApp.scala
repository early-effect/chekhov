package chekhov.docs

import chekhov.*
import specular.*
import specular.ziotest.DocSpecSuite
import zio.test.*

import java.nio.file.Path

/** Scoped Vite / static serve for the app under test. */
object ServingTheApp extends DocSpecSuite:

  def doc = page("Serving the app")(
    md"""
E2E needs a URL. Chekhov owns **scoped** serve so the process dies with the test Scope
(success, failure, or interrupt). Logs land under `artifactsDir/serve`.
""",
    section("Vite")(
      md"""
```scala
import chekhov.*
import chekhov.ziotest.ChekhovSuite
import zio.*
import zio.test.*
import java.nio.file.Path

object ViteSpec extends ChekhovSuite:
  override def chekhovConfig =
    ChekhovConfig(baseUrl = Some("http://127.0.0.1:5173"))

  override def chekhovLayerFor(cfg: ChekhovConfig) =
    ZLayer.succeed(cfg) >>>
      (AppServer.viteLayer(Path.of("examples/vite-fixture")) ++ ChekhovSuite.fullStack)

  def spec = suite("vite")(
    test("home") {
      for
        page <- Chekhov.page
        _    <- page.goto("/")
        t    <- page.title
      yield assertTrue(t.nonEmpty)
    }
  )
```

`AppServer.vite` runs `npm run dev` with `--host 127.0.0.1`, a fixed port, `--strictPort`,
and `--open=false`, waits until the ready URL responds, then kills the process on Scope exit.
Customize with `ServeConfig` / `AppServer.serve` when the recipe differs.
"""
    ),
    section("Static files")(
      md"""
For a directory of HTML/JS without a bundler:

```scala
StaticFileServer.layer(Path.of("path/to/static"))
```

Same readiness + Scope story; pick the helper that matches the fixture.
"""
    ),
    section("Ascent / Scala.js link output")(
      md"""
When Vite must see linked Scala.js output, point the fixture at the sbt link directory
(this repo’s ascent example uses `SCALAJS_OUT_DIR` so Vite and `fastLinkJS` do not deadlock).
That wiring lives in the consuming build, not inside Chekhov core. Chekhov only starts the
server and drives the browser once `baseUrl` is up.
""",
      exampleValue {
        ServeConfig(
          command = List("npm", "run", "dev"),
          cwd = Path.of("examples/vite-fixture"),
          readyUrl = "http://127.0.0.1:5173",
        ).readyUrl
      }.assert(url => assertTrue(url.startsWith("http://127.0.0.1:"))),
    ),
  )
end ServingTheApp
