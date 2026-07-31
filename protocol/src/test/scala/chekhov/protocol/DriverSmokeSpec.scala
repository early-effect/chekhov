package chekhov.protocol

import chekhov.ChekhovError
import chekhov.protocol.generated.*
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.stream.*
import zio.test.*

/** Bottom-up driver smoke tests. Enable with CHEKHOV_E2E=1. */
object DriverSmokeSpec extends ZIOSpecDefault:

  private def e2eEnabled: Boolean =
    sys.env.get("CHEKHOV_E2E").contains("1") ||
      sys.props.get("chekhov.e2e").contains("1")

  private val onlyIfE2E: TestAspectPoly =
    new TestAspect.PerTest.AtLeastR[Any]:
      def perTest[R, E](test: ZIO[R, TestFailure[E], TestSuccess])(using Trace) =
        if e2eEnabled then test else ZIO.succeed(TestSuccess.Ignored())

  override def aspects =
    Chunk(
      TestAspect.withLiveClock,
      TestAspect.timeout(60.seconds),
      TestAspect.sequential,
    )

  private val emptyMetadata: Json = Json.Obj()

  def spec =
    suite("driver smoke")(
      test("0. live clock advances") {
        for
          start <- Clock.instant
          _     <- ZIO.sleep(50.millis)
          end   <- Clock.instant
        yield assertTrue(end.isAfter(start))
      },
      test("1. spawn ChannelTransport") {
        ZIO
          .scoped(ChannelTransport.live)
          .as(assertTrue(true))
      },
      test("2. initialize sendAndWait") {
        ZIO.scoped {
          for
            transport <- ChannelTransport.live
            req = ClientRequest(
              id = 1,
              guid = "",
              method = "initialize",
              params = Some(Json.Obj("sdkLanguage" -> Json.Str("java"))),
              metadata = Some(emptyMetadata),
            )
            resp <- transport.sendAndWait(req)
            _    <- ZIO.debug(s"initialize resp=$resp")
          yield assertTrue(resp.id == 1, resp.error.isEmpty, resp.result.isDefined)
        }
      },
      test("3. poll Playwright __create__ after initialize") {
        ZIO.scoped {
          for
            transport <- ChannelTransport.live
            // Subscribe before initialize; Hub does not replay to late subscribers.
            collected <- Promise.make[ChekhovError, Json]
            eventQ    <- transport.subscribeEvents
            _         <- ZStream
              .fromQueue(eventQ)
              .mapZIO {
                case ServerEvent(_, "__create__", Some(params)) =>
                  val typ  = params.asObject.flatMap(_.get("type")).flatMap(_.asString)
                  val init = params.asObject.flatMap(_.get("initializer"))
                  (typ, init) match
                    case (Some("Playwright"), Some(initializer)) =>
                      collected.succeed(initializer).unit
                    case _ => ZIO.unit
                case _ => ZIO.unit
              }
              .runDrain
              .forkScoped
            req = ClientRequest(
              id = 1,
              guid = "",
              method = "initialize",
              params = Some(Json.Obj("sdkLanguage" -> Json.Str("java"))),
              metadata = Some(emptyMetadata),
            )
            _           <- transport.sendAndWait(req)
            initializer <- collected.await
              .timeoutFail(ChekhovError.Protocol("no Playwright __create__ within 5s"))(5.seconds)
            _        <- ZIO.debug(s"initializer keys=${initializer.asObject.map(_.keys.toList)}")
            chromium <- ZIO
              .fromOption(initializer.asObject.flatMap(_.get("chromium")))
              .orElseFail(ChekhovError.Protocol("no chromium"))
          yield assertTrue(chromium.asObject.flatMap(_.get("guid")).flatMap(_.asString).isDefined)
        }
      },
      test("4. ChannelConnection.layer") {
        ZIO
          .scoped {
            ChannelTransport.live.flatMap { t =>
              ChannelConnection.live.provideSome[Scope](ZLayer.succeed(t))
            }
          }
          .map { conn =>
            assertTrue(
              conn.playwrightGuid.nonEmpty,
              conn.initializer.asObject.flatMap(_.get("chromium")).isDefined,
            )
          }
      },
    ) @@ onlyIfE2E
end DriverSmokeSpec
