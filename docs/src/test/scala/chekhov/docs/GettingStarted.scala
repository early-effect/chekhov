package chekhov.docs

import specular.*
import specular.ziotest.DocSpecSuite
import zio.test.*

object GettingStarted extends DocSpecSuite:
  def doc = page("Getting started")(
    md"""
Chekhov is a ZIO-first Playwright client: `protocol.yml` becomes a Scala AST with zio-json codecs,
then a channel transport talks to the Node Playwright driver. Suites use scoped layers for the
driver, browser, page, and (optionally) Vite/static serve.
""",
    section("Algebras")(
      md"""
Prefer `Chekhov.page` and locator helpers under `ChekhovSuite`. Capabilities are provided with
`ZLayer` (same spirit as saferis `ConnectionProvider` / `Transactor`).
"""
    ),
    section("Extending the command allowlist")(
      md"""
Chekhov claims a **curated** set of Playwright channel methods. Param ADTs in
`Commands.scala` are **generated** from the pinned `protocol.yml`. Do not edit generated
field lists by hand.

**Add a method when** a suite or hub app needs it (for example storage / IndexedDB for
mermoid-class apps).

1. Confirm the method exists: `ProtocolSurface` / `protocol.yml` under the channel’s `commands:`.
2. Append `CommandSpec("Channel", "method", "CaseClassName")` to
   `project/ProtocolCodegen.scala` → `commandAllowlist`.
3. Run `sbt pwCodegen` (also runs as part of `pwBump` / `pwVendor`).
4. Wire `PlaywrightDriver` (and the public algebra if it is user-facing).
5. Commit the allowlist change and regenerated sources together.

See the repo README section **Extending the command allowlist** for the full checklist.
The storage cluster (`storageState`, cookies, `webStorage*`) is already claimed for hub apps.
"""
    ),
  )
end GettingStarted
