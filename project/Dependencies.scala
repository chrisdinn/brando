import sbt._

object Dependencies {

  val ourResolvers = Seq(
    "Horn" at "https://sbt.horn.co/repository/internal",
    "confluent" at "https://packages.confluent.io/maven/"
  )

  def AkkaOverrideDeps(akkaVersion: String) =
    Seq(
      "com.typesafe.akka" %% "akka-actor"                  % akkaVersion,
      "com.typesafe.akka" %% "akka-remote"                 % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster"                % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-sharding"       % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-sharding-typed" % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-tools"          % akkaVersion,
      "com.typesafe.akka" %% "akka-cluster-typed"          % akkaVersion,
      "com.typesafe.akka" %% "akka-coordination"           % akkaVersion,
      "com.typesafe.akka" %% "akka-discovery"              % akkaVersion,
      "com.typesafe.akka" %% "akka-distributed-data"       % akkaVersion,
      "com.typesafe.akka" %% "akka-serialization-jackson"  % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence"            % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-query"      % akkaVersion,
      "com.typesafe.akka" %% "akka-slf4j"                  % akkaVersion,
      "com.typesafe.akka" %% "akka-stream"                 % akkaVersion,
      "com.typesafe.akka" %% "akka-stream-typed"           % akkaVersion,
      "com.typesafe.akka" %% "akka-protobuf-v3"            % akkaVersion,
      "com.typesafe.akka" %% "akka-actor-typed"            % akkaVersion,
      "com.typesafe.akka" %% "akka-persistence-typed"      % akkaVersion,
      "com.typesafe.akka" %% "akka-multi-node-testkit"     % akkaVersion,
      "com.typesafe.akka" %% "akka-testkit"                % akkaVersion,
      "com.typesafe.akka" %% "akka-stream-testkit"         % akkaVersion,
      "com.typesafe.akka" %% "akka-actor-testkit-typed"    % akkaVersion,
      "com.typesafe.akka" %% "akka-pki"                    % akkaVersion
    )
}
