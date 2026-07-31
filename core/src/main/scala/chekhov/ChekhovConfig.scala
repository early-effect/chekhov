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
)

object ChekhovConfig:
  val layer: ULayer[ChekhovConfig] =
    ZLayer.succeed:
      ChekhovConfig(
        browser = sys.props
          .get("chekhov.browser")
          .orElse(sys.env.get("CHEKHOV_BROWSER"))
          .flatMap(ChekhovBrowser.fromString)
          .getOrElse(ChekhovBrowser.Chromium),
        headless = sys.props
          .get("chekhov.headless")
          .orElse(sys.env.get("CHEKHOV_HEADLESS"))
          .forall(_.toBoolean),
        baseUrl = sys.props.get("chekhov.baseUrl").orElse(sys.env.get("CHEKHOV_BASE_URL")),
        artifactsDir = sys.props
          .get("chekhov.artifactsDir")
          .orElse(sys.env.get("CHEKHOV_ARTIFACTS_DIR"))
          .map(Path.of(_))
          .getOrElse(Path.of("target", "chekhov")),
        traceCapture = sys.props
          .get("chekhov.traceCapture")
          .orElse(sys.env.get("CHEKHOV_TRACE_CAPTURE"))
          .flatMap(ArtifactCapture.fromString)
          .getOrElse(ArtifactCapture.Off),
        videoCapture = sys.props
          .get("chekhov.videoCapture")
          .orElse(sys.env.get("CHEKHOV_VIDEO_CAPTURE"))
          .flatMap(ArtifactCapture.fromString)
          .getOrElse(ArtifactCapture.Off),
      )

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
