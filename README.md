Brando (codename)
=================

A Redis client written with the Akka IO package introduced in Akka 2.2.

## Using

In your build.sbt

    resolvers += "http://chrisdinn.github.com/snapshots/"

    libraryDependencies += "com.digital-achiever" %% "brando" % "0.0.2-SNAPSHOT"

## Getting started

To use talk to Redis, create a Brando actor and send it requests and be prepared to handle the response.

      import brando._

      val brando = system.actorOf(Brando("localhost",6379))

      brando ! Request("SET", "some-key", "this-value")

      // Response: Some(Ok)

      brando ! Request("GET", "some-key")

      // Response: Some(ByteString("this-value"))

      brando ! Request("SADD", "some-set", "one", "two", "three")

      // Response: Some(3)

      brando ! Request("GET", "some-set")

      // Response: Some(List(Some("one"), Some("two"), Some("three")))

      brando ! Request("GET", "non-existent-key")

      // Response: None      

Brando only talks to localhost and Redis's default port, 6379.
