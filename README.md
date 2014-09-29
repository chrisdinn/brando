Brando
======

[![Build Status](https://travis-ci.org/chrisdinn/brando.svg?branch=master)](https://travis-ci.org/chrisdinn/brando)

A lightweight Redis client for use with [Akka](http://akka.io).

## Using

In your build.sbt

    resolvers += "http://chrisdinn.github.io/releases/"

    libraryDependencies += "com.digital-achiever" %% "brando" % "2.0.3"

### Getting started

Brando is a lightweight wrapper around the [Redis protocol](http://redis.io/topics/protocol).

Create a Brando actor with your server host and port. 

      import brando._

      val redis = system.actorOf(Brando("localhost", 6379))

You should specify a database and password if you intend to use them. 

      val redis = system.actorOf(Brando("localhost", 6379, database = Some(5), auth = Some("password")))

This is important; if your Brando actor restarts you want be sure it reconnects successfully and to the same database.

Next, send it a command and get your response as a reply.

      redis ! Request("PING")

      // Response: Some(Pong)

The Redis protocol supports 5 standard types of reply: Status, Error, Integer, Bulk and Multi Bulk as well as a special NULL Bulk/Multi Bulk reply. 

Status replies are returned as case objects, such as `Pong` and `Ok`.

      redis ! Request("SET", "some-key", "this-value")

      // Response: Some(Ok)

Error replies are returned as `akka.actor.Status.Failure` objects containing an an exception with server's response as its message.

      redis ! Request("EXPIRE", "1", "key")
	  
	  // Response: Failure(brando.BrandoException: ERR value is not an integer or out of range)

Integer replies are returned as `Option[Int]`. 

      redis ! Request("SADD", "some-set", "one", "two")

      // Response: Some(2)

Bulk replies as `Option[akka.util.ByteString]`.

      redis ! Request("GET", "some-key")

      // Response: Some(ByteString("this-value"))

Multi Bulk replies as `Option[List[Option[ByteString]]]`.

      redis ! Request("SMEMBERS", "some-set")

      // Response: Some(List(Some(ByteString("one")), Some(ByteString("two"))))

NULL replies are returned as `None` and may appear either on their own or nested inside a Multi Bulk reply.

      redis ! Request("GET", "non-existent-key")

      // Response: None

If you're not sure what to expect in response to a request, please refer to the Redis command documentation at [http://redis.io/commands](http://redis.io/commands) where the reply type for each is clearly stated.

### Response extractors

Use the provided response extractors to map your Redis reply to a more appropriate Scala type.

      for{ Response.AsString(value) ← redis ? Request("GET", "key") } yield value
      
      //value: String
      
      for{ Response.AsStrings(values) ← redis ? Request("KEYS", "*") } yield values
      
      //values: Seq[String]
      
      for{ Response.AsByteSeqs(value) ← redis ? Request("GET", "key") } yield value
      
      //value: Seq[Byte]
      
      for{ Response.AsStringsHash(fields) ← redis ? Request("HGETALL", "hash-key") } yield fields
      
      //fields: Map[String,String]
      
### Monitoring Connection State Changes

If a set of listeners is provided to the Brando actor when it is created , it will inform the those listeners about state changes to the underlying Redis connection. For example (from inside an actor):

      val redis = context.actorOf(Brando("localhost", 6379, listeners = Set(self)))

Currently, the possible messages sent to each listener include the following:

 * `Connected`: When a TCP connection has been created, and Authentication (if applicable) has succeeded.
 * `Disconnected`: The connection has been lost. Brando transparently handles disconnects and will automatically reconnect, so typically no user action at all is needed here. During the time that Brando is disconnected, Redis commands sent to Brando will be queued, and will be processed when a connection is established.
 * `AuthenticationFailed`: The TCP connected was made, but Redis auth failed.
 * `ConnectionFailed`:  A connection could not be (re-) established after three attempts. Brando will not attempt to recover from this state; the user should take action.

All these messages inherit from the `BrandoStateChange` trait.

### Sharding

Brando provides support for sharding, as outlined [in the Redis documentation](http://redis.io/topics/partitioning) and in [this blog post from antirez](http://oldblog.antirez.com/post/redis-presharding.html).

To use it, simply create an instance of `ShardManager`, passing it a list of Redis shards you'd like it to connect to. From there, we create a pool of `Brando` instances - one for each shard.

	val shards = Seq(Shard("redis1", "10.0.0.1", 6379),
					 Shard("redis2", "10.0.0.2", 6379),
					 Shard("redis3", "10.0.0.3", 6379))

	val shardManager = context.actorOf(ShardManager(shards))

Once an instance of `ShardManager` has been created, it can be sent several types of messages:

* `Request` objects for inferring the shard key from the params
* `Tuple2[String, Request]` objects for specifying the shard key explicitly
* `ShardBroadcast` objects for broadcasting requests to all shards

Here are some examples,

	shardManager ! Request("SET", "mykey", "some_value")
	shardManager ! ("myshardkey", Request("SET", "mykey", "some_value"))
	shardManager ! BroadcastRequest("LPOP", "mylistkey") // don't use the ask pattern

Note that the `ShardManager` explicitly requires a key for all operations except for the `BroadcastRequest`. This is because the key is used to determined which shard each request should be forwarded to. In this context, operations which operate on multiple keys (e.g. `MSET`, `MGET`) or no keys at all (e.g. `SELECT`, `FLUSHDB`) should be avoided, as they break the Redis sharding model. Also note that the `BroadcastRequest` must not be used with the `ask` pattern in Akka or responses will be lost!

Individual shards can have their configuration updated on the fly. To do this, send a `Shard` message to `ShardManager`.

	shardManager ! Shard("redis1", "10.0.0.4", 6379)

This is intended to support failover via [Redis Sentinel](http://redis.io/topics/sentinel). Note that the id of the shard __MUST__ match one of the original shards configured when the `ShardManager` instance was created. Adding new shards is not supported.

State changes such as disconnects and connection failures can be monitored by providing a set of listeners to the `ShardManager`:

	val shardManager = context.actorOf(ShardManager(shards, listeners = Set(self)))

The `ShardManager` will send a `ShardStateChange(shard, state)` message when a shard changes state; here `shard` is a shard object indicating which shard has changed state, and `state` is a `BrandoStateChange` object, documented above, indicating which new state the shard has entered.

## Documentation

Read the API documentation here: [http://chrisdinn.github.io/api/brando-1.0.0/](http://chrisdinn.github.io/api/brando-1.0.0/)

## Mailing list

Send questions, comments or discussion topics to the mailing list brando@librelist.com.

## License

This project is released under the Apache License v2, for more details see the 'LICENSE' file.

## Contributing

Fork the project, add tests if possible and send a pull request.

## Contributors

Chris Dinn, Jason Goodwin, Tyson Hamilton, Gaetan Hervouet, Damien Levin, Matt MacAulay, Arron Norwell
