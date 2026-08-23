package chekhov.driver

import chekhov.*
import chekhov.protocol.{ChannelConnection, ChannelTransport, PinnedPlaywright}
import chekhov.protocol.generated.Commands
import zio.*
import zio.json.*
import zio.json.ast.Json

/** Playwright channel-backed implementations of Chekhov algebras (saferis-style layers). */
object PlaywrightDriver:

  final case class BrowserTypeService(
      conn: ChannelConnection,
      browser: ChekhovBrowser,
      typeGuid: String,
  ) extends BrowserType:
    val name: String = browser.channelName

    def launch(using Trace): ZIO[Scope & ChekhovConfig, ChekhovError, Browser] =
      for
        config <- ZIO.service[ChekhovConfig]
        // A system browser (executablePath / channel) needs no downloaded revision.
        systemBrowser = config.executablePath.isDefined || config.channel.isDefined
        _ <- ZIO
          .fromEither(PinnedPlaywright.requireBrowser(config.browser))
          .mapError(p => ChekhovError.Driver(p.message))
          .unless(systemBrowser)
        // GitHub Actions / containers: Chromium needs the sandbox disabled.
        ci = sys.env.get("CI").contains("true") || sys.env.get("GITHUB_ACTIONS").contains("true")
        result <- conn.send(
          typeGuid,
          "launch",
          Commands.BrowserTypeLaunch(
            headless = Some(config.headless),
            chromiumSandbox = if ci then Some(false) else None,
            executablePath = config.executablePath,
            channel = config.channel,
            args =
              if config.launchArgs.nonEmpty then Some(Json.Arr(config.launchArgs.map(Json.Str(_))*))
              else None,
          ),
        )
        guid <- guidOf(result, "browser")
        b = BrowserLive(conn, guid)
        _ <- ZIO.addFinalizer(b.close)
      yield b
  end BrowserTypeService

  final case class BrowserLive(conn: ChannelConnection, guid: String) extends Browser:
    def newContext(using Trace): ZIO[Scope & ChekhovConfig & ArtifactSession, ChekhovError, BrowserContext] =
      for
        config   <- ZIO.service[ChekhovConfig]
        session  <- ZIO.service[ArtifactSession]
        videoDir <- videoDirFor(config)
        recordVideo = videoDir.map { dir =>
          Json.Obj("dir" -> Json.Str(dir.toAbsolutePath.toString))
        }
        result  <- conn.send(guid, "newContext", Commands.BrowserNewContext(recordVideo = recordVideo))
        ctxGuid <- guidOf(result, "context")
        init    <- conn.awaitInitializer(ctxGuid)
        tracing <- tracingGuidOf(init)
        ctx = BrowserContextLive(
          conn = conn,
          guid = ctxGuid,
          tracingGuid = tracing,
          traceCapture = config.traceCapture,
          videoCapture = config.videoCapture,
          videoDir = videoDir,
          artifactsDir = config.artifactsDir,
          session = Some(session),
        )
        _ <- ZIO.when(config.traceCapture != ArtifactCapture.Off)(ctx.startTracing)
        _ <- ZIO.addFinalizer(ctx.finalizeCapture.ignore *> ctx.close)
      yield ctx

    def close(using Trace): UIO[Unit] =
      conn.sendClose(guid, "close")
  end BrowserLive

  final case class BrowserContextLive(
      conn: ChannelConnection,
      guid: String,
      tracingGuid: String,
      traceCapture: ArtifactCapture,
      videoCapture: ArtifactCapture,
      videoDir: Option[java.nio.file.Path],
      artifactsDir: java.nio.file.Path,
      session: Option[ArtifactSession],
  ) extends BrowserContext:
    def newPage(using Trace): ZIO[Scope, ChekhovError, Page] =
      for
        result   <- conn.send(guid, "newPage", Commands.BrowserContextNewPage())
        pageGuid <- guidOf(result, "page")
        // Navigation / input channel methods live on Frame (FrameGotoParams), not Page.
        init      <- conn.awaitInitializer(pageGuid)
        frameGuid <- mainFrameGuid(init)
        page = PageLive(conn, pageGuid, frameGuid)
        _ <- ZIO.addFinalizer(page.close)
      yield page

    def storageState(indexedDB: Boolean = false)(using Trace): IO[ChekhovError, String] =
      conn
        .send(
          guid,
          "storageState",
          Commands.BrowserContextStorageState(indexedDB = Some(indexedDB).filter(identity)),
        )
        .map(_.toJson)

    def setStorageState(stateJson: String)(using Trace): IO[ChekhovError, Unit] =
      for
        state <- ZIO
          .fromEither(stateJson.fromJson[Json])
          .mapError(e => ChekhovError.Protocol(s"Invalid storage state JSON: $e"))
        _ <- conn.send(guid, "setStorageState", Commands.BrowserContextSetStorageState(storageState = Some(state)))
      yield ()

    def cookies(urls: Chunk[String] = Chunk.empty)(using Trace): IO[ChekhovError, Chunk[Cookie]] =
      val urlsJson = Json.Arr(urls.map(Json.Str(_))*)
      conn
        .send(guid, "cookies", Commands.BrowserContextCookies(urls = urlsJson))
        .flatMap(decodeCookies)

    def addCookies(cookies: Chunk[CookieInit])(using Trace): IO[ChekhovError, Unit] =
      val payload = Json.Arr(cookies.map(cookieInitJson)*)
      conn.send(guid, "addCookies", Commands.BrowserContextAddCookies(cookies = payload)).unit

    def clearCookies(using Trace): IO[ChekhovError, Unit] =
      conn.send(guid, "clearCookies", Commands.BrowserContextClearCookies()).unit

    def startTracing(using Trace): IO[ChekhovError, Unit] =
      for
        _ <- conn.send(
          tracingGuid,
          "tracingStart",
          Commands.TracingStart(screenshots = Some(true), snapshots = Some(true)),
        )
        _ <- conn.send(tracingGuid, "tracingStartChunk", Commands.TracingStartChunk())
      yield ()

    def stopTracing(how: TraceStop)(using Trace): IO[ChekhovError, Unit] =
      how.match
        case TraceStop.Discard =>
          conn.send(tracingGuid, "tracingStopChunk", Commands.TracingStopChunk(mode = "discard")).unit *>
            conn.send(tracingGuid, "tracingStop", Commands.TracingStop()).unit
        case TraceStop.Archive(path) =>
          for
            result <- conn.send(tracingGuid, "tracingStopChunk", Commands.TracingStopChunk(mode = "archive"))
            _      <- saveArtifact(conn, result, path)
            _      <- conn.send(tracingGuid, "tracingStop", Commands.TracingStop()).unit
          yield ()

    /** Finish opt-in capture for this context (called from the scope finalizer before close). */
    def finalizeCapture(using Trace): IO[ChekhovError, Unit] =
      for
        keepTrace <- shouldKeepCapture(traceCapture, session)
        _         <-
          if traceCapture == ArtifactCapture.Off then ZIO.unit
          else if keepTrace then
            val path = artifactsDir
              .resolve("traces")
              .resolve(s"${java.lang.System.currentTimeMillis()}-trace.zip")
            stopTracing(TraceStop.Archive(path))
          else stopTracing(TraceStop.Discard)
        keepVideo <- shouldKeepCapture(videoCapture, session)
        _         <- finalizeVideoDir(videoDir, videoCapture, keepVideo, artifactsDir)
      yield ()

    def close(using Trace): UIO[Unit] =
      conn.sendClose(guid, "close")
  end BrowserContextLive

  /** Page-facing API; DOM commands go to `frameGuid` (main frame). */
  final case class PageLive(conn: ChannelConnection, guid: String, frameGuid: String) extends Page:
    def goto(url: String)(using Trace): IO[ChekhovError, Unit] =
      conn.send(frameGuid, "goto", Commands.PageGoto(url = url)).unit

    def click(selector: String)(using Trace): IO[ChekhovError, Unit] =
      conn.send(frameGuid, "click", Commands.PageClick(selector = selector)).unit

    def fill(selector: String, value: String)(using Trace): IO[ChekhovError, Unit] =
      conn.send(frameGuid, "fill", Commands.PageFill(selector = selector, value = value)).unit

    def press(selector: String, key: String)(using Trace): IO[ChekhovError, Unit] =
      conn.send(frameGuid, "press", Commands.PagePress(selector = selector, key = key)).unit

    def innerText(selector: String)(using Trace): IO[ChekhovError, String] =
      conn.send(frameGuid, "innerText", Commands.PageInnerText(selector = selector)).flatMap(stringResult)

    def textContent(selector: String)(using Trace): IO[ChekhovError, String] =
      conn.send(frameGuid, "textContent", Commands.PageTextContent(selector = selector)).flatMap(optionalString)

    def title(using Trace): IO[ChekhovError, String] =
      conn.send(frameGuid, "title", Commands.PageTitle()).flatMap(stringResult)

    def evaluate(expression: String, isFunction: Boolean = false)(using Trace): IO[ChekhovError, String] =
      val arg = Json.Obj(
        "value"   -> Json.Obj("v" -> Json.Str("undefined")),
        "handles" -> Json.Arr(),
      )
      conn
        .send(
          frameGuid,
          "evaluateExpression",
          Commands.FrameEvaluateExpression(
            expression = expression,
            isFunction = Some(isFunction),
            arg = arg,
          ),
        )
        .map { result =>
          result.asObject.flatMap(_.get("value")).getOrElse(result).toJson
        }
    end evaluate

    def webStorageItems(kind: WebStorageKind)(using Trace): IO[ChekhovError, Chunk[StorageItem]] =
      conn
        .send(guid, "webStorageItems", Commands.PageWebStorageItems(kind = kind.protocolLiteral))
        .flatMap(decodeStorageItems)

    def webStorageGetItem(kind: WebStorageKind, name: String)(using Trace): IO[ChekhovError, Option[String]] =
      conn
        .send(
          guid,
          "webStorageGetItem",
          Commands.PageWebStorageGetItem(kind = kind.protocolLiteral, name = name),
        )
        .map { json =>
          json.asObject.flatMap(_.get("value")).flatMap {
            case Json.Null => None
            case v         => v.asString
          }
        }

    def webStorageSetItem(kind: WebStorageKind, name: String, value: String)(using Trace): IO[ChekhovError, Unit] =
      conn
        .send(
          guid,
          "webStorageSetItem",
          Commands.PageWebStorageSetItem(kind = kind.protocolLiteral, name = name, value = value),
        )
        .unit

    def webStorageRemoveItem(kind: WebStorageKind, name: String)(using Trace): IO[ChekhovError, Unit] =
      conn
        .send(
          guid,
          "webStorageRemoveItem",
          Commands.PageWebStorageRemoveItem(kind = kind.protocolLiteral, name = name),
        )
        .unit

    def webStorageClear(kind: WebStorageKind)(using Trace): IO[ChekhovError, Unit] =
      conn.send(guid, "webStorageClear", Commands.PageWebStorageClear(kind = kind.protocolLiteral)).unit

    def screenshot(path: java.nio.file.Path)(using Trace): IO[ChekhovError, java.nio.file.Path] =
      for
        result <- conn.send(guid, "screenshot", Commands.PageScreenshot(`type` = Some("png")))
        b64    <- ZIO
          .fromOption {
            result.asObject.flatMap(_.get("binary")).flatMap(_.asString)
          }
          .orElseFail(ChekhovError.Protocol(s"screenshot missing binary: $result"))
        bytes <- ZIO
          .attempt(java.util.Base64.getDecoder.decode(b64))
          .mapError(e => ChekhovError.Protocol(s"screenshot base64 decode failed: $e", Some(e)))
        _ <- ZIO
          .attempt {
            Option(path.getParent).foreach(java.nio.file.Files.createDirectories(_))
            java.nio.file.Files.write(path, bytes)
          }
          .mapError(e => ChekhovError.Driver(s"Failed to write screenshot $path: $e", Some(e)))
      yield path

    def locator(selector: String): Locator =
      LocatorLive(conn, frameGuid, selector)

    def getByPlaceholder(text: String): Locator =
      val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
      locator(s"""css=[placeholder="$escaped"]""")

    def getByRole(role: Role, name: Option[String] = None): Locator =
      val base = s"internal:role=${role.toString.toLowerCase}"
      name.match
        case Some(n) =>
          val escaped = n.replace("\\", "\\\\").replace("\"", "\\\"")
          locator(s"""$base[name="$escaped"]""")
        case None => locator(base)

    def getByTestId(testId: String): Locator =
      locator(s"data-testid=$testId")

    val keyboard: Keyboard = new Keyboard:
      def press(key: String)(using Trace): IO[ChekhovError, Unit] =
        conn.send(guid, "keyboardPress", Commands.PageKeyboardPress(key = key)).unit

    def close(using Trace): UIO[Unit] =
      conn.sendClose(guid, "close")
  end PageLive

  final case class LocatorLive(conn: ChannelConnection, frameGuid: String, selector: String) extends Locator:
    def click(using Trace): IO[ChekhovError, Unit] =
      conn.send(frameGuid, "click", Commands.PageClick(selector = selector)).unit

    def fill(value: String)(using Trace): IO[ChekhovError, Unit] =
      conn.send(frameGuid, "fill", Commands.PageFill(selector = selector, value = value)).unit

    def press(key: String)(using Trace): IO[ChekhovError, Unit] =
      conn.send(frameGuid, "press", Commands.PagePress(selector = selector, key = key)).unit

    def innerText(using Trace): IO[ChekhovError, String] =
      conn.send(frameGuid, "innerText", Commands.PageInnerText(selector = selector)).flatMap(stringResult)

    def textContent(using Trace): IO[ChekhovError, String] =
      conn.send(frameGuid, "textContent", Commands.PageTextContent(selector = selector)).flatMap(optionalString)
  end LocatorLive

  private def mainFrameGuid(pageInitializer: Json): IO[ChekhovError, String] =
    ZIO
      .fromOption {
        pageInitializer.asObject
          .flatMap(_.get("mainFrame"))
          .flatMap(_.asObject)
          .flatMap(_.get("guid"))
          .flatMap(_.asString)
      }
      .orElseFail(ChekhovError.Protocol(s"Page initializer missing mainFrame.guid: $pageInitializer"))

  private def tracingGuidOf(contextInitializer: Json): IO[ChekhovError, String] =
    ZIO
      .fromOption {
        contextInitializer.asObject
          .flatMap(_.get("tracing"))
          .flatMap(_.asObject)
          .flatMap(_.get("guid"))
          .flatMap(_.asString)
      }
      .orElseFail(ChekhovError.Protocol(s"BrowserContext initializer missing tracing.guid: $contextInitializer"))

  private def videoDirFor(config: ChekhovConfig): UIO[Option[java.nio.file.Path]] =
    config.videoCapture match
      case ArtifactCapture.Off    => ZIO.succeed(None)
      case ArtifactCapture.Always =>
        val dir = config.artifactsDir.resolve("videos")
        ZIO.attempt(java.nio.file.Files.createDirectories(dir)).orDie.as(Some(dir))
      case ArtifactCapture.OnFailure =>
        val dir = config.artifactsDir.resolve("videos").resolve(s".staging-${java.util.UUID.randomUUID()}")
        ZIO.attempt(java.nio.file.Files.createDirectories(dir)).orDie.as(Some(dir))

  private def shouldKeepCapture(mode: ArtifactCapture, session: Option[ArtifactSession])(using Trace): UIO[Boolean] =
    mode match
      case ArtifactCapture.Off       => ZIO.succeed(false)
      case ArtifactCapture.Always    => ZIO.succeed(true)
      case ArtifactCapture.OnFailure =>
        session.match
          case Some(s) => s.shouldKeep(mode)
          case None    => ZIO.succeed(false)

  private def finalizeVideoDir(
      videoDir: Option[java.nio.file.Path],
      mode: ArtifactCapture,
      keep: Boolean,
      artifactsDir: java.nio.file.Path,
  ): IO[ChekhovError, Unit] =
    videoDir.match
      case None      => ZIO.unit
      case Some(dir) =>
        mode.match
          case ArtifactCapture.Off    => ZIO.unit
          case ArtifactCapture.Always =>
            ZIO.unit // Playwright already wrote under artifactsDir/videos
          case ArtifactCapture.OnFailure =>
            if keep then
              val dest = artifactsDir.resolve("videos")
              ZIO
                .attempt {
                  java.nio.file.Files.createDirectories(dest)
                  if java.nio.file.Files.isDirectory(dir) then
                    val stream = java.nio.file.Files.list(dir)
                    try
                      stream.forEach { src =>
                        val name = src.getFileName.toString
                        java.nio.file.Files.move(
                          src,
                          dest.resolve(s"${java.lang.System.currentTimeMillis()}-$name"),
                          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        )
                      }
                    finally stream.close()
                    end try
                  end if
                  deleteRecursively(dir)
                }
                .mapError(e => ChekhovError.Driver(s"Failed to promote video dir $dir: $e", Some(e)))
                .unit
            else
              ZIO
                .attempt(deleteRecursively(dir))
                .mapError(e => ChekhovError.Driver(s"Failed to discard video dir $dir: $e", Some(e)))
                .unit

  private def deleteRecursively(path: java.nio.file.Path): Unit =
    if java.nio.file.Files.isDirectory(path) then
      val stream = java.nio.file.Files.list(path)
      try stream.forEach(deleteRecursively)
      finally stream.close()
    java.nio.file.Files.deleteIfExists(path)
    ()

  private def saveArtifact(
      conn: ChannelConnection,
      result: Json,
      path: java.nio.file.Path,
  )(using Trace): IO[ChekhovError, Unit] =
    for
      artifactGuid <- ZIO
        .fromOption {
          result.asObject
            .flatMap(_.get("artifact"))
            .flatMap(_.asObject)
            .flatMap(_.get("guid"))
            .flatMap(_.asString)
        }
        .orElseFail(ChekhovError.Protocol(s"tracingStopChunk missing artifact.guid: $result"))
      _ <- ZIO
        .attempt(Option(path.getParent).foreach(java.nio.file.Files.createDirectories(_)))
        .mapError(e => ChekhovError.Driver(s"Failed to create parent for $path: $e", Some(e)))
      _ <- conn.send(artifactGuid, "saveAs", Commands.ArtifactSaveAs(path = path.toAbsolutePath.toString)).unit
    yield ()

  private def guidOf(result: Json, field: String): IO[ChekhovError, String] =
    ZIO
      .fromOption {
        val obj = result.asObject
        obj
          .flatMap(_.get(field))
          .flatMap(_.asObject)
          .flatMap(_.get("guid"))
          .flatMap(_.asString)
          .orElse(obj.flatMap(_.get(field)).flatMap(_.asString))
      }
      .orElseFail(ChekhovError.Protocol(s"Missing guid field '$field' in $result"))

  private def stringResult(json: Json): IO[ChekhovError, String] =
    ZIO
      .fromOption {
        json.asObject.flatMap(_.get("value")).flatMap(_.asString).orElse(json.asString)
      }
      .orElseFail(ChekhovError.Protocol(s"Expected string result, got $json"))

  private def optionalString(json: Json): IO[ChekhovError, String] =
    ZIO.succeed {
      json.asObject.flatMap(_.get("value")).flatMap(_.asString).orElse(json.asString).getOrElse("")
    }

  private def cookieInitJson(c: CookieInit): Json =
    Json.Obj(
      Chunk(
        Some("name"  -> Json.Str(c.name)),
        Some("value" -> Json.Str(c.value)),
        c.url.map(u => "url" -> Json.Str(u)),
        c.domain.map(d => "domain" -> Json.Str(d)),
        c.path.map(p => "path" -> Json.Str(p)),
        c.expires.map(e => "expires" -> Json.Num(e)),
        c.httpOnly.map(h => "httpOnly" -> Json.Bool(h)),
        c.secure.map(s => "secure" -> Json.Bool(s)),
        c.sameSite.map(s => "sameSite" -> Json.Str(s)),
      ).flatten*
    )

  private def decodeCookies(json: Json): IO[ChekhovError, Chunk[Cookie]] =
    val arr = json.asObject.flatMap(_.get("cookies")).flatMap(_.asArray).getOrElse(Chunk.empty)
    ZIO
      .foreach(arr) { item =>
        ZIO
          .fromOption {
            for
              obj      <- item.asObject
              name     <- obj.get("name").flatMap(_.asString)
              value    <- obj.get("value").flatMap(_.asString)
              domain   <- obj.get("domain").flatMap(_.asString)
              path     <- obj.get("path").flatMap(_.asString)
              expires  <- obj.get("expires").flatMap(_.asNumber).map(_.value.doubleValue)
              httpOnly <- obj.get("httpOnly").flatMap(_.asBoolean)
              secure   <- obj.get("secure").flatMap(_.asBoolean)
              sameSite <- obj.get("sameSite").flatMap(_.asString)
            yield Cookie(name, value, domain, path, expires, httpOnly, secure, sameSite)
          }
          .orElseFail(ChekhovError.Protocol(s"Invalid cookie: $item"))
      }
      .map(Chunk.fromIterable)
  end decodeCookies

  private def decodeStorageItems(json: Json): IO[ChekhovError, Chunk[StorageItem]] =
    val arr = json.asObject.flatMap(_.get("items")).flatMap(_.asArray).getOrElse(Chunk.empty)
    ZIO
      .foreach(arr) { item =>
        ZIO
          .fromOption {
            for
              obj   <- item.asObject
              name  <- obj.get("name").flatMap(_.asString)
              value <- obj.get("value").flatMap(_.asString)
            yield StorageItem(name, value)
          }
          .orElseFail(ChekhovError.Protocol(s"Invalid storage item: $item"))
      }
      .map(Chunk.fromIterable)
  end decodeStorageItems

  def browserType(using Trace): ZIO[ChannelConnection & ChekhovConfig, ChekhovError, BrowserType] =
    for
      conn     <- ZIO.service[ChannelConnection]
      config   <- ZIO.service[ChekhovConfig]
      typeGuid <- ZIO
        .fromOption {
          conn.initializer.asObject
            .flatMap(_.get(config.browser.channelName))
            .flatMap(_.asObject)
            .flatMap(_.get("guid"))
            .flatMap(_.asString)
        }
        .orElseFail(
          ChekhovError.Protocol(
            s"No browser type guid for ${config.browser.channelName} in ${conn.initializer}"
          )
        )
    yield BrowserTypeService(conn, config.browser, typeGuid)

  val browserTypeLive: ZLayer[ChannelConnection & ChekhovConfig, ChekhovError, BrowserType] =
    ZLayer.fromZIO(browserType)

  /** Transport + connection + BrowserType (saferis-style composition). */
  val withBrowserType: ZLayer[ChekhovConfig, ChekhovError, BrowserType] =
    ChannelTransport.layer >>> ChannelConnection.layer >>> browserTypeLive

  val browserLayer: ZLayer[ChekhovConfig & BrowserType, ChekhovError, Browser] =
    ZLayer.scoped(ZIO.serviceWithZIO[BrowserType](_.launch))

  val contextLayer: ZLayer[ChekhovConfig & ArtifactSession & Browser, ChekhovError, BrowserContext] =
    ZLayer.scoped(ZIO.serviceWithZIO[Browser](_.newContext))

  val pageLayer: ZLayer[BrowserContext, ChekhovError, Page] =
    ZLayer.scoped(ZIO.serviceWithZIO[BrowserContext](_.newPage))

  /** Common suite stack: config in → session + BrowserType + Browser + BrowserContext + Page out. */
  val suiteLayers: ZLayer[
    ChekhovConfig,
    ChekhovError,
    ArtifactSession & BrowserType & Browser & BrowserContext & Page,
  ] =
    ArtifactSession.live >+> (withBrowserType >+> browserLayer >+> contextLayer >+> pageLayer)
end PlaywrightDriver
