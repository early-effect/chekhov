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
      test("concurrent withRoot scopes are isolated from each other") {
        // Rendezvous: each scope signals readiness and waits for the other, so the cross checks
        // below run while both iframes are alive in the shared parent document.
        def scope(
            tag: String,
            other: String,
            ref: Ref[Option[dom.Element]],
            ready: Promise[Nothing, Unit],
            otherReady: Promise[Nothing, Unit],
        ) =
          withRoot { root =>
            for
              _ <- ZIO.succeed {
                val el = root.ownerDocument.createElement("span")
                el.setAttribute("data-testid", tag)
                root.appendChild(el)
              }
              _  <- ref.set(Some(root))
              _  <- ready.succeed(())
              _  <- otherReady.await
              ok <- ZIO.succeed(
                root.getAttribute("data-chekhov-root") == "true" &&
                  !root.ownerDocument.eq(dom.document) &&
                  Option(root.querySelector(s"""[data-testid="$other"]""")).isEmpty &&
                  Option(dom.document.querySelector(s"""[data-testid="$tag"]""")).isEmpty
              )
            yield ok
          }

        for
          ra         <- Ref.make[Option[dom.Element]](None)
          rb         <- Ref.make[Option[dom.Element]](None)
          inA        <- Promise.make[Nothing, Unit]
          inB        <- Promise.make[Nothing, Unit]
          (okA, okB) <-
            scope("scope-a", "scope-b", ra, inA, inB).zipPar(scope("scope-b", "scope-a", rb, inB, inA))
          a <- ra.get
          b <- rb.get
        yield assertTrue(
          okA,
          okB,
          (a, b) match
            case (Some(x), Some(y)) => !x.ownerDocument.eq(y.ownerDocument)
            case _                  => false
          ,
          a.forall(!_.isConnected),
          b.forall(!_.isConnected),
        )
        end for
      },
    )
end ChekhovDomSpec
