package chekhov.ascent

import ascent.ast.UI
import ascent.dom
import ascent.js.AscentApp
import chekhov.dom.ChekhovDom
import org.scalajs.dom as sjsdom
import zio.*

/** Bridge for ascent UI under Chekhov JSEnv suites (`chekhov-dom` + `ascent-js`). */
object ChekhovAscent:

  /** Scoped throwaway root, mount `ui`, run `use`, then unmount via [[ascent.js.Subscriptions.cancelAll]]. */
  def withMounted[R, E, A](
      ui: UI[R]
  )(use: sjsdom.Element => ZIO[R, E, A])(using Trace): ZIO[R, E | Throwable, A] =
    ChekhovDom.withRoot { root =>
      ZIO.scoped {
        for
          _ <- ZIO.acquireRelease {
            AscentApp.mount(ui, root.asInstanceOf[dom.Element])
          }(_.cancelAll)
          a <- use(root)
        yield a
      }
    }
end ChekhovAscent
