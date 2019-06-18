name := "brando"
organization := "com.digital-achiever"
version := "3.1.13"
scalaVersion := "2.12.6"
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

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.5.13",
  "org.scalatest" %% "scalatest" % "3.0.5" % "test",
  "com.typesafe.akka" %% "akka-testkit" % "2.5.13" % "test"
)

parallelExecution in Test := false

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"
