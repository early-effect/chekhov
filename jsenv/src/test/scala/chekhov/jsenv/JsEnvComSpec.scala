package chekhov.jsenv

import chekhov.ChekhovBrowser
import org.scalajs.jsenv.*
import zio.*
import zio.test.*

import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/** Live ChekhovJSEnv Com smoke. Enable with `CHEKHOV_E2E=1` or `-Dchekhov.e2e=1`. */
object JsEnvComSpec extends ZIOSpecDefault:

  private def e2eEnabled: Boolean =
    sys.env.get("CHEKHOV_E2E").contains("1") ||
      sys.props.get("chekhov.e2e").contains("1")

  private val onlyIfE2E: TestAspectPoly =
    new TestAspect.PerTest.AtLeastR[Any]:
      def perTest[R, E](test: ZIO[R, TestFailure[E], TestSuccess])(using Trace) =
        if e2eEnabled then test else ZIO.succeed(TestSuccess.Ignored())

  override def aspects =
    Chunk(
      TestAspect.withLiveClock,
      TestAspect.timeout(60.seconds),
      TestAspect.sequential,
    )

  private def comRoundTrip(browser: ChekhovBrowser) =
    test(s"Com round-trip in ${browser.channelName}") {
      ZIO.attemptBlocking {
        val script = Files.createTempFile("chekhov-jsenv-", ".js")
        Files.writeString(
          script,
          """
            |scalajsCom.init(function (msg) {
            |  if (msg === "ping") scalajsCom.send("pong");
            |});
            |""".stripMargin,
        )
        script.toFile.deleteOnExit()

        val inbox = new LinkedBlockingQueue[String](8)
        val env   = ChekhovJSEnv(browser = browser, headless = true)
        val run   = env.startWithCom(
          Seq(Input.Script(script)),
          RunConfig(),
          msg =>
            inbox.offer(msg); (),
        )
        try
          run.send("ping")
          val got = Option(inbox.poll(15, TimeUnit.SECONDS))
          assertTrue(got.contains("pong"))
        finally
          run.close()
          Await.result(run.future, 30.seconds)
      }
    }

  def spec =
    suite("ChekhovJSEnv")(
      comRoundTrip(ChekhovBrowser.Chromium),
      comRoundTrip(ChekhovBrowser.Firefox),
      comRoundTrip(ChekhovBrowser.WebKit),
    ) @@ onlyIfE2E
end JsEnvComSpec
