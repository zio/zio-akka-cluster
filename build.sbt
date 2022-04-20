val mainScala = "2.13.7"
val allScala  = Seq("2.12.15", mainScala)

val zioVersion  = "2.0.0-RC5"
val akkaVersion = "2.6.19"

organization := "dev.zio"
homepage := Some(url("https://github.com/zio/zio-akka-cluster"))
name := "zio-akka-cluster"
licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0"))
scalaVersion := mainScala
Test / parallelExecution := false
Test / fork := true
pgpPublicRing := file("/tmp/public.asc")
pgpSecretRing := file("/tmp/secret.asc")
scmInfo := Some(
  ScmInfo(url("https://github.com/zio/zio-akka-cluster/"), "scm:git:git@github.com:zio/zio-akka-cluster.git")
)
developers := List(
  Developer(
    "ghostdogpr",
    "Pierre Ricadat",
    "ghostdogpr@gmail.com",
    url("https://github.com/ghostdogpr")
  )
)

libraryDependencies ++= Seq(
  "dev.zio"           %% "zio"                   % zioVersion,
  "dev.zio"           %% "zio-streams"           % zioVersion,
  "com.typesafe.akka" %% "akka-cluster-tools"    % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster-sharding" % akkaVersion,
  "dev.zio"           %% "zio-test"              % zioVersion % "test",
  "dev.zio"           %% "zio-test-sbt"          % zioVersion % "test",
  compilerPlugin("org.typelevel" %% "kind-projector"     % "0.13.2" cross CrossVersion.full),
  compilerPlugin("com.olegpy"    %% "better-monadic-for" % "0.3.1")
)

testFrameworks := Seq(new TestFramework("zio.test.sbt.ZTestFramework"))

scalacOptions ++= Seq(
  "-deprecation",
  "-encoding",
  "UTF-8",
  "-explaintypes",
  "-Yrangepos",
  "-feature",
  "-language:higherKinds",
  "-language:existentials",
  "-unchecked",
  "-Xlint:_,-type-parameter-shadow",
  "-Ywarn-numeric-widen",
  "-Ywarn-unused",
  "-Ywarn-value-discard"
) ++ (CrossVersion.partialVersion(scalaVersion.value) match {
  case Some((2, 12)) =>
    Seq(
      "-Xsource:2.13",
      "-Yno-adapted-args",
      "-Ypartial-unification",
      "-Ywarn-extra-implicit",
      "-Ywarn-inaccessible",
      "-Ywarn-infer-any",
      "-Ywarn-nullary-override",
      "-Ywarn-nullary-unit",
      "-opt-inline-from:<source>",
      "-opt-warnings",
      "-opt:l:inline",
      "-Xfuture"
    )
  case _             => Nil
})

run / fork := true

crossScalaVersions := allScala

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")
