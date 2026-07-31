package chekhov

import zio.*

/** Accessibility / locator roles used by the public algebra. */
enum Role:
  case Button, Link, Textbox, Searchbox, Checkbox, Radio, Listitem, Heading, Img, Navigation, Main, Banner,
    Contentinfo, Form, Dialog, Alert, Status, Option, Combobox, Switch, Tab, Tabpanel, Treeitem, Cell, Row, Grid,
    Listbox, Menu, Menuitem, Progressbar, Slider, Spinbutton, Tooltip, Generic

object Role:
  def fromString(s: String): Option[Role] =
    values.find(_.toString.equalsIgnoreCase(s.replace("-", "")))

/** localStorage vs sessionStorage for page webStorage* commands. */
enum WebStorageKind:
  case Local, Session

  def protocolLiteral: String =
    this match
      case Local   => "local"
      case Session => "session"

/** One localStorage / sessionStorage entry. */
final case class StorageItem(name: String, value: String)

/** Cookie as returned by `BrowserContext.cookies` / `storageState`. */
final case class Cookie(
    name: String,
    value: String,
    domain: String,
    path: String,
    expires: Double,
    httpOnly: Boolean,
    secure: Boolean,
    sameSite: String,
)

/** Cookie to install via `BrowserContext.addCookies`. */
final case class CookieInit(
    name: String,
    value: String,
    url: Option[String] = None,
    domain: Option[String] = None,
    path: Option[String] = None,
    expires: Option[Double] = None,
    httpOnly: Option[Boolean] = None,
    secure: Option[Boolean] = None,
    sameSite: Option[String] = None,
)

/** Public ZIO algebras for browser automation (saferis-style services + companions). */
trait BrowserType:
  def launch(using Trace): ZIO[Scope & ChekhovConfig, ChekhovError, Browser]
  def name: String

object BrowserType:
  def launch(using Trace): ZIO[BrowserType & Scope & ChekhovConfig, ChekhovError, Browser] =
    ZIO.serviceWithZIO[BrowserType](_.launch)

  def name(using Trace): URIO[BrowserType, String] =
    ZIO.serviceWith[BrowserType](_.name)

trait Browser:
  def newContext(using Trace): ZIO[Scope & ChekhovConfig, ChekhovError, BrowserContext]
  def close(using Trace): IO[ChekhovError, Unit]

object Browser:
  def newContext(using Trace): ZIO[Browser & Scope & ChekhovConfig, ChekhovError, BrowserContext] =
    ZIO.serviceWithZIO[Browser](_.newContext)

  def close(using Trace): ZIO[Browser, ChekhovError, Unit] =
    ZIO.serviceWithZIO[Browser](_.close)

trait BrowserContext:
  def newPage(using Trace): ZIO[Scope, ChekhovError, Page]

  /** Snapshot cookies + origin storage (localStorage; IndexedDB when `indexedDB` is true). JSON matches Playwright
    * storage state.
    */
  def storageState(indexedDB: Boolean = false)(using Trace): IO[ChekhovError, String]

  /** Apply a Playwright storage-state JSON document (cookies + origins, optional IndexedDB). */
  def setStorageState(stateJson: String)(using Trace): IO[ChekhovError, Unit]

  def cookies(urls: Chunk[String] = Chunk.empty)(using Trace): IO[ChekhovError, Chunk[Cookie]]
  def addCookies(cookies: Chunk[CookieInit])(using Trace): IO[ChekhovError, Unit]
  def clearCookies(using Trace): IO[ChekhovError, Unit]
  def close(using Trace): IO[ChekhovError, Unit]
end BrowserContext

object BrowserContext:
  def newPage(using Trace): ZIO[BrowserContext & Scope, ChekhovError, Page] =
    ZIO.serviceWithZIO[BrowserContext](_.newPage)

  def storageState(indexedDB: Boolean = false)(using Trace): ZIO[BrowserContext, ChekhovError, String] =
    ZIO.serviceWithZIO[BrowserContext](_.storageState(indexedDB))

  def setStorageState(stateJson: String)(using Trace): ZIO[BrowserContext, ChekhovError, Unit] =
    ZIO.serviceWithZIO[BrowserContext](_.setStorageState(stateJson))

  def cookies(urls: Chunk[String] = Chunk.empty)(using Trace): ZIO[BrowserContext, ChekhovError, Chunk[Cookie]] =
    ZIO.serviceWithZIO[BrowserContext](_.cookies(urls))

  def addCookies(cookies: Chunk[CookieInit])(using Trace): ZIO[BrowserContext, ChekhovError, Unit] =
    ZIO.serviceWithZIO[BrowserContext](_.addCookies(cookies))

  def clearCookies(using Trace): ZIO[BrowserContext, ChekhovError, Unit] =
    ZIO.serviceWithZIO[BrowserContext](_.clearCookies)

  def close(using Trace): ZIO[BrowserContext, ChekhovError, Unit] =
    ZIO.serviceWithZIO[BrowserContext](_.close)
end BrowserContext

