package chekhov

import zio.*

import java.nio.file.Path

/** Browser engines Playwright ships. */
enum ChekhovBrowser:
  case Chromium, Firefox, WebKit

  def channelName: String = this match
    case Chromium => "chromium"
    case Firefox  => "firefox"
    case WebKit   => "webkit"

object ChekhovBrowser:
  def fromString(s: String): Option[ChekhovBrowser] =
    s.trim.toLowerCase match
      case "chromium" | "chrome" => Some(Chromium)
      case "firefox"             => Some(Firefox)
      case "webkit"              => Some(WebKit)
      case _                     => None

  /** Comma / whitespace separated channel names; unknown tokens are dropped. */
  def fromList(s: String): List[ChekhovBrowser] =
    s.split("[,\\s]+").iterator.map(_.trim).filter(_.nonEmpty).flatMap(fromString).toList.distinct

  /** Browsers declared by `-Dchekhov.browsers` / `CHEKHOV_BROWSERS` (sbt `chekhovBrowsers`). */
  def listed(
      props: Map[String, String] = sys.props.toMap,
      env: Map[String, String] = sys.env,
  ): List[ChekhovBrowser] =
    props
      .get("chekhov.browsers")
      .orElse(env.get("CHEKHOV_BROWSERS"))
      .map(fromList)
      .filter(_.nonEmpty)
      .getOrElse(Nil)

  /** Listed browsers, else the single `-Dchekhov.browser` / `CHEKHOV_BROWSER`, else Chromium. */
  def configured(
      props: Map[String, String] = sys.props.toMap,
      env: Map[String, String] = sys.env,
  ): List[ChekhovBrowser] =
    listed(props, env) match
      case Nil =>
        props
          .get("chekhov.browser")
          .orElse(env.get("CHEKHOV_BROWSER"))
          .flatMap(fromString)
          .toList match
          case Nil => List(Chromium)
          case one => one
      case many => many
end ChekhovBrowser

/** When to keep Playwright traces / videos under [[ChekhovConfig.artifactsDir]]. */
enum ArtifactCapture:
  case Off, OnFailure, Always

object ArtifactCapture:
  def fromString(s: String): Option[ArtifactCapture] =
    s.trim.toLowerCase match
      case "off" | "false" | "0" | "none"         => Some(Off)
      case "onfailure" | "on-failure" | "failure" => Some(OnFailure)
      case "always" | "true" | "1" | "on"         => Some(Always)
      case _                                      => None

/** How to finish a tracing session started via [[BrowserContext.startTracing]]. */
enum TraceStop:
  case Archive(path: Path)
  case Discard

/** Runtime configuration for Chekhov suites and drivers. */
final case class ChekhovConfig(
    browser: ChekhovBrowser = ChekhovBrowser.Chromium,
    headless: Boolean = true,
    baseUrl: Option[String] = None,
    artifactsDir: Path = Path.of("target", "chekhov"),
    defaultTimeoutMs: Double = 30_000,
    browserInstallEnv: Map[String, String] = Map.empty,
    traceCapture: ArtifactCapture = ArtifactCapture.Off,
    videoCapture: ArtifactCapture = ArtifactCapture.Off,
    // Launch a system browser instead of a Playwright-downloaded one: `executablePath`
    // points at a browser binary, `channel` selects an installed channel (e.g. "chrome";
    // Chromium only). When either is set the pinned-browser-revision check is skipped.
    // `launchArgs` are extra process args, comma / whitespace separated; use
    // `--flag=value` when an argument takes a value (e.g. "--no-sandbox" on NixOS).
    executablePath: Option[String] = None,
    channel: Option[String] = None,
    launchArgs: List[String] = Nil,
)

object ChekhovConfig:
  /** Config from `-Dchekhov.*` system properties and `CHEKHOV_*` env vars; props win. */
  def fromProps(
      props: Map[String, String] = sys.props.toMap,
      env: Map[String, String] = sys.env,
  ): ChekhovConfig =
    ChekhovConfig(
      browser = ChekhovBrowser.configured(props, env).head,
      headless = props
        .get("chekhov.headless")
        .orElse(env.get("CHEKHOV_HEADLESS"))
        .filter(_.nonEmpty)
        .map(_.toBoolean)
        .getOrElse(true),
      baseUrl = props.get("chekhov.baseUrl").orElse(env.get("CHEKHOV_BASE_URL")).filter(_.nonEmpty),
      artifactsDir = props
        .get("chekhov.artifactsDir")
        .orElse(env.get("CHEKHOV_ARTIFACTS_DIR"))
        .filter(_.nonEmpty)
        .map(Path.of(_))
        .getOrElse(Path.of("target", "chekhov")),
      traceCapture = props
        .get("chekhov.traceCapture")
        .orElse(env.get("CHEKHOV_TRACE_CAPTURE"))
        .flatMap(ArtifactCapture.fromString)
        .getOrElse(ArtifactCapture.Off),
      videoCapture = props
        .get("chekhov.videoCapture")
        .orElse(env.get("CHEKHOV_VIDEO_CAPTURE"))
        .flatMap(ArtifactCapture.fromString)
        .getOrElse(ArtifactCapture.Off),
      executablePath = props
        .get("chekhov.executablePath")
        .orElse(env.get("CHEKHOV_EXECUTABLE_PATH"))
        .filter(_.nonEmpty),
      channel = props
        .get("chekhov.channel")
        .orElse(env.get("CHEKHOV_CHANNEL"))
        .filter(_.nonEmpty),
      launchArgs = props
        .get("chekhov.launchArgs")
        .orElse(env.get("CHEKHOV_LAUNCH_ARGS"))
        .map(parseLaunchArgs)
        .getOrElse(Nil),
    )

  /** Comma / whitespace separated process args; `--flag=value` when an argument takes a value. */
  private def parseLaunchArgs(raw: String): List[String] =
    raw.split("[,\\s]+").iterator.map(_.trim).filter(_.nonEmpty).toList

  val layer: ULayer[ChekhovConfig] =
    ZLayer.succeed(fromProps())

  def layer(config: ChekhovConfig): ULayer[ChekhovConfig] =
    ZLayer.succeed(config)
end ChekhovConfig

sealed trait ChekhovError extends Exception:
  def message: String
  override def getMessage: String = message

object ChekhovError:
  final case class Protocol(message: String, cause: Option[Throwable] = None) extends ChekhovError:
    cause.foreach(initCause)
  final case class Timeout(message: String)                                 extends ChekhovError
  final case class Driver(message: String, cause: Option[Throwable] = None) extends ChekhovError:
    cause.foreach(initCause)
  final case class Serve(message: String, cause: Option[Throwable] = None) extends ChekhovError:
    cause.foreach(initCause)
  final case class Assertion(message: String) extends ChekhovError
