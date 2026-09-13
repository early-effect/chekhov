package chekhov.build

import org.scalajs.jsenv.*

import java.io.File
import java.net.{URL, URLClassLoader}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

/** Lazy JSEnv for this monorepo's build.sbt only.
  *
  * Loads `chekhov.jsenv.ChekhovJSEnv` from a classpath file written by `writeJsenvClasspath`. Published consumers
  * should depend on `chekhov-jsenv` (or `sbt-chekhov`) and use `Test / jsEnv := ChekhovJSEnv()` / `chekhovJSEnv.value`
  * instead.
  */
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
    val urls   = readClasspath(classpathFile.toPath)
    val parent = classOf[JSEnv].getClassLoader
    // Child-first: sbt plugins (sbt-specular / specular-site) still put zio-json 0.10 on the parent
    // loader. jsenv is compiled against 1.1.0; parent-first then NCDFE Magnolia / JsonEncoderDerivation
    // and the Scala.js adapter only sees RunTerminatedException.
    val cl     = new ChildFirstURLClassLoader(urls.toArray, parent)
    try
      val module = Class.forName("chekhov.jsenv.ChekhovJSEnv$", true, cl).getField("MODULE$").get(null)
      module.getClass.getMethod("create").invoke(module).asInstanceOf[JSEnv]
    catch
      case NonFatal(e) =>
        throw new IllegalStateException(s"Failed to load ChekhovJSEnv from $classpathFile", e)
  end load

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

/** Prefer this loader's jars, except for the JSEnv API sbt already loaded. */
private final class ChildFirstURLClassLoader(urls: Array[URL], parent: ClassLoader)
    extends URLClassLoader(urls, parent):
  override def loadClass(name: String, resolve: Boolean): Class[?] =
    if ChildFirstURLClassLoader.parentFirst(name) then super.loadClass(name, resolve)
    else
      val loaded = findLoadedClass(name)
      val cls    =
        if loaded != null then loaded
        else
          try findClass(name)
          catch case _: ClassNotFoundException => super.loadClass(name, false)
      if resolve then resolveClass(cls)
      cls
end ChildFirstURLClassLoader

private object ChildFirstURLClassLoader:
  private val ParentFirst =
    List("java.", "javax.", "jdk.", "sun.", "scala.", "org.scalajs.jsenv.")

  def parentFirst(name: String): Boolean =
    ParentFirst.exists(name.startsWith)
end ChildFirstURLClassLoader

object ChekhovJsEnvBridge:
  /** Failed run when E2E is disabled (keeps `sbt test` green without browsers). */
  def ignored(reason: String): JSEnv = new JSEnv:
    val name                                                  = s"ChekhovJsEnvBridge(ignored: $reason)"
    def start(input: Seq[Input], runConfig: RunConfig): JSRun =
      JSRun.failed(new RuntimeException(name))
    def startWithCom(input: Seq[Input], runConfig: RunConfig, onMessage: String => Unit): JSComRun =
      JSComRun.failed(new RuntimeException(name))
end ChekhovJsEnvBridge
