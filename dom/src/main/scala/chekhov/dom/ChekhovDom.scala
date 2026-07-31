package chekhov.dom

import org.scalajs.dom
import zio.*

/** In-page DOM helpers for Chekhov JSEnv suites (scalajs-dom, no ascent). */
object ChekhovDom:

  /** Scoped throwaway parent under `document.body`. */
  def withRoot[R, E, A](use: dom.Element => ZIO[R, E, A])(using Trace): ZIO[R, E | Throwable, A] =
    ZIO.scoped {
      for
        root <- ZIO.acquireRelease {
          ZIO.succeed {
            val el = dom.document.createElement("div")
            el.setAttribute("data-chekhov-root", "true")
            dom.document.body.appendChild(el)
            el
          }
        } { el =>
          ZIO.succeed {
            Option(el.parentNode).foreach(_.removeChild(el))
          }.unit
        }
        a <- use(root)
      yield a
    }

  final case class DomLocator(selector: String, root: dom.Element = dom.document.body):
    def query(using Trace): IO[Throwable, dom.Element] =
      waitFor(selector, root)

    def click(using Trace): IO[Throwable, Unit] =
      query.flatMap {
        case el: dom.HTMLElement => ZIO.succeed(el.click())
        case _                   => ZIO.fail(new RuntimeException(s"not clickable: $selector"))
      }

    def fill(value: String)(using Trace): IO[Throwable, Unit] =
      query.flatMap {
        case input: dom.HTMLInputElement =>
          ZIO.succeed {
            input.value = value
            val init = new dom.InputEventInit {}
            init.bubbles = true
            input.dispatchEvent(new dom.InputEvent("input", init))
            ()
          }
        case _ =>
          ZIO.fail(new RuntimeException(s"not an input: $selector"))
      }

    def innerText(using Trace): IO[Throwable, String] =
      query.map(el => Option(el.textContent).getOrElse(""))

    def selectionStart(using Trace): IO[Throwable, Int] =
      query.flatMap {
        case input: dom.HTMLInputElement => ZIO.succeed(input.selectionStart)
        case _                           => ZIO.fail(new RuntimeException(s"not an input: $selector"))
      }
  end DomLocator

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
