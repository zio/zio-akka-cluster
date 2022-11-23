addSbtPlugin("org.scalameta" % "sbt-scalafmt"    % "2.4.5")
addSbtPlugin("com.geirsson"  % "sbt-ci-release"  % "1.5.7")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix"    % "0.9.34")
addSbtPlugin("dev.zio"       % "zio-sbt-website" % "0.0.0+84-6fd7d64e-SNAPSHOT")

resolvers += Resolver.sonatypeRepo("public")
