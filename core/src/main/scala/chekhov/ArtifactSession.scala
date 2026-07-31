package chekhov

import zio.*

/** Tracks whether the current suite/test should retain OnFailure artifacts.
  *
  * [[ChekhovSuite]] marks failed when capture mode is [[ArtifactCapture.OnFailure]]. Custom stacks can call
  * [[markFailed]] themselves.
  */
trait ArtifactSession:
  def markFailed(using Trace): UIO[Unit]
  def shouldKeep(mode: ArtifactCapture)(using Trace): UIO[Boolean]

object ArtifactSession:
  def markFailed(using Trace): URIO[ArtifactSession, Unit] =
    ZIO.serviceWithZIO[ArtifactSession](_.markFailed)

  def shouldKeep(mode: ArtifactCapture)(using Trace): URIO[ArtifactSession, Boolean] =
    ZIO.serviceWithZIO[ArtifactSession](_.shouldKeep(mode))

  val live: ULayer[ArtifactSession] =
    ZLayer.fromZIO:
      Ref.make(false).map { failed =>
        new ArtifactSession:
          def markFailed(using Trace): UIO[Unit]                           = failed.set(true)
          def shouldKeep(mode: ArtifactCapture)(using Trace): UIO[Boolean] =
            mode match
              case ArtifactCapture.Off       => ZIO.succeed(false)
              case ArtifactCapture.Always    => ZIO.succeed(true)
              case ArtifactCapture.OnFailure => failed.get
      }
end ArtifactSession
