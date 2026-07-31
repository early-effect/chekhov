package chekhov.protocol

import chekhov.ChekhovError
import chekhov.protocol.generated.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*

/** Correlates request ids and owns the Playwright initialize handshake. */
trait ChannelConnection:
  def send[A: JsonEncoder](guid: String, method: String, params: A)(using Trace): IO[ChekhovError, Json]
  def sendEmpty(guid: String, method: String)(using Trace): IO[ChekhovError, Json]
  def playwrightGuid: String
  def initializer: Json

  /** Await a channel object's `__create__` initializer (by guid). */
  def awaitInitializer(guid: String)(using Trace): IO[ChekhovError, Json]

object ChannelConnection:

  /** Playwright rejects requests without a metadata object. */
  private val emptyMetadata: Json = Json.Obj()

  final case class CreatedObject(typeName: String, initializer: Json)

  final case class Live(
      transport: ChannelTransport,
      nextId: Ref[Int],
      playwrightGuid: String,
      initializer: Json,
      objects: Ref[Map[String, CreatedObject]],
  ) extends ChannelConnection:

    def send[A: JsonEncoder](guid: String, method: String, params: A)(using Trace): IO[ChekhovError, Json] =
      for
        id         <- nextId.getAndUpdate(_ + 1)
        paramsJson <- ZIO
          .fromEither(params.toJsonAST)
          .mapError(e => ChekhovError.Protocol(s"encode params: $e"))
        req = ClientRequest(
          id = id,
          guid = guid,
          method = method,
          params = Some(paramsJson),
          metadata = Some(emptyMetadata),
        )
        resp   <- transport.sendAndWait(req)
        result <- resp.error.match
          case Some(err) => ZIO.fail(ChekhovError.Protocol(err.toString))
          case None      =>
            resp.result.match
              case Some(r) => ZIO.succeed(r)
              case None    => ZIO.succeed(Json.Obj())
      yield result

    def sendEmpty(guid: String, method: String)(using Trace): IO[ChekhovError, Json] =
      send(guid, method, Json.Obj())

    def awaitInitializer(guid: String)(using Trace): IO[ChekhovError, Json] =
      def loop: IO[ChekhovError, Json] =
        objects.get.flatMap { map =>
          map.get(guid) match
            case Some(CreatedObject(_, init)) => ZIO.succeed(init)
            case None                         => ZIO.sleep(5.millis) *> loop
        }

      loop.timeoutFail(ChekhovError.Protocol(s"Timed out waiting for __create__ of $guid"))(8.seconds)

  end Live

  def live(using Trace): ZIO[Scope & ChannelTransport, ChekhovError, ChannelConnection] =
    for
      transport       <- ZIO.service[ChannelTransport]
      nextId          <- Ref.make(1)
      objects         <- Ref.make(Map.empty[String, CreatedObject])
      playwrightReady <- Promise.make[Nothing, Json]
      // Subscribe before initialize so Playwright `__create__` is never missed.
      eventQ <- transport.subscribeEvents
      _      <- ZStream.fromQueue(eventQ).mapZIO(ingestCreate(_, objects, playwrightReady)).runDrain.forkScoped
      id     <- nextId.getAndUpdate(_ + 1)
      // sdkLanguage must be a protocol enum value: javascript | python | java | csharp
      initParams <- ZIO
        .fromEither(Commands.Initialize(sdkLanguage = "java").toJsonAST)
        .mapError(e => ChekhovError.Protocol(e))
      req = ClientRequest(
        id = id,
        guid = "",
        method = "initialize",
        params = Some(initParams),
        metadata = Some(emptyMetadata),
      )
      resp   <- transport.sendAndWait(req)
      result <- resp.error.match
        case Some(err) => ZIO.fail(ChekhovError.Protocol(s"initialize error: $err"))
        case None      =>
          ZIO
            .fromOption(resp.result)
            .orElseFail(ChekhovError.Protocol("initialize returned empty result"))
      playwright <- ZIO
        .fromOption(result.asObject.flatMap(_.get("playwright")))
        .orElseFail(ChekhovError.Protocol(s"initialize missing playwright: $result"))
      guid <- ZIO
        .fromOption(playwright.asObject.flatMap(_.get("guid")).flatMap(_.asString))
        .orElseFail(ChekhovError.Protocol(s"initialize missing playwright.guid: $playwright"))
      // Browser-type guids live on the Playwright `__create__` initializer, not the initialize result.
      initializer <- playwrightReady.await
        .timeoutFail(ChekhovError.Protocol("Timed out waiting for Playwright __create__ event"))(
          8.seconds
        )
    yield Live(transport, nextId, guid, initializer, objects)

  private def ingestCreate(
      event: ServerEvent,
      objects: Ref[Map[String, CreatedObject]],
      playwrightReady: Promise[Nothing, Json],
  )(using Trace): UIO[Unit] =
    event match
      case ServerEvent(_, "__create__", Some(params)) =>
        val typ  = params.asObject.flatMap(_.get("type")).flatMap(_.asString)
        val guid = params.asObject.flatMap(_.get("guid")).flatMap(_.asString)
        val init = params.asObject.flatMap(_.get("initializer"))
        (typ, guid, init) match
          case (Some(t), Some(g), Some(initializer)) =>
            objects.update(_ + (g -> CreatedObject(t, initializer))) *>
              (if t == "Playwright" then playwrightReady.succeed(initializer).unit else ZIO.unit)
          case _ => ZIO.unit
      case _ => ZIO.unit

  /** Owns initialize handshake; Scope is managed by `ZLayer.scoped`. */
  val layer: ZLayer[ChannelTransport, ChekhovError, ChannelConnection] =
    ZLayer.scoped(live)
end ChannelConnection
