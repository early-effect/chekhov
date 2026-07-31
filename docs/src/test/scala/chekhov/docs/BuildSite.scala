package chekhov.docs

import earlyeffect.docs.EarlyEffectTheme
import specular.site.*
import zio.*

import java.nio.file.Path

/** Docs-as-tests site builder (Test classpath; `docs/specularSite`). */
object BuildSite extends DocsSite:

  def pages = Vector(GettingStarted.doc)

  // Meta comes from published `core` (`chekhov-core`); hub card / chrome use the toolkit name.
  override def meta: ProjectMeta =
    super.meta.copy(name = "chekhov", title = Some("chekhov"))

  override def site: SiteModel =
    val m       = meta
    val branded = EarlyEffectTheme.brand(super.site)
    branded.copy(
      summaryMarkdown = Some(
        """**chekhov** is a ZIO-first Playwright client and Scala.js browser test toolkit.
Protocol YAML becomes a typed Scala AST; a Node driver speaks the channel; suites compose
browser, context, and page as layers (plus optional Vite/static serve).

This is a **multi-module toolkit**: pick the artifacts you need (`chekhov-core`,
`chekhov-driver`, `chekhov-zio-test`, `chekhov-dom`, `chekhov-jsenv`, `sbt-chekhov`).
"""
      ),
      installSnippets = Vector(
        CodeSnippet(
          "Suite stack (JVM)",
          s"""libraryDependencies ++= Seq(
  "${m.organization}" %% "chekhov-zio-test" % "${m.version}",
  "${m.organization}" %% "chekhov-driver"   % "${m.version}",
)""",
        ),
        CodeSnippet(
          "Core algebras only",
          s"""libraryDependencies += "${m.organization}" %% "chekhov-core" % "${m.version}"""",
        ),
        CodeSnippet(
          "Scala.js DOM helpers",
          s"""libraryDependencies += "${m.organization}" %%% "chekhov-dom" % "${m.version}"""",
        ),
      ),
      brand = Some(
        Brand(
          name = m.title.getOrElse("chekhov"),
          links = Vector(EarlyEffectTheme.github("https://github.com/early-effect/chekhov")),
        )
      ),
    )
  end site

  override def layers: ZLayer[Any, Nothing, SiteBuilder] =
    EarlyEffectTheme.layers

  override def afterBuild(out: Path, result: SiteOutput): Task[Unit] =
    val _ = result
    EarlyEffectTheme.writeLogo(out)
end BuildSite
