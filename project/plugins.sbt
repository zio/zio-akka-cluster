addSbtPlugin("org.scalameta" % "sbt-scalafmt"    % "2.4.5")
addSbtPlugin("com.geirsson"  % "sbt-ci-release"  % "1.5.7")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix"    % "0.9.34")
addSbtPlugin("dev.zio"       % "zio-sbt-website" % "0.1.5+21-de576a1e-SNAPSHOT")

resolvers += Resolver.sonatypeRepo("public")
