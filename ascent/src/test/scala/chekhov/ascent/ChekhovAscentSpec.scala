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
    )

  private def waitForText(root: dom.Element, testIdName: String, expected: String)(using Trace): IO[Throwable, String] =
    def loop: IO[Throwable, String] =
      getByTestId(testIdName, root).innerText.flatMap { t =>
        if t == expected then ZIO.succeed(t)
        else ZIO.sleep(20.millis) *> loop
      }
    loop.timeoutFail(new RuntimeException(s"timed out waiting for $testIdName == $expected"))(5.seconds)
end ChekhovAscentSpec
