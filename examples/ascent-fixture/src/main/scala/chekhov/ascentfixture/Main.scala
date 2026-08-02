package chekhov.ascentfixture

import ascent.*
import ascent.dsl.*
import ascent.js.AscentApp
import zio.*

object Main extends ZIOAppDefault:

  def run =
    for
      count <- sq(0)
      ui = E.body(
        E.main(
          Aria.role("main"),
          E.h1("Chekhov ascent fixture"),
          E.p(
            "Count: ",
            E.span(A.id("count"), count.map(_.toString)),
          ),
          E.button(
            A.id("inc"),
            A.typ("button"),
            Aria.ariaLabel("Increment"),
            Ev.onClick(_ => count.update(_ + 1)),
            "Increment",
          ),
        )
      )
      _ <- AscentApp.mountBody(ui)
      _ <- ZIO.never
    yield ()
end Main
