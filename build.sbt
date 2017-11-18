name := "brando"

organization := "com.digital-achiever"

version := "3.1.9"

scalaVersion := "2.12.4"

scalacOptions ++= Seq("-unchecked", "-deprecation", "-feature")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.5.6",
  "org.scalatest" %% "scalatest" % "3.0.4" % "test",
  "com.typesafe.akka" %% "akka-testkit" % "2.5.6" % "test"
)

parallelExecution in Test := false

resolvers += "Typesafe Repository" at "http://repo.typesafe.com/typesafe/releases/"

//publishTo <<= version { (v: String) =>
//  if (v.trim.endsWith("-SNAPSHOT"))
//    Some(Resolver.file("Snapshots", file("../chrisdinn.github.com/snapshots/")))
//  else
//    Some(Resolver.file("Releases", file("../chrisdinn.github.com/releases/")))
//}
