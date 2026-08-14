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

/** Live ChekhovJSEnv Com smoke. */
object JsEnvComSpec extends ZIOSpecDefault:

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
    )
end JsEnvComSpec