trait Page:
  def goto(url: String)(using Trace): IO[ChekhovError, Unit]
  def click(selector: String)(using Trace): IO[ChekhovError, Unit]
  def fill(selector: String, value: String)(using Trace): IO[ChekhovError, Unit]
  def press(selector: String, key: String)(using Trace): IO[ChekhovError, Unit]
  def innerText(selector: String)(using Trace): IO[ChekhovError, String]
  def textContent(selector: String)(using Trace): IO[ChekhovError, String]
  def title(using Trace): IO[ChekhovError, String]

  /** Evaluate JS in the main frame. Returns Playwright SerializedValue JSON. When `isFunction`, `expression` is invoked
    * as a function.
    */
  def evaluate(expression: String, isFunction: Boolean = false)(using Trace): IO[ChekhovError, String]

  def webStorageItems(kind: WebStorageKind)(using Trace): IO[ChekhovError, Chunk[StorageItem]]
  def webStorageGetItem(kind: WebStorageKind, name: String)(using Trace): IO[ChekhovError, Option[String]]
  def webStorageSetItem(kind: WebStorageKind, name: String, value: String)(using Trace): IO[ChekhovError, Unit]
  def webStorageRemoveItem(kind: WebStorageKind, name: String)(using Trace): IO[ChekhovError, Unit]
  def webStorageClear(kind: WebStorageKind)(using Trace): IO[ChekhovError, Unit]

  /** Capture a PNG of the page (viewport) and write it to `path`. Returns `path`. */
  def screenshot(path: java.nio.file.Path)(using Trace): IO[ChekhovError, java.nio.file.Path]

  def locator(selector: String): Locator
  def getByPlaceholder(text: String): Locator
  def getByRole(role: Role, name: Option[String] = None): Locator
  def getByTestId(testId: String): Locator
  def keyboard: Keyboard
  def close(using Trace): IO[ChekhovError, Unit]
end Page

object Page:
  def goto(url: String)(using Trace): ZIO[Page, ChekhovError, Unit] =
    ZIO.serviceWithZIO[Page](_.goto(url))

  def click(selector: String)(using Trace): ZIO[Page, ChekhovError, Unit] =
    ZIO.serviceWithZIO[Page](_.click(selector))

  def fill(selector: String, value: String)(using Trace): ZIO[Page, ChekhovError, Unit] =
    ZIO.serviceWithZIO[Page](_.fill(selector, value))

  def press(selector: String, key: String)(using Trace): ZIO[Page, ChekhovError, Unit] =
    ZIO.serviceWithZIO[Page](_.press(selector, key))

  def innerText(selector: String)(using Trace): ZIO[Page, ChekhovError, String] =
    ZIO.serviceWithZIO[Page](_.innerText(selector))

  def textContent(selector: String)(using Trace): ZIO[Page, ChekhovError, String] =
    ZIO.serviceWithZIO[Page](_.textContent(selector))

  def title(using Trace): ZIO[Page, ChekhovError, String] =
    ZIO.serviceWithZIO[Page](_.title)

  def webStorageItems(kind: WebStorageKind)(using Trace): ZIO[Page, ChekhovError, Chunk[StorageItem]] =
    ZIO.serviceWithZIO[Page](_.webStorageItems(kind))

  def webStorageGetItem(kind: WebStorageKind, name: String)(using Trace): ZIO[Page, ChekhovError, Option[String]] =
    ZIO.serviceWithZIO[Page](_.webStorageGetItem(kind, name))

  def webStorageSetItem(kind: WebStorageKind, name: String, value: String)(using Trace): ZIO[Page, ChekhovError, Unit] =
    ZIO.serviceWithZIO[Page](_.webStorageSetItem(kind, name, value))

  def webStorageRemoveItem(kind: WebStorageKind, name: String)(using Trace): ZIO[Page, ChekhovError, Unit] =
    ZIO.serviceWithZIO[Page](_.webStorageRemoveItem(kind, name))

  def webStorageClear(kind: WebStorageKind)(using Trace): ZIO[Page, ChekhovError, Unit] =
    ZIO.serviceWithZIO[Page](_.webStorageClear(kind))

  def screenshot(path: java.nio.file.Path)(using Trace): ZIO[Page, ChekhovError, java.nio.file.Path] =
    ZIO.serviceWithZIO[Page](_.screenshot(path))

  def close(using Trace): ZIO[Page, ChekhovError, Unit] =
    ZIO.serviceWithZIO[Page](_.close)
end Page

trait Locator:
  def click(using Trace): IO[ChekhovError, Unit]
  def fill(value: String)(using Trace): IO[ChekhovError, Unit]
  def press(key: String)(using Trace): IO[ChekhovError, Unit]
  def innerText(using Trace): IO[ChekhovError, String]
  def textContent(using Trace): IO[ChekhovError, String]

trait Keyboard:
  def press(key: String)(using Trace): IO[ChekhovError, Unit]

/** Entry accessors for suites (`ZIO.service` style). */
object Chekhov:
  def config(using Trace): URIO[ChekhovConfig, ChekhovConfig] =
    ZIO.service[ChekhovConfig]

  def page(using Trace): URIO[Page, Page] =
    ZIO.service[Page]

  def browserContext(using Trace): URIO[BrowserContext, BrowserContext] =
    ZIO.service[BrowserContext]

  def browser(using Trace): URIO[Browser, Browser] =
    ZIO.service[Browser]

  def browserType(using Trace): URIO[BrowserType, BrowserType] =
    ZIO.service[BrowserType]
end Chekhov
