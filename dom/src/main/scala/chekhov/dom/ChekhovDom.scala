package chekhov.dom

import org.scalajs.dom
import zio.*

/** In-page DOM helpers for Chekhov JSEnv suites (scalajs-dom, no ascent). */
object ChekhovDom:

  /** Scoped throwaway parent in its own same-origin iframe under `document.body`.
    *
    * The whole module runs in one page, so each scope gets an isolated `about:blank` iframe document instead of a div
    * in the shared body. Concurrent scopes cannot see each other's nodes; the parent document only ever sees the
    * iframes themselves. The element passed to `use` lives inside the iframe and carries `data-chekhov-root="true"`;
    * release removes the iframe (and its contents) from the parent document. Default locators still search the parent
    * `document.body`.
    */
  def withRoot[R, E, A](use: dom.Element => ZIO[R, E, A])(using Trace): ZIO[R, E | Throwable, A] =
    ZIO.scoped {
      for
        iframe <- ZIO.acquireRelease(createIframe)(removeNode)
        doc    <- waitForContentDocument(iframe)
        root   <- ZIO.acquireRelease(innerRoot(doc))(removeNode)
        a      <- use(root)
      yield a
    }

  private def createIframe: UIO[dom.HTMLIFrameElement] =
    ZIO.succeed {
      val el = dom.document.createElement("iframe").asInstanceOf[dom.HTMLIFrameElement]
      el.setAttribute("data-chekhov-root", "true")
      el.src = "about:blank"
      dom.document.body.appendChild(el)
      el
    }

  private def removeNode(el: dom.Node): UIO[Unit] =
    ZIO.succeed(Option(el.parentNode).foreach(_.removeChild(el))).unit

  private def waitForContentDocument(iframe: dom.HTMLIFrameElement): IO[Throwable, dom.Document] =
    def attempt: UIO[Option[dom.Document]]              = ZIO.succeed(Option(iframe.contentDocument))
    def loop(retries: Int): IO[Throwable, dom.Document] =
      attempt.flatMap {
        case Some(doc)           => ZIO.succeed(doc)
        case None if retries > 0 => ZIO.sleep(15.millis) *> loop(retries - 1)
        case None                => ZIO.fail(new RuntimeException("iframe contentDocument stayed null"))
      }
    loop(20)

  private def innerRoot(doc: dom.Document): UIO[dom.Element] =
    ZIO.succeed {
      val el = doc.createElement("div")
      el.setAttribute("data-chekhov-root", "true")
      val parent = doc match
        case html: dom.HTMLDocument => Option(html.body).getOrElse(doc.documentElement)
        case _                      => doc.documentElement
      parent.appendChild(el)
      el
    }

  final case class DomLocator(selector: String, root: dom.Element = dom.document.body):
    def query(using Trace): IO[Throwable, dom.Element] =
      waitFor(selector, root)

    def click(using Trace): IO[Throwable, Unit] =
      // Iframe nodes fail `instanceof` against the parent window's HTMLElement.
      query.map(el => el.asInstanceOf[dom.HTMLElement].click())

    def fill(value: String)(using Trace): IO[Throwable, Unit] =
      query.flatMap { el =>
        if isInput(el) then
          ZIO.succeed {
            val input = el.asInstanceOf[dom.HTMLInputElement]
            input.value = value
            val init = new dom.InputEventInit {}
            init.bubbles = true
            input.dispatchEvent(new dom.InputEvent("input", init))
            ()
          }
        else ZIO.fail(new RuntimeException(s"not an input: $selector"))
      }

    def innerText(using Trace): IO[Throwable, String] =
      query.map(el => Option(el.textContent).getOrElse(""))

    def selectionStart(using Trace): IO[Throwable, Int] =
      query.flatMap { el =>
        if isInput(el) then ZIO.succeed(el.asInstanceOf[dom.HTMLInputElement].selectionStart)
        else ZIO.fail(new RuntimeException(s"not an input: $selector"))
      }
  end DomLocator

  private def isInput(el: dom.Element): Boolean =
    val tag = el.tagName
    tag.equalsIgnoreCase("INPUT") || tag.equalsIgnoreCase("TEXTAREA")

  def getByTestId(testId: String, root: dom.Element = dom.document.body): DomLocator =
    DomLocator(s"""[data-testid="$testId"]""", root)

  def getByRole(role: String, root: dom.Element = dom.document.body): DomLocator =
    DomLocator(s"""[role="$role"]""", root)

  def css(selector: String, root: dom.Element = dom.document.body): DomLocator =
    DomLocator(selector, root)

  def waitFor(
      selector: String,
      root: dom.Element = dom.document.body,
      timeout: Duration = 5.seconds,
  )(using Trace): IO[Throwable, dom.Element] =
    def attempt: UIO[Option[dom.Element]] =
      ZIO.succeed(Option(root.querySelector(selector)))

    def loop: IO[Throwable, dom.Element] =
      attempt.flatMap {
        case Some(el) => ZIO.succeed(el)
        case None     => ZIO.sleep(50.millis) *> loop
      }

    loop.timeoutFail(new RuntimeException(s"Timeout waiting for $selector"))(timeout)
  end waitFor
end ChekhovDom

export ChekhovDom.*
