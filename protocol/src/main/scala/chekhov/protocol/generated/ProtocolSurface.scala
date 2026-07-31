package chekhov.protocol.generated

/** Channel command inventories extracted from protocol.yml (coverage / bump parity). */
object ProtocolSurface:
  val channels: List[String] = List(
    "Root",
    "Playwright",
    "BrowserType",
    "Browser",
    "BrowserContext",
    "Page",
    "Frame",
    "Tracing",
    "Artifact"
  )

  val commands: Map[String, Set[String]] = Map(
    "Root" -> Set("initialize"),
    "Playwright" -> Set("newRequest"),
    "BrowserType" -> Set("connectOverCDP", "connectToWorker", "launch", "launchPersistentContext"),
    "Browser" -> Set("close", "defaultUserAgentForTest", "disconnectFromReusedContext", "killForTests", "newBrowserCDPSession", "newContext", "newContextForReuse", "startServer", "startTracing", "stopServer", "stopTracing"),
    "BrowserContext" -> Set("addCookies", "addInitScript", "clearCookies", "clearPermissions", "clockFastForward", "clockInstall", "clockPauseAt", "clockResume", "clockRunFor", "clockSetFixedTime", "clockSetSystemTime", "close", "cookies", "createTempFiles", "credentialsCreate", "credentialsDelete", "credentialsGet", "credentialsInstall", "disableRecorder", "enableRecorder", "exposeBinding", "exposeConsoleApi", "grantPermissions", "newCDPSession", "newPage", "pause", "registerSelectorEngine", "setExtraHTTPHeaders", "setGeolocation", "setHTTPCredentials", "setNetworkInterceptionPatterns", "setOffline", "setStorageState", "setTestIdAttributeName", "setWebSocketInterceptionPatterns", "storageState", "updateSubscription"),
    "Page" -> Set("addInitScript", "bringToFront", "cancelPickLocator", "clearConsoleMessages", "clearPageErrors", "close", "consoleMessages", "emulateMedia", "expectScreenshot", "exposeBinding", "goBack", "goForward", "hideHighlight", "keyboardDown", "keyboardInsertText", "keyboardPress", "keyboardType", "keyboardUp", "mouseClick", "mouseDown", "mouseMove", "mouseUp", "mouseWheel", "pageErrors", "pdf", "pickLocator", "registerLocatorHandler", "reload", "requestGC", "requests", "resolveLocatorHandlerNoReply", "runBeforeUnload", "screencastChapter", "screencastFrameAck", "screencastHideActions", "screencastRemoveOverlay", "screencastSetOverlayVisible", "screencastShowActions", "screencastShowOverlay", "screencastStart", "screencastStop", "screenshot", "setDockTile", "setExtraHTTPHeaders", "setNetworkInterceptionPatterns", "setViewportSize", "setWebSocketInterceptionPatterns", "startCSSCoverage", "startJSCoverage", "stopCSSCoverage", "stopJSCoverage", "touchscreenTap", "unregisterLocatorHandler", "updateSubscription", "webStorageClear", "webStorageGetItem", "webStorageItems", "webStorageRemoveItem", "webStorageSetItem"),
    "Frame" -> Set("addScriptTag", "addStyleTag", "ariaSnapshot", "blur", "check", "click", "content", "dblclick", "dispatchEvent", "dragAndDrop", "drop", "evalOnSelector", "evalOnSelectorAll", "evaluateExpression", "evaluateExpressionHandle", "expect", "fill", "focus", "frameElement", "getAttribute", "goto", "hideHighlight", "highlight", "hover", "innerHTML", "innerText", "inputValue", "isChecked", "isDisabled", "isEditable", "isEnabled", "isHidden", "isVisible", "press", "queryCount", "querySelector", "querySelectorAll", "resolveSelector", "selectOption", "setContent", "setInputFiles", "tap", "textContent", "title", "type", "uncheck", "waitForFunction", "waitForSelector", "waitForTimeout"),
    "Tracing" -> Set("harExport", "harStart", "tracingGroup", "tracingGroupEnd", "tracingStart", "tracingStartChunk", "tracingStop", "tracingStopChunk"),
    "Artifact" -> Set("cancel", "delete", "failure", "pathAfterFinished", "saveAs", "saveAsStream", "stream")
  )

  def has(channel: String, method: String): Boolean =
    commands.getOrElse(channel, Set.empty).contains(method)
end ProtocolSurface
