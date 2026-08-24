# AGENTS.md

Guidance for agents working in **chekhov**.

## Browser tests: two runtimes, different isolation stories

Chekhov has two browser test runtimes. Which one you are in decides what is shared and what
is isolated.

### JSEnv + chekhov-dom (Scala.js, in-page)

Playwright stays on the JVM. `ChekhovJSEnv` hosts the compiled Scala.js test bundle in
**one page per module start**, shared by every spec and test in the module; ZIO Test runs
suite tests concurrently by default.

- Isolation is iframe-backed: `withRoot` installs each scope inside its own iframe, and
  `withMounted` inherits that isolation. Pass the scoped root to locators instead of
  searching the whole document.
- The **parent document is still shared** across scopes. Whole-document assertions (counting
  `[data-chekhov-root]`, reading global state) can observe a sibling test's state mid-flight
  and flake under concurrent scheduling; intra-suite tests share that one Playwright page, so
  do not assume separate pages per test.
- Assert on **your own** element instead: capture the root inside the scope and check it
  after exit. Per-element detachment holds under any scheduling.

```scala
test("withMounted removes the chekhov root on exit") {
  for
    ref <- Ref.make[Option[dom.Element]](None)
    during <- withMounted(E.div(testId("x"), "hi")) { root =>
      ref.set(Some(root)).as(root.getAttribute("data-chekhov-root") == "true")
    }
    detached <- ref.get.map(_.exists(el => !el.isConnected))
  yield assertTrue(during, detached)
}
```

Do not reach for `TestAspect.sequential` to paper over parent-document races; it masks the
isolation problem instead of fixing it. The unmount machinery (`Mount` under the parent,
`cancelAll` for listeners, `removeChild` on release) is correct and covered by these
per-element tests.

Background: a global-count assertion in this style made "removes the chekhov root on exit"
flake intermittently; an instrumented repro showed each scope removes its own root, with the
overlap explaining the spurious count. Fixed 2026-08 (PR #32).

### JVM ChekhovSuite (out-of-page E2E)

The Node run-driver process is shared per spec / browser fan-out: `processLayers` provides
the transport + one browser, composed with `provideSomeLayerShared`, and `pageLayers` gives
each test a **fresh `BrowserContext` + `Page`**. Concurrent tests in one suite therefore each
get their own page; there is no shared parent document to race on.

- Override `chekhovLayerFor` to add `StaticFileServer.layer` / `AppServer.layer` on the
  **shared process** so serve is not restarted per test (it is applied once per
  `chekhovBrowsers` entry).

### keepOpen (debug)

`chekhovBrowserKeepOpen := true`, or `-Dchekhov.keepOpen=true`, or `CHEKHOV_KEEP_OPEN=true`.
Skips close and parks until Enter; default false. Headed + keep-open is the debug combo for
stepping through a flaky suite.
