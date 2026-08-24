package chekhov.ziotest

import chekhov.ChekhovConfig
import zio.*
import zio.test.*

/** Parks the JVM after a headed suite when `-Dchekhov.keepOpen` / `CHEKHOV_KEEP_OPEN` is true. */
object KeepOpen:

  def park: UIO[Unit] =
    ZIO
      .attemptBlocking {
        java.lang.System.err.println("chekhov: browser kept open; press Enter to exit")
        val _ = scala.io.StdIn.readLine()
      }
      .orElseSucceed(())

  def afterSuite: UIO[Unit] =
    ZIO.succeed(ChekhovConfig.fromProps()).flatMap { cfg =>
      if cfg.keepOpen then park else ZIO.unit
    }

  /** Must sit inside `onBrowsers` so park runs before the shared process is released. */
  val aspect: TestAspect[Nothing, Any, Nothing, Any] =
    TestAspect.aroundAll(ZIO.unit, afterSuite)
end KeepOpen
