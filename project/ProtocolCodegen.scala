package chekhov.protocol

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.jdk.CollectionConverters.*

import org.yaml.snakeyaml.Yaml

/** Generates Scala ADTs + zio-json codecs from Playwright's protocol.yml (MVP slice + shared types). */
object ProtocolCodegen:

  /** Channels whose command inventories feed the coverage gate. */
  val surfaceChannels: List[String] =
    List("Root", "Playwright", "BrowserType", "Browser", "BrowserContext", "Page", "Frame", "Tracing", "Artifact")

  /** Claimed interpreter surface: protocol channel + method → generated case class name.
    *
    * To claim a new method: append a `CommandSpec` here, run `sbt pwCodegen`, wire `PlaywrightDriver` (+ algebra if
    * public). See README "Extending the command allowlist".
    */
  final case class CommandSpec(channel: String, method: String, className: String)

  val commandAllowlist: List[CommandSpec] = List(
    CommandSpec("Root", "initialize", "Initialize"),
    CommandSpec("BrowserType", "launch", "BrowserTypeLaunch"),
    CommandSpec("Browser", "newContext", "BrowserNewContext"),
    CommandSpec("Browser", "close", "BrowserClose"),
    CommandSpec("BrowserContext", "newPage", "BrowserContextNewPage"),
    CommandSpec("BrowserContext", "close", "BrowserContextClose"),
    // Storage cluster (hub / mermoid-class apps)
    CommandSpec("BrowserContext", "storageState", "BrowserContextStorageState"),
    CommandSpec("BrowserContext", "setStorageState", "BrowserContextSetStorageState"),
    CommandSpec("BrowserContext", "cookies", "BrowserContextCookies"),
    CommandSpec("BrowserContext", "addCookies", "BrowserContextAddCookies"),
    CommandSpec("BrowserContext", "clearCookies", "BrowserContextClearCookies"),
    CommandSpec("Frame", "goto", "PageGoto"),
    CommandSpec("Frame", "click", "PageClick"),
    CommandSpec("Frame", "fill", "PageFill"),
    CommandSpec("Frame", "press", "PagePress"),
    CommandSpec("Frame", "innerText", "PageInnerText"),
    CommandSpec("Frame", "textContent", "PageTextContent"),
    CommandSpec("Frame", "title", "PageTitle"),
    CommandSpec("Frame", "evaluateExpression", "FrameEvaluateExpression"),
    CommandSpec("Page", "webStorageItems", "PageWebStorageItems"),
    CommandSpec("Page", "webStorageGetItem", "PageWebStorageGetItem"),
    CommandSpec("Page", "webStorageSetItem", "PageWebStorageSetItem"),
    CommandSpec("Page", "webStorageRemoveItem", "PageWebStorageRemoveItem"),
    CommandSpec("Page", "webStorageClear", "PageWebStorageClear"),
    CommandSpec("Page", "keyboardPress", "PageKeyboardPress"),
    CommandSpec("Page", "screenshot", "PageScreenshot"),
    CommandSpec("Page", "close", "PageClose"),
    // Context tracing + artifact save (traces / video under artifactsDir)
    CommandSpec("Tracing", "tracingStart", "TracingStart"),
    CommandSpec("Tracing", "tracingStartChunk", "TracingStartChunk"),
    CommandSpec("Tracing", "tracingStopChunk", "TracingStopChunk"),
    CommandSpec("Tracing", "tracingStop", "TracingStop"),
    CommandSpec("Artifact", "saveAs", "ArtifactSaveAs"),
  )

  private val sharedObjectNames: Set[String] =
    Set("Point", "Rect", "ViewportSize", "NameValue", "StackFrame")

  private val scalaKeywords: Set[String] =
    Set(
      "abstract",
      "case",
      "catch",
      "class",
      "def",
      "do",
      "else",
      "enum",
      "export",
      "extends",
      "false",
      "final",
      "finally",
      "for",
      "given",
      "if",
      "implicit",
      "import",
      "lazy",
      "match",
      "new",
      "null",
      "object",
      "override",
      "package",
      "private",
      "protected",
      "return",
      "sealed",
      "super",
      "then",
      "this",
      "throw",
      "trait",
      "true",
      "try",
      "type",
      "val",
      "var",
      "while",
      "with",
      "yield",
    )

  def generate(ymlFile: File, outDir: File, playwrightVersion: String): Seq[File] =
    if !ymlFile.exists() then sys.error(s"Missing protocol.yml at ${ymlFile.getAbsolutePath}")
    Files.createDirectories(outDir.toPath)
    val types = loadTypes(ymlFile)

    val files = List(
      write(outDir, "SharedTypes.scala", generateSharedTypes(types)),
      write(outDir, "ProtocolSurface.scala", generateSurface(types)),
      write(outDir, "Envelopes.scala", generateEnvelopes()),
      write(outDir, "Commands.scala", generateCommands(types)),
      write(outDir, "ProtocolMeta.scala", metaSource(playwrightVersion, types.keys.toSeq.sorted)),
    )
    files
  end generate

  /** Regenerate SharedTypes + ProtocolSurface + Commands (allowlist). Leaves Envelopes alone. */
  def generateSharedSurfaceAndCommands(ymlFile: File, outDir: File): Seq[File] =
    if !ymlFile.exists() then sys.error(s"Missing protocol.yml at ${ymlFile.getAbsolutePath}")
    Files.createDirectories(outDir.toPath)
    val types = loadTypes(ymlFile)
    List(
      write(outDir, "SharedTypes.scala", generateSharedTypes(types)),
      write(outDir, "ProtocolSurface.scala", generateSurface(types)),
      write(outDir, "Commands.scala", generateCommands(types)),
    )

  /** @deprecated use [[generateSharedSurfaceAndCommands]] */
  def generateSharedAndSurface(ymlFile: File, outDir: File): Seq[File] =
    generateSharedSurfaceAndCommands(ymlFile, outDir)

  private def loadTypes(ymlFile: File): Map[String, Any] =
    val root = new Yaml().load[java.util.Map[String, Any]](Files.newInputStream(ymlFile.toPath))
    root.asScala.toMap

  /** ProtocolMeta.scala source for a given Playwright pin + definition inventory. */
  def metaSource(playwrightVersion: String, keys: Seq[String]): String =
    s"""package chekhov.protocol.generated
       |
       |/** Inventory of top-level protocol.yml definitions (parity / bump tooling). */
       |object ProtocolMeta:
       |  val playwrightProtocolVersion: String = "$playwrightVersion"
       |  val definitionNames: List[String] = List(
       |${keys.map(k => s"""    "$k"""").mkString(",\n")}
       |  )
       |end ProtocolMeta
       |""".stripMargin

  private def write(outDir: File, name: String, content: String): File =
    val f = new File(outDir, name)
    Files.writeString(f.toPath, content, StandardCharsets.UTF_8)
    f

  private def generateSurface(types: Map[String, Any]): String =
    val entries = surfaceChannels.flatMap { name =>
      commandNames(types, name) match
        case Nil  => None
        case cmds =>
          val lit = cmds.map(c => s""""$c"""").mkString(", ")
          Some(s"""    "$name" -> Set($lit)""")
    }
    s"""package chekhov.protocol.generated
       |
       |/** Channel command inventories extracted from protocol.yml (coverage / bump parity). */
       |object ProtocolSurface:
       |  val channels: List[String] = List(
       |${surfaceChannels.map(c => s"""    "$c"""").mkString(",\n")}
       |  )
       |
       |  val commands: Map[String, Set[String]] = Map(
       |${entries.mkString(",\n")}
       |  )
       |
       |  def has(channel: String, method: String): Boolean =
       |    commands.getOrElse(channel, Set.empty).contains(method)
       |end ProtocolSurface
       |""".stripMargin
  end generateSurface

  private def commandNames(types: Map[String, Any], typeName: String): List[String] =
    types.get(typeName) match
      case Some(m: java.util.Map[?, ?]) =>
        val map = m.asScala.toMap.asInstanceOf[Map[String, Any]]
        map.get("commands") match
          case Some(c: java.util.Map[?, ?]) =>
            c.asScala.keys.map(_.toString).toList.sorted
          case _ => Nil
      case _ => Nil

  private def generateEnvelopes(): String =
    """package chekhov.protocol.generated
      |
      |import zio.json.*
      |
      |/** Wire RPC envelopes for the Playwright channel protocol. */
      |final case class ClientRequest(
      |    id: Int,
      |    guid: String,
      |    method: String,
      |    params: Option[Json] = None,
      |    metadata: Option[Json] = None,
      |) derives JsonCodec
      |
      |final case class ServerResponse(
      |    id: Int,
      |    result: Option[Json] = None,
      |    error: Option[Json] = None,
      |) derives JsonCodec
      |
      |final case class ServerEvent(
      |    guid: String,
      |    method: String,
      |    params: Option[Json] = None,
      |) derives JsonCodec
      |
      |/** Discriminated inbound message (response or event). */
      |enum InboundMessage:
      |  case Response(value: ServerResponse)
      |  case Event(value: ServerEvent)
      |
      |object InboundMessage:
      |  given JsonDecoder[InboundMessage] = JsonDecoder[Json].mapOrFail { json =>
      |    json.asObject match
      |      case Some(obj) if obj.contains("id") =>
      |        json.as[ServerResponse].map(Response.apply)
      |      case Some(obj) if obj.contains("method") =>
      |        json.as[ServerEvent].map(Event.apply)
      |      case _ =>
      |        Left(s"Unrecognized inbound message: $json")
      |  }
      |end InboundMessage
      |""".stripMargin

  private def generateCommands(types: Map[String, Any]): String =
    val allowlistLit = commandAllowlist
      .map(s => s"""    ("${s.channel}", "${s.method}", "${s.className}")""")
      .mkString(",\n")

    val bodies = commandAllowlist.map(spec => renderCommand(types, spec)).mkString("\n")

    s"""package chekhov.protocol.generated
       |
       |import zio.json.*
       |import zio.json.ast.Json
       |
       |/** Typed channel command params for the Chekhov interpreter.
       |  *
       |  * Generated from protocol.yml via ProtocolCodegen.commandAllowlist.
       |  * Do not edit field lists by hand; run `sbt pwCodegen` / `pwBump`.
       |  */
       |object Commands:
       |
       |  /** (channel, method, caseClassName) covered by this file. */
       |  val allowlist: List[(String, String, String)] = List(
       |$allowlistLit
       |  )
       |
       |$bodies
       |  private def emptyCodec[A](value: A): JsonCodec[A] =
       |    JsonCodec(
       |      JsonEncoder[Json].contramap(_ => Json.Obj()),
       |      JsonDecoder[Json].map(_ => value),
       |    )
       |
       |end Commands
       |""".stripMargin
  end generateCommands

  private def renderCommand(types: Map[String, Any], spec: CommandSpec): String =
    val params = methodParameters(types, spec.channel, spec.method)
    val fields = flattenParams(types, params)
    if fields.isEmpty then s"""  final case class ${spec.className}()
         |
         |  object ${spec.className}:
         |    given JsonCodec[${spec.className}] = emptyCodec(${spec.className}())
         |
         |""".stripMargin
    else
      val required = fields.filterNot(_._2)
      val optional = fields.filter(_._2)
      val ordered  = required ++ optional
      val lines    = ordered.map { case (name, opt, tpe) =>
        val id = scalaIdent(name)
        if opt then s"      $id: Option[$tpe] = None"
        else s"      $id: $tpe"
      }
      s"""  final case class ${spec.className}(
         |${lines.mkString(",\n")}
         |  ) derives JsonCodec
         |
         |""".stripMargin
    end if
  end renderCommand

  /** Raw parameter map for a channel method (may include `$mixin`). */
  private def methodParameters(
      types: Map[String, Any],
      channel: String,
      method: String,
  ): Map[String, Any] =
    types.get(channel) match
      case Some(m: java.util.Map[?, ?]) =>
        val map = m.asScala.toMap.asInstanceOf[Map[String, Any]]
        map.get("commands") match
          case Some(cmds: java.util.Map[?, ?]) =>
            val byName = cmds.asScala.map { case (k, v) => k.toString -> v }.toMap
            byName.get(method) match
              case Some(cmd: java.util.Map[?, ?]) =>
                val cmdMap = cmd.asScala.toMap.asInstanceOf[Map[String, Any]]
                cmdMap.get("parameters") match
                  case Some(p: java.util.Map[?, ?]) =>
                    p.asScala.toMap.asInstanceOf[Map[String, Any]]
                  case _ => Map.empty
              case _ =>
                sys.error(s"protocol.yml missing $channel.$method")
          case _ =>
            sys.error(s"protocol.yml $channel has no commands")
        end match
      case _ =>
        sys.error(s"protocol.yml missing channel $channel")

  /** Flatten `$mixin` refs into (name, optional, scalaType). */
  private def flattenParams(
      types: Map[String, Any],
      params: Map[String, Any],
  ): List[(String, Boolean, String)] =
    def fromProps(props: Map[String, Any]): List[(String, Boolean, String)] =
      props.toList.flatMap {
        case ("$mixin", mixinName: String) =>
          propertiesOf(types, mixinName) match
            case Some(mprops) => fromProps(mprops)
            case None         => sys.error(s"Unknown mixin $mixinName")
        case ("$mixin", other) =>
          sys.error(s"Invalid $$mixin value: $other")
        case (name, fdef) =>
          val (tpe, opt) = scalaType(types, fdef)
          List((name, opt, tpe))
      }

    // Stable-ish order: declaration order from YAML maps is not guaranteed; sort required then optional by name.
    val flat = fromProps(params)
    val req  = flat.filterNot(_._2).sortBy(_._1)
    val opt  = flat.filter(_._2).sortBy(_._1)
    req ++ opt
  end flattenParams

  private def propertiesOf(types: Map[String, Any], typeName: String): Option[Map[String, Any]] =
    types.get(typeName).collect { case m: java.util.Map[?, ?] =>
      val map = m.asScala.toMap.asInstanceOf[Map[String, Any]]
      map.get("properties") match
        case Some(p: java.util.Map[?, ?]) =>
          p.asScala.toMap.asInstanceOf[Map[String, Any]]
        case _ => Map.empty
    }

  private def scalaIdent(name: String): String =
    if scalaKeywords.contains(name) then s"`$name`" else name

  private def generateSharedTypes(types: Map[String, Any]): String =
    val wanted = List("Point", "Rect", "ViewportSize", "NameValue", "StackFrame")
    val buf    = new StringBuilder
    buf ++= """package chekhov.protocol.generated
              |
              |import zio.json.*
              |
              |""".stripMargin

    wanted.foreach { name =>
      types.get(name).foreach {
        case m: java.util.Map[?, ?] =>
          val map = m.asScala.toMap.asInstanceOf[Map[String, Any]]
          map.get("type") match
            case Some("object") =>
              val props = map
                .get("properties")
                .collect { case p: java.util.Map[?, ?] => p.asScala.toMap.asInstanceOf[Map[String, Any]] }
                .getOrElse(Map.empty)
              val fields = props.toList.map { case (fname, fdef) =>
                val (tpe, opt) = scalaType(types, fdef)
                val id         = scalaIdent(fname)
                if opt then s"    $id: Option[$tpe] = None"
                else s"    $id: $tpe"
              }
              buf ++= s"final case class $name(\n"
              buf ++= fields.mkString(",\n")
              buf ++= s"\n) derives JsonCodec\n\n"
            case _ => ()
          end match
        case _ => ()
      }
    }

    if !types.contains("ViewportSize") then buf ++= """final case class ViewportSize(
                |    width: Double,
                |    height: Double,
                |) derives JsonCodec
                |
                |""".stripMargin

    if !types.contains("NameValue") then buf ++= """final case class NameValue(
                |    name: String,
                |    value: String,
                |) derives JsonCodec
                |
                |""".stripMargin

    buf.toString
  end generateSharedTypes

  private def scalaType(types: Map[String, Any], fdef: Any): (String, Boolean) =
    fdef match
      case s: String =>
        val opt = s.endsWith("?")
        val raw = if opt then s.dropRight(1) else s
        (mapNamed(types, raw), opt)
      case m: java.util.Map[?, ?] =>
        val map = m.asScala.toMap.asInstanceOf[Map[String, Any]]
        map.get("type") match
          case Some(t: String) if t.endsWith("?") =>
            val base = t.dropRight(1)
            val tpe  = base match
              case "number" | "float" | "int"           => "Double"
              case "string"                             => "String"
              case "boolean"                            => "Boolean"
              case "object" | "array" | "json" | "enum" =>
                if base == "enum" then "String" else "Json"
              case other => mapNamed(types, other)
            (tpe, true)
          case Some("number") | Some("float") | Some("int") => ("Double", false)
          case Some("string")                               => ("String", false)
          case Some("boolean")                              => ("Boolean", false)
          case Some("object")                               => ("Json", false)
          case Some("array")                                => ("Json", false)
          case Some("enum")                                 => ("String", false)
          case Some("json")                                 => ("Json", false)
          case Some(other: String)                          => (mapNamed(types, other), false)
          case Some(_)                                      => ("Json", false)
          case None                                         =>
            ("Json", true)
        end match
      case _ => ("Json", true)

  private def mapNamed(types: Map[String, Any], raw: String): String =
    raw match
      case "string" | "binary"                                          => "String"
      case "number" | "float" | "int"                                   => "Double"
      case "boolean"                                                    => "Boolean"
      case "json"                                                       => "Json"
      case "SerializedArgument" | "SerializedValue" | "SerializedError" => "Json"
      case other                                                        =>
        types.get(other) match
          case Some(m: java.util.Map[?, ?]) =>
            val t = m.asScala.toMap.get("type")
            t match
              case Some("enum")                                   => "String"
              case Some("object") if sharedObjectNames(other)     => other
              case Some("object") | Some("mixin") | Some("array") => "Json"
              case Some("interface")                              => "Json"
              case _                                              => "Json"
          case _ => "Json"
end ProtocolCodegen
