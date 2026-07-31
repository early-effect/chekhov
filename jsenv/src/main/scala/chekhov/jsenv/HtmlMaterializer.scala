package chekhov.jsenv

import org.scalajs.jsenv.*

import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.atomic.AtomicInteger

/** Materialize Scala.js inputs + Com bridge into a static directory served over HTTP. */
object HtmlMaterializer:

  private val counter = new AtomicInteger(0)

  final case class Materialized(dir: Path, indexUrlPath: String = "/")

  def materialize(inputs: Seq[Input]): Materialized =
    val dir = Files.createTempDirectory(s"chekhov-jsenv-${counter.incrementAndGet()}-")
    dir.toFile.deleteOnExit()

    Files.writeString(dir.resolve("__chekhov_com.js"), ComSetup.source)

    val scriptTags = inputs.zipWithIndex.map { case (input, i) =>
      input match
        case Input.Script(path) =>
          val name = copyAs(dir, path, s"script-$i.js")
          s"""<script src="/$name"></script>"""
        case Input.ESModule(path) =>
          val name = copyAs(dir, path, s"module-$i.mjs")
          s"""<script type="module" src="/$name"></script>"""
        case other =>
          throw new UnsupportedInputException(Seq(other))
    }

    val html =
      s"""<!DOCTYPE html>
         |<html><head><meta charset="utf-8"/><title>chekhov-jsenv</title></head>
         |<body>
         |<script src="/__chekhov_com.js"></script>
         |${scriptTags.mkString("\n")}
         |</body></html>
         |""".stripMargin
    Files.writeString(dir.resolve("index.html"), html)
    Materialized(dir)
  end materialize

  private def copyAs(dir: Path, src: Path, name: String): String =
    Files.copy(src, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING)
    name
end HtmlMaterializer
