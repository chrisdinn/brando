name := "brando"

organization := "io.redis.brando"

version := "3.1.0"

scalaVersion := "2.11.7"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.4.1",
  "org.scalatest" %% "scalatest" % "2.2.5" % "test",
  "com.typesafe.akka" %% "akka-testkit" % "2.4.1" % "test"
)

parallelExecution in Test := false

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

publishTo <<= version { (v: String) =>
  if (v.trim.endsWith("-SNAPSHOT"))
    Some(Resolver.file("Snapshots", file("../chrisdinn.github.com/snapshots/")))
  else
    Some(Resolver.file("Releases", file("../chrisdinn.github.com/releases/")))
}
