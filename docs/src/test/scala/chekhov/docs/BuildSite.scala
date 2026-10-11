package chekhov.docs

import earlyeffect.docs.EarlyEffectTheme
import specular.*
import specular.site.*
import zio.*

import java.nio.file.{Files, Path, StandardCopyOption}

/** Docs-as-tests site builder (Test classpath; `docs/specularSite`). */
object BuildSite extends DocsSite:

  /** Not a nav item. The sidebar is the table of contents; this page is the front. */
  private val frontPage: DocPage = Front.doc

  private val guidePages: Vector[DocPage] = Vector(
    Quickstart.doc,
    BrowsersAndE2E.doc,
    ServingTheApp.doc,
    JsEnvAndDom.doc,
    AlgebrasTour.doc,
    Contributing.doc,
  )

  def pages: Vector[DocPage] = frontPage +: guidePages

  override def site(settings: DocsSettings): SiteModel =
    val meta = settings.meta.copy(name = "chekhov", title = Some("chekhov"))
    EarlyEffectTheme
      .brand(super.site(settings))
      .copy(
        title = meta.displayTitle,
        meta = Some(meta),
        nav = Some(NavModel.flat(guidePages)),
        pages = pages,
        summaryMarkdown = None,
        installSnippets = Vector.empty,
        brand = Some(
          Brand(
            name = "chekhov",
            links = Vector(EarlyEffectTheme.github("https://github.com/early-effect/chekhov")),
          )
        ),
      )
  end site

  override def layers: ZLayer[Any, Nothing, SiteBuilder] =
    EarlyEffectTheme.layers

  override def afterBuild(out: Path, result: SiteOutput): IO[SiteError, Unit] =
    val _ = result
    // Specular always writes index.html as a summary plus a second copy of the nav.
    // The front DocPage is the index. Copy it over that file. Asset paths stay site-relative.
    val front   = out.resolve(s"${frontPage.slug}.html")
    val index   = out.resolve("index.html")
    val promote =
      ZIO.attemptBlockingIO(Files.copy(front, index, StandardCopyOption.REPLACE_EXISTING)).mapError { err =>
        SiteError.WriteFailed(index, err)
      }
    promote *> EarlyEffectTheme.writeLogo(out)
  end afterBuild
end BuildSite
