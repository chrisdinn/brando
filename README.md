Brando
======

A lightweight Redis client for Akka 2.2.

## Using

In your build.sbt

    resolvers += "http://chrisdinn.github.io/releases/"

    libraryDependencies += "com.digital-achiever" %% "brando" % "0.2.2"

### Getting started

Brando is a lightweight wrapper around the [Redis protocol](http://redis.io/topics/protocol).

Create a Brando actor with your server host and port. 

      import brando._

      val brando = system.actorOf(Brando("localhost", 6379))

You should specify a database and password if you intend to use them. 

      val brando = system.actorOf(Brando("localhost", 6379, database = Some(5), auth = Some("password")))

This is important; if your Brando actor restarts you want be sure it reconnects successfully and to the same database.

Next, send it a command and get your response as a reply.

      brando ! Request("PING")

      // Response: Some(Pong)

The Redis protocol supports 5 standard types of reply: Status, Error, Integer, Bulk and Multi Bulk as well as a special NULL Bulk/Multi Bulk reply. 

Status replies are returned as case objects, such as `Pong` and `Ok`.

      brando ! Request("SET", "some-key", "this-value")

      // Response: Some(Ok)

Error replies are returned as `akka.actor.Status.Failure` objects containing an an exception with server's response as its message.

      brando ! Request("EXPIRE", "1", "key")
	  
	  // Response: Failure(brando.BrandoException: ERR value is not an integer or out of range)

Integer replies are returned as `Option[Int]`. 

      brando ! Request("SADD", "some-set", "one", "two")

      // Response: Some(2)

Bulk replies as `Option[akka.util.ByteString]`.

      brando ! Request("GET", "some-key")

      // Response: Some(ByteString("this-value"))

Multi Bulk replies as `Option[List[Option[ByteString]]]`.

      brando ! Request("SMEMBERS", "some-set")

      // Response: Some(List(Some(ByteString("one")), Some(ByteString("two"))))

NULL replies are returned as `None` and may appear either on their own or nested inside a Multi Bulk reply.

      brando ! Request("GET", "non-existent-key")

      // Response: None

If you're not sure what to expect in response to a request, please refer to the Redis command documentation at [http://redis.io/commands](http://redis.io/commands) where the reply type for each is clearly stated.

### Response extractors

Use the provided response extractors to map your Redis reply to a more appropriate Scala type.

      for{ Response.AsString(value) ← brando ? Request("GET", "key") } yield value
      
      //value: String
      
      for{ Response.AsStrings(values) ← brando ? Request("KEYS", "*") } yield values
      
      //values: Seq[String]
      
      for{ Response.AsByteSeqs(value) ← brando ? Request("GET", "key") } yield value
      
      //value: Seq[Byte]
      
      for{ Response.AsStringsHash(fields) ← brando ? Request("HGETALL", "hash-key") } yield fields
      
      //fields: Map[String,String]
      
### Presharding

Brando provides preliminary support for sharding (AKA "Presharding"), as outlined [in the Redis documentation](http://redis.io/topics/partitioning) and in [this blog post from antirez](http://oldblog.antirez.com/post/redis-presharding.html).

To use it, simply create an instance of `ShardManager`, passing it a list of Redis shards you'd like it to connect to. From there, we simply create a pool of `Brando` instances - one for each shard.

	val shards = Seq(Shard("redis1", "10.0.0.1", 6379),
					 Shard("redis2", "10.0.0.2", 6379),
					 Shard("redis3", "10.0.0.3", 6379))
					 
	val shardManager = context.actorOf(ShardManager(shards))

Once an instance of `ShardManager` has been created, send it commands via the `ShardRequest` class.

	shardManager ! ShardRequest(ByteString("GET"), ByteString(mykey))
	
Note that `ShardRequest` explicitly requires a key for all operations. This is because the key is used to determined which shard each request should be forwarded to. In this context, operations which operate on multiple keys (e.g. `MSET`, `MGET`) or no keys at all (e.g. `SELECT`, `FLUSHDB`) should be avoided, as they break the Redis sharding model.

Individual shards can have their configuration updated on the fly. To do this, send a `Shard` message to `ShardManager`.

	shardManager ! Shard("redis1", "10.0.0.4", 6379)
	
This is intended to support failover via [Redis Sentinel](http://redis.io/topics/sentinel). Note that the id of the shard __MUST__ match one of the original shards configured when the `ShardManager` instance was created. Adding new shards is not supported.

State changes such as disconnects and connection failures can be monitored by providing a set of listeners to the `ShardManager`:

	val shardManager = context.actorOf(ShardManager(shards, listeners = Set(self)))

The `ShardManager` will send a `ShardStateChange` message when a shard changes state; this message contains the shard as well as the new state, which may be one of `Connected`, `Disconnected`, `AuthenticationFailed`, or `ConnectionFailed`. Brando automatically attempts to reconnect after the `Disconnected` event, so no user action is required.. If a connection attempt fails after multiple retries, then `ConnectionFailed` will occur, after which Brando will no longer attempt to connect.

## Documentation

Read the API documentation here: [http://chrisdinn.github.io/api/brando-0.2.2/](http://chrisdinn.github.io/api/brando-0.2.2/)

## Mailing list

Send questions, comments or discussion topics to the mailing list brando@librelist.com.

## License

This project is released under the Apache License v2, for more details see the 'LICENSE' file.

## Contributing

Fork the project, add tests if possible and send a pull request.

## Contributors

Chris Dinn, Jason Goodwin, Gaetan Hervouet, Damien Levin, Matt MacAulay, Arron Norwell
