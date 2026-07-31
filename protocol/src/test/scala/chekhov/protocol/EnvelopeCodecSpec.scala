package chekhov.protocol

import chekhov.protocol.generated.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

object EnvelopeCodecSpec extends ZIOSpecDefault:
  def spec =
    suite("protocol envelopes")(
      test("ClientRequest round-trips") {
        val req = ClientRequest(
          id = 1,
          guid = "",
          method = "initialize",
          params = Some(Json.Obj("sdkLanguage" -> Json.Str("java"))),
        )
        val json = req.toJson
        assertTrue(json.fromJson[ClientRequest].isRight)
      },
      test("ProtocolMeta matches package.json pin") {
        val pkg = java.nio.file.Files.readString(java.nio.file.Path.of("package.json"))
        val pin = """"playwright"\s*:\s*"([^"]+)"""".r
          .findFirstMatchIn(pkg)
          .map(_.group(1).stripPrefix("^"))
          .getOrElse("")
        assertTrue(
          ProtocolMeta.definitionNames.size >= 50,
          ProtocolMeta.playwrightProtocolVersion == pin,
        )
      },
      test("Commands.PageGoto encodes") {
        val g = Commands.PageGoto(url = "http://example.com")
        assertTrue(g.toJson.contains("example.com"))
      },
    ) @@ TestAspect.timeout(10.seconds)
end EnvelopeCodecSpec
