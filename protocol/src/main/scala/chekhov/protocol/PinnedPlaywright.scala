package chekhov.protocol

import chekhov.ChekhovBrowser
import chekhov.protocol.generated.ProtocolMeta
import zio.json.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex

/** The Playwright CLI this Chekhov was built for: resolve the pin, refuse skew, install on demand.
  *
  * Consumers should not hunt `node_modules` or leftover `npx` CLIs. `sbt chekhovInstall` extracts the pinned
  * `playwright` package into the Chekhov cache and installs matching browser revisions.
  */
object PinnedPlaywright:

  val version: String = ProtocolMeta.playwrightProtocolVersion

  val defaultInstallBrowsers: List[ChekhovBrowser] = ChekhovBrowser.values.toList

  final case class Lookup(
      env: Map[String, String],
      cwd: Path,
      userHome: Path,
  )

  object Lookup:
    def system: Lookup =
      Lookup(
        env = sys.env,
        cwd = Path.of("").toAbsolutePath.normalize,
        userHome = Path.of(sys.props.getOrElse("user.home", ".")),
      )

  final case class Driver(node: String, cli: Path, packageVersion: String)

  enum Problem:
    case Missing
    case MissingOverride(path: Path)
    case Skew(foundVersion: String, path: Path)
    case Unreadable(path: Path)
    case BrowserMissing(browser: ChekhovBrowser, revision: String, found: List[String])

    def message: String = this match
      case Missing =>
        s"chekhov: Playwright $version CLI not found. Run `sbt chekhovInstall` (or set PLAYWRIGHT_DRIVER_CLI to that version's cli.js)."
      case MissingOverride(path) =>
        s"chekhov: PLAYWRIGHT_DRIVER_CLI is $path, which is not a file. Point it at Playwright $version cli.js or run `sbt chekhovInstall`."
      case Skew(found, path) =>
        s"chekhov: driver is Playwright $found at $path; this Chekhov was built for $version. Unset PLAYWRIGHT_DRIVER_CLI or run `sbt chekhovInstall`."
      case Unreadable(path) =>
        s"chekhov: could not read a Playwright version from $path; this Chekhov was built for $version. Run `sbt chekhovInstall`."
      case BrowserMissing(browser, revision, found) =>
        val title       = browser.toString
        val foundClause =
          if found.isEmpty then ""
          else
            val joined = found.mkString(", ")
            val verb   = if found.tail.isEmpty then "belongs" else "belong"
            s" Found $joined, which $verb to a different Playwright."
        s"chekhov: $title revision $revision is not installed (Playwright $version). Run `sbt chekhovInstall`.$foundClause"
  end Problem

  /** Node executable + pinned `cli.js`, or a message that names the pin. */
  def resolve(lookup: Lookup = Lookup.system): Either[Problem, Driver] =
    val node = lookup.env.getOrElse("PLAYWRIGHT_NODEJS_PATH", "node")
    lookup.env.get("PLAYWRIGHT_DRIVER_CLI") match
      case Some(raw) =>
        val cli = Path.of(raw)
        if !Files.isRegularFile(cli) then Left(Problem.MissingOverride(cli))
        else inspect(cli).flatMap(accept(cli)).map(ver => Driver(node, cli.toAbsolutePath.normalize, ver))
      case None =>
        val existing =
          (cliInCache(lookup) :: cwdCliCandidates(lookup.cwd)).distinct.filter(Files.isRegularFile(_))
        val (firstProblem, found) =
          existing.foldLeft[(Option[Problem], Option[Driver])]((None, None)) {
            case ((prob, some @ Some(_)), _) => (prob, some)
            case ((prob, None), cli)         =>
              inspect(cli).flatMap(accept(cli)) match
                case Right(ver) =>
                  (prob, Some(Driver(node, cli.toAbsolutePath.normalize, ver)))
                case Left(problem) =>
                  (prob.orElse(Some(problem)), None)
          }
        found.map(Right(_)).getOrElse(Left(firstProblem.getOrElse(Problem.Missing)))
    end match
  end resolve

  /** Fail if the pin's browser revision is not in the Playwright cache. */
  def requireBrowser(browser: ChekhovBrowser, lookup: Lookup = Lookup.system): Either[Problem, Unit] =
    resolve(lookup).flatMap { driver =>
      val name    = browser.channelName
      val checked =
        for
          json <- browsersJsonNear(driver.cli)
          pin  <- readBrowserRevision(json, name)
        yield
          val root = browsersRoot(lookup)
          if hasRevision(root, name, pin) then Right(())
          else Left(Problem.BrowserMissing(browser, pin.revision, siblingDirs(root, name)))
      checked.getOrElse(Right(()))
    }

  /** Extract `playwright@[[version]]` into the Chekhov cache and install browsers for that pin. */
  def install(
      lookup: Lookup = Lookup.system,
      browsers: List[ChekhovBrowser] = defaultInstallBrowsers,
      log: String => Unit = println,
  ): Either[String, Path] =
    if browsers.isEmpty then
      Left("chekhov: no browsers to install. Set chekhovBrowsers to at least one ChekhovBrowser.")
    else
      val node = lookup.env.getOrElse("PLAYWRIGHT_NODEJS_PATH", "node")
      for
        _   <- ensureCommand(node, "Node.js")
        _   <- ensureCommand("npm", "npm")
        cli <- ensurePinnedCli(lookup, log)
        _   <- installBrowsers(lookup, node, cli, browsers, log)
      yield cli
  end install

  def cacheRoot(lookup: Lookup = Lookup.system): Path =
    lookup.env.get("CHEKHOV_CACHE") match
      case Some(p) if p.nonEmpty => Path.of(p)
      case _                     => osCache(lookup).resolve("chekhov")

  def packageDir(lookup: Lookup = Lookup.system): Path =
    cacheRoot(lookup).resolve("playwright").resolve(version)

  def cliInCache(lookup: Lookup = Lookup.system): Path =
    packageDir(lookup).resolve("node_modules").resolve("playwright").resolve("cli.js")

  /** Playwright CLI package names for `install` / `install-deps`. No extra engines, no ffmpeg. */
  def installPackageNames(browsers: List[ChekhovBrowser]): List[String] =
    browsers.map(_.channelName)

  def browsersRoot(lookup: Lookup = Lookup.system): Path =
    lookup.env.get("PLAYWRIGHT_BROWSERS_PATH") match
      case Some(p) if p == "0" || p.contains("cursor-sandbox-cache") =>
        osCache(lookup).resolve("ms-playwright")
      case Some(p) if p.nonEmpty =>
        val path = Path.of(p)
        if path.isAbsolute then path else lookup.cwd.resolve(path).normalize
      case _ =>
        osCache(lookup).resolve("ms-playwright")

  private def osCache(lookup: Lookup): Path =
    sys.props.getOrElse("os.name", "").toLowerCase match
      case os if os.contains("mac") =>
        lookup.userHome.resolve("Library").resolve("Caches")
      case os if os.contains("win") =>
        lookup.env
          .get("LOCALAPPDATA")
          .filter(_.nonEmpty)
          .map(Path.of(_))
          .getOrElse(lookup.userHome.resolve("AppData").resolve("Local"))
      case _ =>
        lookup.env
          .get("XDG_CACHE_HOME")
          .filter(_.nonEmpty)
          .map(Path.of(_))
          .getOrElse(lookup.userHome.resolve(".cache"))

  private def cwdCliCandidates(cwd: Path): List[Path] =
    Iterator
      .iterate(cwd)(_.getParent)
      .takeWhile(_ != null)
      .take(8)
      .flatMap { dir =>
        val pw = dir.resolve("node_modules").resolve("playwright")
        List(pw.resolve("cli.js"), pw.resolve("lib").resolve("cli").resolve("cli.js"))
      }
      .toList

  private def inspect(cli: Path): Either[Problem, String] =
    packageJsonNear(cli) match
      case None      => Left(Problem.Unreadable(cli.toAbsolutePath.normalize))
      case Some(pkg) =>
        readNamedVersion(pkg) match
          case Some(ver) => Right(ver)
          case None      => Left(Problem.Unreadable(pkg.toAbsolutePath.normalize))

  private def accept(cli: Path)(found: String): Either[Problem, String] =
    if found == version then Right(found)
    else Left(Problem.Skew(found, cli.toAbsolutePath.normalize))

  private def packageJsonNear(cli: Path): Option[Path] =
    Iterator
      .iterate(cli.getParent)(p => if p == null then null else p.getParent)
      .takeWhile(_ != null)
      .take(6)
      .map(_.resolve("package.json"))
      .find(p => Files.isRegularFile(p) && isPlaywrightPackage(p))

  private val nameField    = """"name"\s*:\s*"([^"]+)"""".r
  private val versionField = """"version"\s*:\s*"([^"]+)"""".r

  private def isPlaywrightPackage(pkg: Path): Boolean =
    readField(pkg, nameField).exists(n => n == "playwright" || n == "playwright-core")

  private def readNamedVersion(pkg: Path): Option[String] =
    if isPlaywrightPackage(pkg) then readField(pkg, versionField) else None

  private def readField(pkg: Path, re: Regex): Option[String] =
    val text = Files.readString(pkg, StandardCharsets.UTF_8)
    re.findFirstMatchIn(text).map(_.group(1))

  private def browsersJsonNear(cli: Path): Option[Path] =
    Iterator
      .iterate(cli.getParent)(p => if p == null then null else p.getParent)
      .takeWhile(_ != null)
      .take(6)
      .map(_.resolve("playwright-core").resolve("browsers.json"))
      .find(Files.isRegularFile(_))
      .orElse {
        Option(cli.getParent).map(_.resolve("browsers.json")).filter(Files.isRegularFile(_))
      }

  private final case class BrowserEntry(
      name: String,
      revision: String,
      revisionOverrides: Option[Map[String, String]] = None,
  )
  private final case class BrowsersFile(browsers: List[BrowserEntry])
  private given JsonDecoder[BrowserEntry] = DeriveJsonDecoder.gen
  private given JsonDecoder[BrowsersFile] = DeriveJsonDecoder.gen

  private final case class BrowserPin(revision: String, overrideRevisions: List[String])

  private def readBrowserRevision(json: Path, browser: String): Option[BrowserPin] =
    val text = Files.readString(json, StandardCharsets.UTF_8)
    text
      .fromJson[BrowsersFile]
      .toOption
      .flatMap(_.browsers.find(_.name == browser))
      .map { e =>
        BrowserPin(e.revision, e.revisionOverrides.fold(List.empty[String])(_.values.toList))
      }

  private def hasRevision(root: Path, browser: String, pin: BrowserPin): Boolean =
    if !Files.isDirectory(root) then false
    else listDirNames(root).exists(isExpectedDir(_, browser, pin))

  private def isExpectedDir(name: String, browser: String, pin: BrowserPin): Boolean =
    val prefix = browser.replace('-', '_')
    name == s"$prefix-${pin.revision}" ||
    pin.overrideRevisions.exists(r => name.startsWith(prefix + "_") && name.endsWith("_special-" + r))

  private def siblingDirs(root: Path, browser: String): List[String] =
    if !Files.isDirectory(root) then Nil
    else
      val prefix = browser.replace('-', '_')
      listDirNames(root)
        .filter(name => name.startsWith(prefix + "-") || name.startsWith(prefix + "_"))
        .sorted

  private def listDirNames(root: Path): List[String] =
    val stream = Files.list(root)
    try
      stream.iterator().asScala.filter(Files.isDirectory(_)).map(_.getFileName.toString).toList
    finally stream.close()

  private def ensurePinnedCli(lookup: Lookup, log: String => Unit): Either[String, Path] =
    resolve(lookup) match
      case Right(driver) =>
        log(s"chekhov: using Playwright $version CLI at ${driver.cli}")
        Right(driver.cli)
      case Left(_: Problem.Skew) | Left(_: Problem.Unreadable) | Left(Problem.Missing) |
          Left(_: Problem.MissingOverride) =>
        downloadPinned(lookup, log)
      case Left(other) =>
        Left(other.message)

  private def downloadPinned(lookup: Lookup, log: String => Unit): Either[String, Path] =
    val dest = packageDir(lookup)
    val cli  = cliInCache(lookup)
    val pkg  = dest.resolve("node_modules").resolve("playwright").resolve("package.json")
    if Files.isRegularFile(cli) && readNamedVersion(pkg).contains(version) then
      log(s"chekhov: Playwright $version already in $dest")
      Right(cli)
    else
      Files.createDirectories(dest)
      log(s"chekhov: installing playwright@$version into $dest")
      run(
        dest,
        log,
        "npm",
        "install",
        "--no-fund",
        "--no-audit",
        "--no-progress",
        s"playwright@$version",
      ).map(_ => cli).flatMap { installed =>
        if Files.isRegularFile(installed) then Right(installed)
        else Left(s"chekhov: npm install playwright@$version did not produce $installed")
      }
    end if
  end downloadPinned

  private def installBrowsers(
      lookup: Lookup,
      node: String,
      cli: Path,
      browsers: List[ChekhovBrowser],
      log: String => Unit,
  ): Either[String, Unit] =
    val names   = installPackageNames(browsers)
    val env     = sanitizedBrowserEnv(lookup)
    val linuxCi =
      sys.props.getOrElse("os.name", "").toLowerCase.contains("linux") &&
        (lookup.env.get("CI").contains("true") || lookup.env.get("GITHUB_ACTIONS").contains("true"))
    val deps =
      if linuxCi then run(lookup.cwd, log, env, node :: cli.toString :: "install-deps" :: names)
      else Right(())
    deps.flatMap { _ =>
      log(s"chekhov: installing ${names.mkString(", ")} for Playwright $version")
      run(lookup.cwd, log, env, node :: cli.toString :: "install" :: names)
    }
  end installBrowsers

  private def sanitizedBrowserEnv(lookup: Lookup): Map[String, String] =
    lookup.env.get("PLAYWRIGHT_BROWSERS_PATH") match
      case Some(p) if p == "0" || p.contains("cursor-sandbox-cache") =>
        lookup.env - "PLAYWRIGHT_BROWSERS_PATH" - "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD"
      case _ =>
        lookup.env - "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD"

  private def ensureCommand(name: String, label: String): Either[String, Unit] =
    val pb = new ProcessBuilder(name, "--version")
    pb.redirectErrorStream(true)
    try
      val p    = pb.start()
      val _    = p.getInputStream.readAllBytes()
      val code = p.waitFor()
      if code == 0 then Right(())
      else
        Left(
          s"chekhov: $label is required to install Playwright $version. Install Node 20+ and run `sbt chekhovInstall`."
        )
    catch
      case _: Exception =>
        Left(
          s"chekhov: $label is required to install Playwright $version. Install Node 20+ and run `sbt chekhovInstall`."
        )
    end try
  end ensureCommand

  private def run(cwd: Path, log: String => Unit, cmd: String*): Either[String, Unit] =
    run(cwd, log, sys.env, cmd.toList)

  private def run(
      cwd: Path,
      log: String => Unit,
      env: Map[String, String],
      cmd: List[String],
  ): Either[String, Unit] =
    val pb = new ProcessBuilder(cmd*)
    pb.directory(cwd.toFile)
    pb.redirectErrorStream(true)
    pb.environment().clear()
    env.foreach { case (k, v) => pb.environment().put(k, v) }
    try
      val p   = pb.start()
      val out = new String(p.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      if out.nonEmpty then out.linesIterator.foreach(log)
      val code = p.waitFor()
      if code == 0 then Right(())
      else Left(s"chekhov: command failed ($code): ${cmd.mkString(" ")}")
    catch
      case e: Exception =>
        Left(s"chekhov: failed to run ${cmd.mkString(" ")}: ${e.getMessage}")
    end try
  end run

end PinnedPlaywright
