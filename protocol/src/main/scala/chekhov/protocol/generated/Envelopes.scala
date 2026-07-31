package chekhov.protocol.generated

import zio.json.*
import zio.json.ast.Json

/** Wire RPC envelopes for the Playwright channel protocol. */
final case class ClientRequest(
    id: Int,
    guid: String,
    method: String,
    params: Option[Json] = None,
    metadata: Option[Json] = None,
) derives JsonCodec

final case class ServerResponse(
    id: Int,
    result: Option[Json] = None,
    error: Option[Json] = None,
) derives JsonCodec

final case class ServerEvent(
    guid: String,
    method: String,
    params: Option[Json] = None,
) derives JsonCodec

/** Discriminated inbound message (response or event). */
enum InboundMessage:
  case Response(value: ServerResponse)
  case Event(value: ServerEvent)

object InboundMessage:
  given JsonDecoder[InboundMessage] = JsonDecoder[Json].mapOrFail { json =>
    json.asObject match
      case Some(obj) if obj.contains("id") =>
        json.as[ServerResponse].map(Response.apply)
      case Some(obj) if obj.contains("method") =>
        json.as[ServerEvent].map(Event.apply)
      case _ =>
        Left(s"Unrecognized inbound message: $json")
  }
end InboundMessage
