addSbtPlugin("org.scala-js"      % "sbt-scalajs"   % "1.22.0")
addSbtPlugin("org.scalameta"     % "sbt-scalafmt"  % "2.6.2")
addSbtPlugin("rocks.earlyeffect" % "sbt-dynver-ci" % "0.2.2")
addSbtPlugin("com.github.sbt"    % "sbt-pgp"       % "2.3.1")
addSbtPlugin("rocks.earlyeffect" % "sbt-specular"  % "0.7.3")
addSbtPlugin("rocks.earlyeffect" % "sbt-zipx"      % "0.1.5")

// Protocol YAML → Scala AST codegen (meta-build).
libraryDependencies += "org.yaml" % "snakeyaml" % "2.6"
