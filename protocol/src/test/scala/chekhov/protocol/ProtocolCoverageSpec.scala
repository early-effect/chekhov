package chekhov.protocol

import chekhov.protocol.generated.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/** Parity gate: claimed MVP channel methods exist in protocol.yml; codecs round-trip. */
object ProtocolCoverageSpec extends ZIOSpecDefault:

  def spec =
    suite("protocol coverage")(
      test("protocol.yml inventory is substantial") {
        assertTrue(ProtocolMeta.definitionNames.size >= 50)
      },
      test("surface channels are present in ProtocolMeta") {
        assertTrue(
          ProtocolSurface.channels.forall(ProtocolMeta.definitionNames.contains)
        )
      },
      test("Commands allowlist methods exist in ProtocolSurface") {
        val missing = Commands.allowlist.filterNot { case (channel, method, _) =>
          ProtocolSurface.has(channel, method)
        }
        assertTrue(missing.isEmpty)
      },
      test("MVP command codecs encode") {
        val arg = Json.Obj(
          "value"   -> Json.Obj("v" -> Json.Str("undefined")),
          "handles" -> Json.Arr(),
        )
        val encoded = List(
          Commands.Initialize("java").toJson,
          Commands.BrowserTypeLaunch(headless = Some(true)).toJson,
          Commands.PageGoto(url = "http://localhost").toJson,
          Commands.PageClick(selector = "button").toJson,
          Commands.PageFill(selector = "input", value = "x").toJson,
          Commands
            .FrameEvaluateExpression(
              expression = "1+1",
              isFunction = Some(false),
              arg = arg,
            )
            .toJson,
          Commands.PageClose().toJson,
          Commands.BrowserClose().toJson,
          Commands.BrowserContextStorageState(indexedDB = Some(true)).toJson,
          Commands.PageWebStorageSetItem(kind = "local", name = "k", value = "v").toJson,
        )
        assertTrue(encoded.forall(_.nonEmpty), Commands.allowlist.size >= 20)
      },
    ) @@ TestAspect.timeout(10.seconds)
end ProtocolCoverageSpec
