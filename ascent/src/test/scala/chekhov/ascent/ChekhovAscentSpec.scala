package chekhov.ascent

import ascent.*
import ascent.ast.Attr
import ascent.domtypes.AttrValue
import ascent.dsl.*
import chekhov.ascent.ChekhovAscent.withMounted
import chekhov.dom.*
import org.scalajs.dom
import zio.*
import zio.test.*

/** Live `withMounted` dogfood under ChekhovJSEnv. */
object ChekhovAscentSpec extends ZIOSpecDefault:

  override def aspects =
    Chunk(
      TestAspect.withLiveClock,
      TestAspect.timeout(15.seconds),
    )

  private def testId(id: String): Attr[Any] =
    Attr.StaticAttr("data-testid", AttrValue.Str(id))

  def spec =
    suite("ChekhovAscent")(
      test("withMounted mounts a counter and supports getByTestId click") {
        for
          count <- sq(0)
          ui = E.div(
            testId("root"),
            E.span(testId("count"), count.map(_.toString)),
            E.button(
              testId("inc"),
              A.typ("button"),
              Ev.onClick(_ => count.update(_ + 1)),
              "Increment",
            ),
          )
          result <- withMounted(ui) { root =>
            for
              before <- getByTestId("count", root).innerText
              _      <- getByTestId("inc", root).click
              after  <- waitForText(root, "count", "1")
            yield assertTrue(before == "0", after == "1")
          }
        yield result
      },
      test("withMounted removes the chekhov root on exit") {
        for
          ref    <- Ref.make[Option[dom.Element]](None)
          during <- withMounted(E.div(testId("x"), "hi")) { root =>
            ref.set(Some(root)).as(root.getAttribute("data-chekhov-root") == "true")
          }
          detached <- ref.get.map(_.exists(el => !el.isConnected))
        yield assertTrue(during, detached)
      },
      test("concurrent withMounted scopes are isolated from each other") {
        // Rendezvous: each scope signals readiness and waits for the other, so the cross checks
        // below run while both iframes are alive in the shared parent document.
        def scope(
            tag: String,
            other: String,
            ref: Ref[Option[dom.Element]],
            ready: Promise[Nothing, Unit],
            otherReady: Promise[Nothing, Unit],
        ) =
          withMounted(E.div(testId(tag), tag)) { root =>
            for
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
            scope("iso-a", "iso-b", ra, inA, inB).zipPar(scope("iso-b", "iso-a", rb, inB, inA))
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

  private def waitForText(root: dom.Element, testIdName: String, expected: String)(using Trace): IO[Throwable, String] =
    def loop: IO[Throwable, String] =
      getByTestId(testIdName, root).innerText.flatMap { t =>
        if t == expected then ZIO.succeed(t)
        else ZIO.sleep(20.millis) *> loop
      }
    loop.timeoutFail(new RuntimeException(s"timed out waiting for $testIdName == $expected"))(5.seconds)
end ChekhovAscentSpec
