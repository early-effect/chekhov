import zipx.*

/** Typed catalog: every library and plugin this build may use. `zipxDepUpdate` rewrites constructors here. sbt-zipx is
  * not a row: generate emits it from the loaded plugin (`zipxSelfPlugins`). Action pins stay on jar defaults. sbt-pgp
  * is not a row: zipx already brings it in.
  */
object MyVersions extends ZipxVersions:
  val sbt: SbtVersion     = SbtVersion("2.0.6")
  val scala: ScalaVersion = ScalaVersion("3.8.4")

  val zio        = Lib("dev.zio", "zio", "2.1.26")
  val zioStreams = zio.mod("zio-streams")
  val zioJson    = Lib("dev.zio", "zio-json", "0.10.0")
  val zioTest    = zio.mod("zio-test")
  val zioTestSbt = zio.mod("zio-test-sbt")

  val scalajsDom    = Lib("org.scala-js", "scalajs-dom", "2.8.0")
  val scalajsJsEnvs = Lib("org.scala-js", "scalajs-js-envs", "1.6.0")
  val ascentJs      = Lib("rocks.earlyeffect", "ascent-js", "0.3.1")

  val specular        = Lib("rocks.earlyeffect", "specular-core", "0.12.0")
  val specularZioTest = specular.mod("specular-zio-test").test
  val specularTheme   = specular.mod("early-effect-docs-theme").test

  val scalajs        = Plugin("org.scala-js", "sbt-scalajs", "1.22.0")
  val scalafmt       = Plugin("org.scalameta", "sbt-scalafmt", "2.6.2")
  val dynverCi       = Plugin("rocks.earlyeffect", "sbt-dynver-ci", "0.2.2")
  val specularPlugin = Plugin("rocks.earlyeffect", "sbt-specular", "0.12.0")

  def zioCore     = library(zio, zioStreams)
  def zioProtocol = library(zio, zioStreams, zioJson)
  def zioOnly     = library(zio)
  def zioTests    = library(zioTest.test, zioTestSbt.test)
  def zioTestLib  = library(zio, zioTest, zioTestSbt)
  def domLib      = library(zio, scalajsDom)
  def ascentLib   = library(ascentJs, zio)
  def jsenvLib    = library(scalajsJsEnvs, zio, zioJson)
  def docsTest    = library(specularZioTest, specularTheme)
end MyVersions
