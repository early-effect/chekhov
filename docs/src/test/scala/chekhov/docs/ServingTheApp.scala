package chekhov.docs

import chekhov.*
import specular.*
import specular.ziotest.DocSpecSuite
import zio.test.*

import java.nio.file.Path

/** Scoped static / process serve for the app under test. */
object ServingTheApp extends DocSpecSuite:

  def doc = page("Serving the app")(
    md"""
E2E needs a URL. Chekhov owns **scoped** serve so the server or process dies with the test Scope
(success, failure, or interrupt). Process logs land under `artifactsDir/serve`.
""",
    section("Static files")(
      md"""
For a directory of HTML/JS with no bundler, `StaticFileServer` is the default recipe. It binds an
ephemeral port on `127.0.0.1`, serves the directory (`index.html` at `/`), and yields an
`AppServer` whose `baseUrl` you point `ChekhovConfig.baseUrl` at:

```scala
import chekhov.*
import zio.*
import java.nio.file.Path

val server = StaticFileServer.layer(Path.of("path/to/static"))
```

Same Scope story as any other layer: the server stops when the test scope closes.

```scala
object StaticSpec extends ChekhovSuite:
  override def chekhovConfig =
    ChekhovConfig(baseUrl = Some("http://127.0.0.1:8080"))

  override def chekhovLayerFor(cfg: ChekhovConfig) =
    ZLayer.succeed(cfg) >>>
      (StaticFileServer.layer(Path.of("examples/static-fixture")) ++ ChekhovSuite.fullStack)
```
"""
    ),
    section("Scala.js / ascent (sbt-splice)")(
      md"""
For a Scala.js or ascent fixture there is no dev server to run. `sbt-splice` links one
self-contained `fast.js`; stage it next to an `index.html`, then serve that directory with
`StaticFileServer`:

```scala
// build.sbt: spliceFastOutput := baseDirectory.value / "target" / "serve" / "fast.js"
// a staging task writes target/serve/index.html that loads ./fast.js, then:
StaticFileServer.layer(Path.of("examples/ascent-fixture/target/serve"))
```

This repo's `ascent-fixture` does exactly this: `stageAscentFixture` stages `index.html` plus the
`spliceFast` output under `target/serve`. Chekhov only serves the directory and drives the browser
once `baseUrl` is up; the link and stage wiring live in the consuming build.
"""
    ),
    section("Your own process")(
      md"""
If you already run a dev server (any bundler, any stack), point `AppServer.serve` at it with a
`ServeConfig`. Chekhov starts the process, waits until `readyUrl` responds, and kills it on Scope
exit:

```scala
import chekhov.*
import java.nio.file.Path

val cfg = ServeConfig(
  command  = List("npm", "run", "dev"),
  cwd      = Path.of("my-app"),
  readyUrl = "http://127.0.0.1:5173",
)
AppServer.serve(cfg)   // ZIO[Scope & ChekhovConfig, ChekhovError, AppServer]
AppServer.layer(cfg)   // ZLayer[ChekhovConfig, ChekhovError, AppServer]
```

`readyTimeout` (default 45s) bounds the readiness wait; `env` passes extra process env.
""",
      exampleValue {
        ServeConfig(
          command = List("npm", "run", "dev"),
          cwd = Path.of("examples/static-fixture"),
          readyUrl = "http://127.0.0.1:5173",
        ).readyUrl
      }.assert(url => assertTrue(url.startsWith("http://127.0.0.1:"))),
    ),
  )
end ServingTheApp
