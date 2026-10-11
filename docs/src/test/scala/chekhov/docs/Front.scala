package chekhov.docs

import ascent.ast.{Attr, UI}
import ascent.domtypes.AttrValue
import chekhov.Chekhov
import mermoid.{InitDirective, LayoutConfig, Mermaid, RenderConfig, SequenceConfig, SvgNode, SvgRenderer}
import specular.*
import specular.ziotest.DocSpecSuite
import zio.test.*

/** Install coordinate. Always the last published tag. A bare next version is not on Central. */
object DocsVersion:
  /** Last published tag. Update this in the release commit. */
  val published = "0.1.3"

  /** Returns [[published]]. A bare next version, `-ci`, `+`, and `SNAPSHOT` are unpublished. */
  def advertise(raw: String): String =
    if raw.trim == published then published else published

  /** `specular.meta.version` is the raw build version. Absent in unit tests, so those use [[published]]. */
  def version: String =
    sys.props.get("specular.meta.version").map(advertise).getOrElse(published)
end DocsVersion

/** The site front. Specular still emits a summary index; [[BuildSite]] copies this page over it.
  *
  * Not a sidebar item.
  */
object Front extends DocSpecSuite:

  private val version = DocsVersion.version

  private val coordinate: String =
    s"""libraryDependencies ++= Seq(
       |  "rocks.earlyeffect" %% "chekhov-zio-test" % "$version" % Test,
       |  "rocks.earlyeffect" %% "chekhov-driver"   % "$version" % Test,
       |)
       |libraryDependencies += "rocks.earlyeffect" %% "chekhov-core" % "$version"
       |libraryDependencies += "rocks.earlyeffect" %%% "chekhov-dom" % "$version" % Test
       |libraryDependencies += "rocks.earlyeffect" %%% "chekhov-ascent" % "$version" % Test
       |
       |// project/plugins.sbt
       |addSbtPlugin("rocks.earlyeffect" % "sbt-chekhov" % "$version")""".stripMargin

  /** Test fiber, Playwright, the page, the assertion. Names are the ones the driver uses. */
  private val exchange =
    Mermaid("""sequenceDiagram
              |    participant Test as Test fiber
              |    participant Playwright
              |    participant Page
              |    Test->>Playwright: Chekhov.page
              |    Playwright->>Page: click
              |    Page-->>Playwright: frame
              |    Playwright-->>Test: innerText
              |    Test->>Test: assertTrue
              |""".stripMargin)

  /** Phone column is about 390px. Padding and actor boxes stay inside that. */
  private val diagramConfig =
    RenderConfig(
      layout = LayoutConfig(padding = 8, fontSize = 12, edgeLabelFontSize = 11, lineHeight = 14),
      sequence = SequenceConfig(
        actorMinWidth = 32,
        actorPadH = 4,
        actorHeight = 28,
        columnGap = 8,
        rowPitch = 36,
        headerGap = 8,
        footerGap = 8,
      ),
    )

  private val paintedConfig = InitDirective.apply(exchange.source, diagramConfig)

  private val svg: String = SvgRenderer.render(exchange.diagram, paintedConfig)

  private val diagram: UI[Any] =
    SvgEmbed.root(SvgRenderer.renderTree(exchange.diagram, paintedConfig))

  /** Phone content width, after the theme's side padding. A wider scene shrinks type below reading size. */
  private def fits(text: String): Boolean =
    svgWidth(text).exists(_ <= 360)

  private def svgWidth(text: String): Option[Double] =
    val attrs = List(
      """width="([0-9.]+)"""".r
    )
    attrs.flatMap(_.findAllMatchIn(text)).flatMap(m => m.group(1).toDoubleOption).maxOption

  def doc = page("A test that can see a control can fire it.")(
    illustration(diagram).assert { ui =>
      val text = ui.toString
      assertTrue(
        text.contains("Test fiber"),
        text.contains("Playwright"),
        text.contains("Page"),
        text.contains("Chekhov.page"),
        text.contains("click"),
        text.contains("frame"),
        text.contains("innerText"),
        text.contains("assertTrue"),
        text.contains("width"),
      )
    },
    section("Install")(
      md"""
The coordinate is the release this site advertises.

```scala
${coordinate}
```
"""
    ),
    section("Chekhov.page")(
      md"""
`Chekhov.page` is the page the test fiber gets from the environment. The method takes a context `Trace` and no ordinary parameter, so `_.page(using _)` does not pin `Trace`. This cite is `Chekhov.page(using _)`, the symbol `Chekhov.page`.
""",
      cite(Chekhov.page(using _)),
    ),
  )

  override def spec =
    suite("A test that can see a control can fire it.")(
      super.spec,
      test("install coordinate is the published release") {
        val shown = DocsVersion.version
        assertTrue(
          shown == DocsVersion.published,
          coordinate.contains(shown),
          coordinate.contains("rocks.earlyeffect"),
          coordinate.contains("%% \"chekhov-zio-test\""),
          coordinate.contains("%% \"chekhov-driver\""),
          coordinate.contains("%% \"chekhov-core\""),
          coordinate.contains("%%% \"chekhov-dom\""),
          coordinate.contains("%%% \"chekhov-ascent\""),
          coordinate.contains("% \"sbt-chekhov\""),
          !shown.contains("-ci"),
          !shown.contains("+"),
          !shown.contains("SNAPSHOT"),
          !coordinate.contains("-ci"),
          !coordinate.contains("SNAPSHOT"),
          !coordinate.contains("<version>"),
          !coordinate.contains("0.1.4"),
          DocsVersion.advertise("0.1.4") == DocsVersion.published,
          DocsVersion.advertise("0.1.4-ci") == DocsVersion.published,
          DocsVersion.advertise("0.1.3+1-abc") == DocsVersion.published,
          DocsVersion.advertise(DocsVersion.published) == DocsVersion.published,
        )
      },
      test("sequence diagram fits a phone column") {
        val width = svgWidth(svg)
        assertTrue(
          fits(svg),
          svg.contains("Test fiber"),
          svg.contains("Playwright"),
          svg.contains("Page"),
          svg.contains("Chekhov.page"),
          svg.contains("click"),
          svg.contains("frame"),
          svg.contains("innerText"),
          svg.contains("assertTrue"),
        ).label(s"svg width $width")
      },
    )
end Front

/** Root SVG scaled to the content column. Nested icons keep the sizes mermoid painted. */
private object SvgEmbed:

  def root(node: SvgNode): UI[Any] = node match
    case SvgNode.Element("svg", attrs, children) =>
      element("svg", fit(attrs), children)
    case other =>
      paint(other)

  private def paint(node: SvgNode): UI[Any] = node match
    case SvgNode.Text(value) =>
      UI.Text(value)
    case SvgNode.Raw(content) =>
      UI.Text(content)
    case SvgNode.Element(tag, attrs, children) =>
      element(tag, attrs, children)

  private def element(tag: String, attrs: List[(String, String)], children: List[SvgNode]): UI[Any] =
    UI.Element(
      tag,
      attrs.map((name, value) => Attr.StaticAttr(name, AttrValue.Str(value))).toVector,
      children.map(paint).toVector,
    )

  /** Drop the painted pixel box. `viewBox` keeps the aspect; the column width does the rest. */
  private def fit(attrs: List[(String, String)]): List[(String, String)] =
    val kept = attrs.filter((name, _) => name != "width" && name != "height" && name != "style")
    kept ++ List(
      "width" -> "100%",
      "style" -> "height:auto;max-width:100%;display:block",
    )
end SvgEmbed
