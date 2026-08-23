package chekhov.dom

import org.scalajs.dom
import zio.*
import zio.test.*

/** Live DOM helpers under ChekhovJSEnv. */
object ChekhovDomSpec extends ZIOSpecDefault:

  override def aspects =
    Chunk(
      TestAspect.withLiveClock,
      TestAspect.timeout(10.seconds),
    )

  def spec =
    suite("ChekhovDom")(
      test("withRoot mounts and removes a throwaway parent") {
        for
          ref    <- Ref.make[Option[dom.Element]](None)
          during <- withRoot { root =>
            ref.set(Some(root)).as(root.getAttribute("data-chekhov-root") == "true")
          }
          detached <- ref.get.map(_.exists(el => !el.isConnected))
        yield assertTrue(during, detached)
      },
      test("getByTestId fill and click update the DOM") {
        withRoot { root =>
          for
            _ <- ZIO.succeed {
              val input = dom.document.createElement("input").asInstanceOf[dom.HTMLInputElement]
              input.setAttribute("data-testid", "todo")
              val btn = dom.document.createElement("button").asInstanceOf[dom.HTMLButtonElement]
              btn.setAttribute("data-testid", "add")
              val out = dom.document.createElement("span")
              out.setAttribute("data-testid", "out")
              btn.addEventListener(
                "click",
                (_: dom.Event) => out.textContent = input.value,
              )
              root.append(input, btn, out)
            }
            _ <- getByTestId("todo", root).fill("milk")
            _ <- getByTestId("add", root).click
            t <- getByTestId("out", root).innerText
          yield assertTrue(t == "milk")
        }
      },
      test("getByRole finds an element by role attribute") {
        withRoot { root =>
          for
            _ <- ZIO.succeed {
              root.innerHTML = """<button role="button">Go</button>"""
            }
            t <- getByRole("button", root).innerText
          yield assertTrue(t.contains("Go"))
        }
      },
    )
end ChekhovDomSpec
