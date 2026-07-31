package chekhov.ascent

/** Optional bridge sketch for ascent JSEnv suites.
  *
  * Full module depends on `ascent-js` + `chekhov-dom`. Call-site pattern:
  *
  * {{{
  * ChekhovDom.withRoot { root =>
  *   AscentApp.mount(ui, root.asInstanceOf[ascent.dom.Element]) *>
  *     ChekhovDom.getByTestId("inc").click
  * }
  * }}}
  *
  * Publish `chekhov-ascent` later; dogfood lives in the ascent repo once Chekhov is published.
  */
object ChekhovAscentDocs:
  val purpose: String = "façade cast + withMounted lifecycle for ascent + chekhov-dom"
