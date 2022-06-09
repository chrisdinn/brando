name := "brando"
organization := "com.digital-achiever"
version := "3.2.0"
scalaVersion := "2.13.8"
scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

// Sbt seems to have some issues with publishing packages with credentials and below line is an workaround
// for this bug: https://github.com/sbt/sbt/issues/3570
updateOptions := updateOptions.value.withGigahorse(false)

publishTo := Some("Horn SBT" at "https://sbt.horn.co/repository/internal")
credentials += Credentials(
  "Repository Archiva Managed internal Repository",
  "sbt.horn.co",
  sys.env("HORN_SBT_USERNAME"),
  sys.env("HORN_SBT_PASSWORD")
)

val akkaV = "2.6.19"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor"   % akkaV,
  "org.scalatest"     %% "scalatest"    % "3.0.9" % "test",
  "com.typesafe.akka" %% "akka-testkit" % akkaV   % "test"
)

parallelExecution in Test := false
