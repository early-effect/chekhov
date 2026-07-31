package chekhov.jsenv

import org.scalajs.jsenv.*
import zio.test.*

import java.nio.file.Files

object HtmlMaterializerSpec extends ZIOSpecDefault:

  def spec =
    suite("HtmlMaterializer")(
      test("writes index + com bridge + script") {
        val js = Files.createTempFile("mat-", ".js")
        Files.writeString(js, "console.log(1)")
        val m    = HtmlMaterializer.materialize(Seq(Input.Script(js)))
        val html = Files.readString(m.dir.resolve("index.html"))
        assertTrue(
          Files.isRegularFile(m.dir.resolve("__chekhov_com.js")),
          Files.isRegularFile(m.dir.resolve("script-0.js")),
          html.contains("__chekhov_com.js"),
          html.contains("script-0.js"),
        )
      }
    )
end HtmlMaterializerSpec
