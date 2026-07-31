package chekhov.build

import org.scalajs.jsenv.*

import java.io.File
import java.net.{URL, URLClassLoader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

/** Lazy JSEnv for build.sbt: loads `chekhov.jsenv.ChekhovJSEnv` from a classpath file written by a task. */
final class ChekhovJsEnvBridge(classpathFile: File) extends JSEnv:

  val name: String = "ChekhovJsEnvBridge"

  private lazy val delegate: JSEnv = load()

  def start(input: Seq[Input], runConfig: RunConfig): JSRun =
    delegate.start(input, runConfig)

  def startWithCom(input: Seq[Input], runConfig: RunConfig, onMessage: String => Unit): JSComRun =
    delegate.startWithCom(input, runConfig, onMessage)

  private def load(): JSEnv =
    if !classpathFile.isFile then
      throw new IllegalStateException(
        s"Missing ${classpathFile.getAbsolutePath}. Run the jsenv classpath task before jsenv-smoke tests."
      )
    val urls = readClasspath(classpathFile.toPath)
    val parent = classOf[JSEnv].getClassLoader
    val cl     = new URLClassLoader(urls.toArray, parent)
    try
      val module = Class.forName("chekhov.jsenv.ChekhovJSEnv$", true, cl).getField("MODULE$").get(null)
      module.getClass.getMethod("create").invoke(module).asInstanceOf[JSEnv]
    catch
      case NonFatal(e) =>
        throw new IllegalStateException(s"Failed to load ChekhovJSEnv from $classpathFile", e)

  private def readClasspath(path: Path): Seq[URL] =
    val raw = Files.readString(path, StandardCharsets.UTF_8).trim
    if raw.isEmpty then Nil
    else
      raw
        .split(File.pathSeparatorChar)
        .toSeq
        .filter(_.nonEmpty)
        .map(p => Path.of(p).toUri.toURL)
end ChekhovJsEnvBridge

object ChekhovJsEnvBridge:
  /** Failed run when E2E is disabled (keeps `sbt test` green without browsers). */
  def ignored(reason: String): JSEnv = new JSEnv:
    val name = s"ChekhovJsEnvBridge(ignored: $reason)"
    def start(input: Seq[Input], runConfig: RunConfig): JSRun =
      JSRun.failed(new RuntimeException(name))
    def startWithCom(input: Seq[Input], runConfig: RunConfig, onMessage: String => Unit): JSComRun =
      JSComRun.failed(new RuntimeException(name))
end ChekhovJsEnvBridge
