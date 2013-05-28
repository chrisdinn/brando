Brando (codename)
=================

A Redis client written with the Akka IO package introduced in Akka 2.2.

## Using

In your build.sbt

    resolvers += "http://chrisdinn.github.com/snapshots/"

    libraryDependencies += "com.digital-achiever" %% "brando" % "0.0.3"

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

## Use Response extractors
Brando actor forwards the reply as redis send it back. However, some extractors are provided to help mapping the responses to scala types.

      for{ Response.AsString(value) ← brando ? Request("GET", "key") } yield value
      
      //value: String
      
      for{ Response.AsStrings(values) ← brando ? Request("KEYS", "*") } yield values
      
      //values: Seq[String]
      
      for{ Response.AsByteSeqs(value) ← brando ? Request("GET", "key") } yield value
      
      //value: Seq[Byte]
      
      for{ Response.AsStringsHash(fields) ← brando ? Request("HGETALL", "hash-key") } yield fields
      
      //value: Map[String,String]
