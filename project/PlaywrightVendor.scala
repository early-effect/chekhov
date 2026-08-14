package chekhov.protocol

import java.io.File
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration

import scala.jdk.CollectionConverters.*

import org.yaml.snakeyaml.Yaml

/** Vendors Playwright's channel protocol into the Chekhov tree and keeps npm pin / ProtocolMeta in sync. */
object PlaywrightVendor:

  final case class Paths(
      base: File,
      protocolYml: File,
      protocolMeta: File,
      packageJson: File,
  )

  def defaultPaths(base: File): Paths =
    Paths(
      base = base,
      protocolYml = new File(base, "protocol/src/main/resources/playwright/protocol.yml"),
      protocolMeta = new File(base, "protocol/src/main/scala/chekhov/protocol/generated/ProtocolMeta.scala"),
      packageJson = new File(base, "package.json"),
    )

  /** Resolve the newest stable Playwright release from the npm registry. */
  def latestNpmVersion(): String =
    val body = httpGetString("https://registry.npmjs.org/playwright/latest")
    val m    = "\"version\"\\s*:\\s*\"([^\"]+)\"".r.findFirstMatchIn(body)
    m.map(_.group(1)).getOrElse(sys.error(s"Could not parse playwright latest from npm: $body"))

  /** Read the pinned version from package.json (`devDependencies.playwright`). */
  def readPackagePin(packageJson: File): String =
    val text = Files.readString(packageJson.toPath, StandardCharsets.UTF_8)
    val m    = "\"playwright\"\\s*:\\s*\"\\^?([0-9]+\\.[0-9]+\\.[0-9]+[^\"]*)\"".r.findFirstMatchIn(text)
    m.map(_.group(1)).getOrElse(sys.error(s"No playwright pin in ${packageJson.getPath}"))

  /** Full bump:
    *   1. pin `playwright` in package.json + `npm install`
    *   2. vendor merged `protocol.yml` from the matching GitHub tag
    *   3. regenerate `ProtocolMeta` (version + definition inventory)
    *   4. regenerate `SharedTypes` + `ProtocolSurface` + allowlist `Commands` from the YAML
    *
    * Envelopes stay hand-stable unless the wire shape changes.
    */
  def bump(version: String, paths: Paths, installBrowsers: Boolean = false): Unit =
    val v = normalizeVersion(version)
    println(s"[chekhov] bumping Playwright → $v")
    writePackagePin(paths.packageJson, v)
    run(paths.base, "npm", "install")
    vendorProtocol(v, paths.protocolYml)
    writeProtocolMeta(v, paths.protocolYml, paths.protocolMeta)
    writeGeneratedFromYaml(paths.protocolYml, paths.protocolMeta.getParentFile)
    if installBrowsers then run(paths.base, "bash", "./scripts/install-browsers.sh")
    println(s"[chekhov] Playwright $v vendored. Review protocol diffs, then run tests.")
  end bump

  /** Download + merge protocol YAML for `version` into `dest`. */
  def vendorProtocol(version: String, dest: File): Unit =
    val v    = normalizeVersion(version)
    val tag  = s"v$v"
    val body =
      fetchMergedSpecYaml(tag).orElse(fetchLegacyProtocolYaml(tag)) match
        case Some(yaml) => yaml
        case None       =>
          sys.error(
            s"Could not vendor protocol for $tag. Tried packages/protocol/spec/*.yml and packages/protocol/src/protocol.yml."
          )
    val header =
      s"""|# Vendored from microsoft/playwright $tag (see sbt playwrightBump).
         |# Do not edit by hand; re-vendor when bumping Playwright.
         |
         |""".stripMargin
    Files.createDirectories(dest.toPath.getParent)
    Files.writeString(dest.toPath, header + body, StandardCharsets.UTF_8)
    println(s"[chekhov] wrote ${dest.getPath} (${body.linesIterator.size} lines of protocol)")
  end vendorProtocol

  /** Regenerate ProtocolMeta only from the on-disk protocol.yml. */
  def writeProtocolMeta(version: String, yml: File, out: File): Unit =
    if !yml.exists() then sys.error(s"Missing ${yml.getAbsolutePath}")
    val root = new Yaml().load[java.util.Map[String, Any]](Files.newInputStream(yml.toPath))
    val keys = root.asScala.keys.toSeq.sorted
    val v    = normalizeVersion(version)
    val text = ProtocolCodegen.metaSource(v, keys)
    Files.createDirectories(out.toPath.getParent)
    Files.writeString(out.toPath, text, StandardCharsets.UTF_8)
    println(s"[chekhov] wrote ${out.getPath} (${keys.size} definitions, version=$v)")

  /** Regenerate SharedTypes + ProtocolSurface + allowlist Commands from on-disk protocol.yml. */
  def writeGeneratedFromYaml(yml: File, generatedDir: File): Unit =
    val files = ProtocolCodegen.generateSharedSurfaceAndCommands(yml, generatedDir)
    files.foreach(f => println(s"[chekhov] wrote ${f.getPath}"))

  /** @deprecated use [[writeGeneratedFromYaml]] */
  def writeSharedAndSurface(yml: File, generatedDir: File): Unit =
    writeGeneratedFromYaml(yml, generatedDir)

  private def fetchMergedSpecYaml(tag: String): Option[String] =
    val api = s"https://api.github.com/repos/microsoft/playwright/contents/packages/protocol/spec?ref=$tag"
    try
      val listing = httpGetString(api)
      val names   =
        "\"name\"\\s*:\\s*\"([^\"]+\\.yml)\"".r
          .findAllMatchIn(listing)
          .map(_.group(1))
          .toList
          .distinct
          .sorted
      if names.isEmpty then None
      else
        val parts = names.map { name =>
          val url =
            s"https://raw.githubusercontent.com/microsoft/playwright/$tag/packages/protocol/spec/$name"
          ensureTrailingNewline(httpGetString(url))
        }
        Some(parts.mkString("\n"))
    catch case _: Throwable => None
    end try
  end fetchMergedSpecYaml

  private def fetchLegacyProtocolYaml(tag: String): Option[String] =
    val url =
      s"https://raw.githubusercontent.com/microsoft/playwright/$tag/packages/protocol/src/protocol.yml"
    try Some(httpGetString(url))
    catch case _: Throwable => None

  private def writePackagePin(packageJson: File, version: String): Unit =
    val path    = packageJson.toPath
    val text    = Files.readString(path, StandardCharsets.UTF_8)
    val updated =
      if text.contains("\"playwright\"") then
        "\"playwright\"\\s*:\\s*\"[^\"]+\"".r.replaceAllIn(text, s""""playwright": "$version"""")
      else sys.error(s"package.json missing playwright dependency: ${packageJson.getPath}")
    Files.writeString(path, updated, StandardCharsets.UTF_8)
    println(s"[chekhov] pinned playwright@$version in package.json")

  private def normalizeVersion(version: String): String =
    version.trim.stripPrefix("v")

  private def ensureTrailingNewline(s: String): String =
    if s.endsWith("\n") then s else s + "\n"

  private def httpGetString(url: String): String =
    val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build()
    val req    = HttpRequest
      .newBuilder(URI.create(url))
      .timeout(Duration.ofMinutes(2))
      .header("User-Agent", "chekhov-playwright-vendor")
      .header("Accept", "application/vnd.github+json, application/json, text/plain, */*")
      .GET()
      .build()
    val resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    if resp.statusCode() / 100 != 2 then sys.error(s"HTTP ${resp.statusCode()} for $url")
    resp.body()
  end httpGetString

  private def run(cwd: File, cmd: String*): Unit =
    val pb = new ProcessBuilder(cmd*)
    pb.directory(cwd)
    pb.inheritIO()
    val code = pb.start().waitFor()
    if code != 0 then sys.error(s"Command failed ($code): ${cmd.mkString(" ")}")

end PlaywrightVendor
