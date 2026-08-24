package chekhov.jsenv

import chekhov.ChekhovBrowser
import org.scalajs.jsenv.*

import scala.concurrent.Future

/** Playwright channel-backed JSEnv: scripts run in a real browser page over localhost. */
final class ChekhovJSEnv(
    browser: ChekhovBrowser = ChekhovBrowser.Chromium,
    headless: Boolean = true,
    keepOpen: Boolean = false,
) extends JSEnv:

  val name: String = s"ChekhovJSEnv(${browser.channelName}, headless=$headless, keepOpen=$keepOpen)"

  def start(input: Seq[Input], runConfig: RunConfig): JSRun =
    validate(runConfig)
    val runner = new BrowserRunner(browser, headless, keepOpen, onMessage = None, runConfig)
    runner.start(input)
    new JSRun:
      def future: Future[Unit] = runner.future
      def close(): Unit        = runner.close()

  def startWithCom(input: Seq[Input], runConfig: RunConfig, onMessage: String => Unit): JSComRun =
    validate(runConfig)
    val runner = new BrowserRunner(browser, headless, keepOpen, onMessage = Some(onMessage), runConfig)
    runner.start(input)
    new JSComRun:
      def future: Future[Unit]    = runner.future
      def close(): Unit           = runner.close()
      def send(msg: String): Unit = runner.send(msg)

  private def validate(runConfig: RunConfig): Unit =
    RunConfig
      .Validator()
      .supportsInheritIO()
      .supportsOnOutputStream()
      .validate(runConfig)
end ChekhovJSEnv

object ChekhovJSEnv:
  def apply(
      browser: ChekhovBrowser = ChekhovBrowser.Chromium,
      headless: Boolean = true,
      keepOpen: Boolean = false,
  ): ChekhovJSEnv =
    new ChekhovJSEnv(browser, headless, keepOpen)

  /** No-arg factory for reflective / sbt `jsEnv` wiring (Chromium, headless). */
  def create(): JSEnv = apply()
end ChekhovJSEnv
