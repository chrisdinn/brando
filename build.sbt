name := "brando"

organization := "com.digital-achiever"

version := "0.1.0-SNAPSHOT"

scalaVersion := "2.10.1"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.2.0",
  "org.scalatest" %% "scalatest" % "1.9.1" % "test",
  "com.typesafe.akka" %% "akka-testkit" % "2.2.0" % "test"
)

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

publishTo <<= version { (v: String) =>
  if (v.trim.endsWith("-SNAPSHOT")) 
    Some(Resolver.file("Snapshots", file("../chrisdinn.github.com/snapshots/")))
  else
    Some(Resolver.file("Releases", file("../chrisdinn.github.com/releases/")))
}
