package chekhov.docs

import chekhov.protocol.generated.ProtocolSurface
import specular.*
import specular.ziotest.DocSpecSuite
import zio.test.*

/** Contributor notes: allowlist growth (ops stay in the README for bumps). */
object Contributing extends DocSpecSuite:

  def doc = page("Contributing")(
    md"""
Consumer guides live on the other pages. This note is for **claiming a new Playwright channel
method** when a suite or hub app needs it. Playwright pin bumps and `pwBump` live in the
[repo README](https://github.com/early-effect/chekhov#bumping-playwright).
""",
    section("Extending the command allowlist")(
      md"""
Chekhov claims a **curated** set of channel methods. Param ADTs in `Commands.scala` are
**generated** from the pinned `protocol.yml`. Do not edit generated field lists by hand.

1. Confirm the method exists: `ProtocolSurface.has("Channel", "method")` or search
   `protocol.yml` under that channel’s `commands:`.
2. Append `CommandSpec("Channel", "method", "CaseClassName")` to
   `project/ProtocolCodegen.scala` → `commandAllowlist`.
3. Run `sbt pwCodegen` (also part of `pwBump` / `pwVendor`).
4. Wire `PlaywrightDriver` (and the public algebra if it is user-facing).
5. Commit the allowlist change and regenerated sources together.

The storage cluster (`storageState`, cookies, `webStorage*`) is already claimed for hub apps.
""",
      exampleValue {
        ProtocolSurface.has("BrowserContext", "storageState")
      }.assert(claimed => assertTrue(claimed)),
    ),
  )
end Contributing
