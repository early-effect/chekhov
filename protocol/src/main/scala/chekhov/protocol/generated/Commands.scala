package chekhov.protocol.generated

import zio.json.*
import zio.json.ast.Json

/** Typed channel command params for the Chekhov interpreter.
  *
  * Generated from protocol.yml via ProtocolCodegen.commandAllowlist.
  * Do not edit field lists by hand; run `sbt pwCodegen` / `pwBump`.
  */
object Commands:

  /** (channel, method, caseClassName) covered by this file. */
  val allowlist: List[(String, String, String)] = List(
    ("Root", "initialize", "Initialize"),
    ("BrowserType", "launch", "BrowserTypeLaunch"),
    ("Browser", "newContext", "BrowserNewContext"),
    ("Browser", "close", "BrowserClose"),
    ("BrowserContext", "newPage", "BrowserContextNewPage"),
    ("BrowserContext", "close", "BrowserContextClose"),
    ("BrowserContext", "storageState", "BrowserContextStorageState"),
    ("BrowserContext", "setStorageState", "BrowserContextSetStorageState"),
    ("BrowserContext", "cookies", "BrowserContextCookies"),
    ("BrowserContext", "addCookies", "BrowserContextAddCookies"),
    ("BrowserContext", "clearCookies", "BrowserContextClearCookies"),
    ("Frame", "goto", "PageGoto"),
    ("Frame", "click", "PageClick"),
    ("Frame", "fill", "PageFill"),
    ("Frame", "press", "PagePress"),
    ("Frame", "innerText", "PageInnerText"),
    ("Frame", "textContent", "PageTextContent"),
    ("Frame", "title", "PageTitle"),
    ("Frame", "evaluateExpression", "FrameEvaluateExpression"),
    ("Page", "webStorageItems", "PageWebStorageItems"),
    ("Page", "webStorageGetItem", "PageWebStorageGetItem"),
    ("Page", "webStorageSetItem", "PageWebStorageSetItem"),
    ("Page", "webStorageRemoveItem", "PageWebStorageRemoveItem"),
    ("Page", "webStorageClear", "PageWebStorageClear"),
    ("Page", "keyboardPress", "PageKeyboardPress"),
    ("Page", "screenshot", "PageScreenshot"),
    ("Page", "close", "PageClose"),
    ("Tracing", "tracingStart", "TracingStart"),
    ("Tracing", "tracingStartChunk", "TracingStartChunk"),
    ("Tracing", "tracingStopChunk", "TracingStopChunk"),
    ("Tracing", "tracingStop", "TracingStop"),
    ("Artifact", "saveAs", "ArtifactSaveAs")
  )

  final case class Initialize(
      sdkLanguage: String
  ) derives JsonCodec


  final case class BrowserTypeLaunch(
      args: Option[Json] = None,
      artifactsDir: Option[String] = None,
      cdpPort: Option[Double] = None,
      channel: Option[String] = None,
      chromiumSandbox: Option[Boolean] = None,
      downloadsPath: Option[String] = None,
      env: Option[Json] = None,
      executablePath: Option[String] = None,
      firefoxUserPrefs: Option[Json] = None,
      handleSIGHUP: Option[Boolean] = None,
      handleSIGINT: Option[Boolean] = None,
      handleSIGTERM: Option[Boolean] = None,
      headless: Option[Boolean] = None,
      ignoreAllDefaultArgs: Option[Boolean] = None,
      ignoreDefaultArgs: Option[Json] = None,
      proxy: Option[Json] = None,
      slowMo: Option[Double] = None,
      tracesDir: Option[String] = None
  ) derives JsonCodec


  final case class BrowserNewContext(
      acceptDownloads: Option[String] = None,
      baseURL: Option[String] = None,
      bypassCSP: Option[Boolean] = None,
      clientCertificates: Option[Json] = None,
      colorScheme: Option[String] = None,
      contrast: Option[String] = None,
      deviceScaleFactor: Option[Double] = None,
      extraHTTPHeaders: Option[Json] = None,
      forcedColors: Option[String] = None,
      geolocation: Option[Json] = None,
      hasTouch: Option[Boolean] = None,
      httpCredentials: Option[Json] = None,
      ignoreHTTPSErrors: Option[Boolean] = None,
      isMobile: Option[Boolean] = None,
      javaScriptEnabled: Option[Boolean] = None,
      locale: Option[String] = None,
      noDefaultViewport: Option[Boolean] = None,
      offline: Option[Boolean] = None,
      permissions: Option[Json] = None,
      proxy: Option[Json] = None,
      recordVideo: Option[Json] = None,
      reducedMotion: Option[String] = None,
      screen: Option[Json] = None,
      selectorEngines: Option[Json] = None,
      serviceWorkers: Option[String] = None,
      storageState: Option[Json] = None,
      strictSelectors: Option[Boolean] = None,
      testIdAttributeName: Option[String] = None,
      timezoneId: Option[String] = None,
      userAgent: Option[String] = None,
      viewport: Option[Json] = None
  ) derives JsonCodec


  final case class BrowserClose(
      reason: Option[String] = None
  ) derives JsonCodec


  final case class BrowserContextNewPage()

  object BrowserContextNewPage:
    given JsonCodec[BrowserContextNewPage] = emptyCodec(BrowserContextNewPage())


  final case class BrowserContextClose(
      reason: Option[String] = None
  ) derives JsonCodec


  final case class BrowserContextStorageState(
      credentials: Option[Boolean] = None,
      indexedDB: Option[Boolean] = None
  ) derives JsonCodec


  final case class BrowserContextSetStorageState(
      storageState: Option[Json] = None
  ) derives JsonCodec


  final case class BrowserContextCookies(
      urls: Json
  ) derives JsonCodec


  final case class BrowserContextAddCookies(
      cookies: Json
  ) derives JsonCodec


  final case class BrowserContextClearCookies(
      domain: Option[String] = None,
      domainRegexFlags: Option[String] = None,
      domainRegexSource: Option[String] = None,
      name: Option[String] = None,
      nameRegexFlags: Option[String] = None,
      nameRegexSource: Option[String] = None,
      path: Option[String] = None,
      pathRegexFlags: Option[String] = None,
      pathRegexSource: Option[String] = None
  ) derives JsonCodec


  final case class PageGoto(
      url: String,
      referer: Option[String] = None,
      waitUntil: Option[String] = None
  ) derives JsonCodec


  final case class PageClick(
      selector: String,
      button: Option[String] = None,
      clickCount: Option[Double] = None,
      delay: Option[Double] = None,
      force: Option[Boolean] = None,
      modifiers: Option[Json] = None,
      noWaitAfter: Option[Boolean] = None,
      position: Option[Point] = None,
      scroll: Option[String] = None,
      steps: Option[Double] = None,
      strict: Option[Boolean] = None,
      trial: Option[Boolean] = None
  ) derives JsonCodec


  final case class PageFill(
      selector: String,
      value: String,
      force: Option[Boolean] = None,
      strict: Option[Boolean] = None
  ) derives JsonCodec


  final case class PagePress(
      key: String,
      selector: String,
      delay: Option[Double] = None,
      noWaitAfter: Option[Boolean] = None,
      strict: Option[Boolean] = None
  ) derives JsonCodec


  final case class PageInnerText(
      selector: String,
      strict: Option[Boolean] = None
  ) derives JsonCodec


  final case class PageTextContent(
      selector: String,
      strict: Option[Boolean] = None
  ) derives JsonCodec


  final case class PageTitle()

  object PageTitle:
    given JsonCodec[PageTitle] = emptyCodec(PageTitle())


  final case class FrameEvaluateExpression(
      arg: Json,
      expression: String,
      isFunction: Option[Boolean] = None
  ) derives JsonCodec


  final case class PageWebStorageItems(
      kind: String
  ) derives JsonCodec


  final case class PageWebStorageGetItem(
      kind: String,
      name: String
  ) derives JsonCodec


  final case class PageWebStorageSetItem(
      kind: String,
      name: String,
      value: String
  ) derives JsonCodec


  final case class PageWebStorageRemoveItem(
      kind: String,
      name: String
  ) derives JsonCodec


  final case class PageWebStorageClear(
      kind: String
  ) derives JsonCodec


  final case class PageKeyboardPress(
      key: String,
      delay: Option[Double] = None
  ) derives JsonCodec


  final case class PageScreenshot(
      animations: Option[String] = None,
      caret: Option[String] = None,
      clip: Option[Rect] = None,
      fullPage: Option[Boolean] = None,
      mask: Option[Json] = None,
      maskColor: Option[String] = None,
      omitBackground: Option[Boolean] = None,
      quality: Option[Double] = None,
      scale: Option[String] = None,
      style: Option[String] = None,
      `type`: Option[String] = None
  ) derives JsonCodec


  final case class PageClose(
      reason: Option[String] = None
  ) derives JsonCodec


  final case class TracingStart(
      live: Option[Boolean] = None,
      name: Option[String] = None,
      screenshots: Option[Boolean] = None,
      snapshots: Option[Boolean] = None
  ) derives JsonCodec


  final case class TracingStartChunk(
      name: Option[String] = None,
      title: Option[String] = None
  ) derives JsonCodec


  final case class TracingStopChunk(
      mode: String
  ) derives JsonCodec


  final case class TracingStop()

  object TracingStop:
    given JsonCodec[TracingStop] = emptyCodec(TracingStop())


  final case class ArtifactSaveAs(
      path: String
  ) derives JsonCodec


  private def emptyCodec[A](value: A): JsonCodec[A] =
    JsonCodec(
      JsonEncoder[Json].contramap(_ => Json.Obj()),
      JsonDecoder[Json].map(_ => value),
    )

end Commands
