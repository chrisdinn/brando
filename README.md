Brando
======

A lightweight Redis client for Akka 2.2.

## Using

In your build.sbt

    resolvers += "http://chrisdinn.github.com/releases/"

    libraryDependencies += "com.digital-achiever" %% "brando" % "0.0.4"

### Getting started

Brando is a lightweight wrapper around the [Redis protocol](http://redis.io/topics/protocol).

Create a Brando actor with your server host and port. Send it a command and get your response as a reply.

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

### Response extractors

Use the provided extractors to map the response to its Scala type.

      for{ Response.AsString(value) ← brando ? Request("GET", "key") } yield value
      
      //value: String
      
      for{ Response.AsStrings(values) ← brando ? Request("KEYS", "*") } yield values
      
      //values: Seq[String]
      
      for{ Response.AsByteSeqs(value) ← brando ? Request("GET", "key") } yield value
      
      //value: Seq[Byte]
      
      for{ Response.AsStringsHash(fields) ← brando ? Request("HGETALL", "hash-key") } yield fields
      
      //value: Map[String,String]
      
### Presharding

Brando now provides preliminary support for sharding (AKA "Presharding"), as outlined [in the Redis documentation](http://redis.io/topics/partitioning) and in [this blog post from antirez](http://oldblog.antirez.com/post/redis-presharding.html).

To use it, simply create an instance of `ShardManager`, passing it a list of Redis shards you'd like it to connect to. From there, we simply create a pool of `Brando` instances - one for each shard.

	val shards = Seq(Shard("redis1", "10.0.0.1", 6379),
					 Shard("redis2", "10.0.0.2", 6379),
					 Shard("redis3", "10.0.0.3", 6379))
					 
	val shardManager = context.actorOf(Props(new ShardManager(shards)))

Once an instance of `ShardManager` has been created, send it commands via the `ShardRequest` class.

	shardManager ! ShardRequest(ByteString("GET"), ByteString(mykey))
	
Note that `ShardRequest` explicitly requires a key for all operations. This is because the key is used to determined which shard each request should be forwarded to. In this context, operations which operate on multiple keys (e.g. `MSET`, `MGET`) or no keys at all (e.g. `SELECT`, `FLUSHDB`) should be avoided, as they break the Redis sharding model.

Individual shards can have their configuration updated on the fly. To do this, send a `Shard` message to `ShardManager`.

	shardManager ! Shard("redis1", "10.0.0.4", 6379)
	
This is intended to support failover via [Redis Sentinel](http://redis.io/topics/sentinel). Note that the id of the shard __MUST__ match one of the original shards configured when the `ShardManager` instance was created. Adding new shards is not supported.

## License

This project is released under the Apache License v2, for more details see the 'LICENSE' file.

## Contributing

Fork the project, add tests if possible and send a pull request.

## Contributors

Chris Dinn, Gaetan Hervouet, Damien Levin, Matt MacAulay, Arron Norwell
