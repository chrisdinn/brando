package brando

import org.scalatest.FunSpecLike
import akka.testkit._

import akka.actor._
import akka.util.ByteString
import scala.concurrent.duration._
import scala.util.Failure

class ShardManagerTest extends TestKit(ActorSystem("ShardManagerTest"))
    with FunSpecLike with ImplicitSender {

  import ShardManager._
  import Connection._

  describe("creating shards") {
    it("should create a pool of clients mapped to ids") {
      val shards = Seq(
        RedisShard("server1", "localhost", 6379, 0),
        RedisShard("server2", "localhost", 6379, 1),
        RedisShard("server3", "localhost", 6379, 2))

      val shardManager = TestActorRef[ShardManager](ShardManager(
        shards))

      assert(shardManager.underlyingActor.pool.keys === Set("server1", "server2", "server3"))
    }

    it("should support updating existing shards but not creating new ones") {
      val shards = Seq(
        RedisShard("server1", "localhost", 6379, 0),
        RedisShard("server2", "localhost", 6379, 1),
        RedisShard("server3", "localhost", 6379, 2))

      val shardManager = TestActorRef[ShardManager](ShardManager(
        shards))

      assert(shardManager.underlyingActor.pool.keys === Set("server1", "server2", "server3"))

      shardManager ! RedisShard("server1", "localhost", 6379, 6)

      assert(shardManager.underlyingActor.pool.keys === Set("server1", "server2", "server3"))

      shardManager ! RedisShard("new_server", "localhost", 6378, 3)

      assert(shardManager.underlyingActor.pool.keys === Set("server1", "server2", "server3"))
    }
  }

  describe("sending requests") {
    describe("using sentinel") {
      it("should forward each request to the appropriate client transparently") {
        val sentinelProbe = TestProbe()
        val redisProbe = TestProbe()
        val sentinel = system.actorOf(Sentinel(listeners = Set(sentinelProbe.ref)))
        val shardManager = system.actorOf(ShardManager(
          shards = Seq(SentinelShard("mymaster", 0)),
          sentinelClient = Some(sentinel),
          listeners = Set(redisProbe.ref)))

        sentinelProbe.expectMsg(
          Connecting("localhost", 26379))
        sentinelProbe.expectMsg(
          Connected("localhost", 26379))

        redisProbe.expectMsg(
          Connecting("127.0.0.1", 6379))
        redisProbe.expectMsg(
          Connected("127.0.0.1", 6379))

        shardManager ! ("key", Request("SET", "shard_manager_test", "some value"))

        expectMsg(Some(Ok))

        shardManager ! ("key", Request("GET", "shard_manager_test"))

        expectMsg(Some(ByteString("some value")))
      }
    }

    it("should forward each request to the appropriate client transparently") {
      val shards = Seq(
        RedisShard("server1", "127.0.0.1", 6379, 0),
        RedisShard("server2", "127.0.0.1", 6379, 1),
        RedisShard("server3", "127.0.0.1", 6379, 2))

      val sentinelProbe = TestProbe()
      val redisProbe = TestProbe()
      val sentinel = system.actorOf(Sentinel(listeners = Set(sentinelProbe.ref)))
      val shardManager = system.actorOf(ShardManager(
        shards = shards,
        sentinelClient = Some(sentinel),
        listeners = Set(redisProbe.ref)))

      sentinelProbe.expectMsg(
        Connecting("localhost", 26379))
      sentinelProbe.expectMsg(
        Connected("localhost", 26379))

      val redisNotifications = redisProbe.receiveN(6)
      assert(redisNotifications.toSet === Set(
        Connecting("127.0.0.1", 6379),
        Connecting("127.0.0.1", 6379),
        Connecting("127.0.0.1", 6379),
        Connected("127.0.0.1", 6379),
        Connected("127.0.0.1", 6379),
        Connected("127.0.0.1", 6379)))

      shardManager ! ("key", Request("SET", "shard_manager_test", "some value"))

      expectMsg(Some(Ok))

      shardManager ! ("key", Request("GET", "shard_manager_test"))

      expectMsg(Some(ByteString("some value")))
    }

    it("should infer the key from the params list") {
      val shards = Seq(
        RedisShard("server1", "127.0.0.1", 6379, 0),
        RedisShard("server2", "127.0.0.1", 6379, 1),
        RedisShard("server3", "127.0.0.1", 6379, 2))

      val redisProbe = TestProbe()
      val shardManager = TestActorRef[ShardManager](ShardManager(
        shards, listeners = Set(redisProbe.ref)))

      val redisNotifications = redisProbe.receiveN(6)
      assert(redisNotifications.toSet === Set(
        Connecting("127.0.0.1", 6379),
        Connecting("127.0.0.1", 6379),
        Connecting("127.0.0.1", 6379),
        Connected("127.0.0.1", 6379),
        Connected("127.0.0.1", 6379),
        Connected("127.0.0.1", 6379)))

      shardManager ! Request("SET", "shard_manager_test", "some value")

      expectMsg(Some(Ok))

      shardManager ! Request("GET", "shard_manager_test")

      expectMsg(Some(ByteString("some value")))
    }

    it("should fail with IllegalArgumentException when params is empty") {
      val shards = Seq(
        RedisShard("server1", "127.0.0.1", 6379, 0),
        RedisShard("server2", "127.0.0.1", 6379, 1),
        RedisShard("server3", "127.0.0.1", 6379, 2))

      val redisProbe = TestProbe()
      val shardManager = TestActorRef[ShardManager](ShardManager(
        shards, listeners = Set(redisProbe.ref)))

      val redisNotifications = redisProbe.receiveN(6)
      assert(redisNotifications.toSet === Set(
        Connecting("127.0.0.1", 6379),
        Connecting("127.0.0.1", 6379),
        Connecting("127.0.0.1", 6379),
        Connected("127.0.0.1", 6379),
        Connected("127.0.0.1", 6379),
        Connected("127.0.0.1", 6379)))

      shardManager ! Request("SET")

      expectMsgClass(classOf[Failure[IllegalArgumentException]])
    }

    it("should broadcast a Request to all shards") {
      val shards = Seq(
        RedisShard("server1", "127.0.0.1", 6379, 0),
        RedisShard("server2", "127.0.0.1", 6379, 1),
        RedisShard("server3", "127.0.0.1", 6379, 2))

      val redisProbe = TestProbe()
      val shardManager = TestActorRef[ShardManager](ShardManager(
        shards, listeners = Set(redisProbe.ref)))

      val redisNotifications = redisProbe.receiveN(6)
      assert(redisNotifications.toSet === Set(
        Connecting("127.0.0.1", 6379),
        Connecting("127.0.0.1", 6379),
        Connecting("127.0.0.1", 6379),
        Connected("127.0.0.1", 6379),
        Connected("127.0.0.1", 6379),
        Connected("127.0.0.1", 6379)))

      val listName = scala.util.Random.nextString(5)

      shardManager ! BroadcastRequest("LPUSH", listName, "somevalue")

      shards.foreach { _ ⇒ expectMsg(Some(new java.lang.Long(1))) }

      shardManager ! BroadcastRequest("LPOP", listName)

      shards.foreach { _ ⇒ expectMsg(Some(ByteString("somevalue"))) }
    }
  }

  describe("Listening to Shard state changes") {
    it("should notify listeners when a shard connect successfully") {
      val shards = Seq(RedisShard("server1", "localhost", 6379, 0))

      val probe = TestProbe()

      val shardManager = TestActorRef[ShardManager](ShardManager(
        shards, Set(probe.ref)))

      probe.expectMsg(Connecting("localhost", 6379))
      probe.expectMsg(Connected("localhost", 6379))
    }

    it("should notify listeners when a shard fails to connect") {
      val shards = Seq(
        RedisShard("server2", "localhost", 13579, 1))

      val probe = TestProbe()

      val shardManager = TestActorRef[ShardManager](ShardManager(
        shards, Set(probe.ref)))

      probe.expectMsg(Connecting("localhost", 13579))
      probe.expectMsg(ConnectionFailed("localhost", 13579))
    }

    it("should cleaned up any dead listeners") {

      val shards = Seq(
        RedisShard("server1", "localhost", 6379, 0))

      val probe1 = TestProbe()
      val probe2 = TestProbe()

      val shardManager = TestActorRef[ShardManager](ShardManager(
        shards, Set(probe1.ref, probe2.ref))).underlyingActor
      assertResult(2)(shardManager.listeners.size)

      probe1.ref ! PoisonPill

      probe2.expectMsg(Connecting("localhost", 6379))
      probe2.expectMsg(Connected("localhost", 6379))

      assertResult(1)(shardManager.listeners.size)

    }

    it("should notify listeners when a shard fails to authenticate") {
      val shards = Seq(
        RedisShard("server1", "localhost", 6379, 0),
        RedisShard("server2", "localhost", 6379, 1, auth = Some("not-valid-auth")))

      val probe = TestProbe()

      val shardManager = TestActorRef[ShardManager](ShardManager(
        shards, Set(probe.ref)))

      val redisNotifications = probe.receiveN(4)
      assert(redisNotifications.toSet === Set(
        Connecting("localhost", 6379),
        Connecting("localhost", 6379),
        Connected("localhost", 6379),
        Redis.AuthenticationFailed("localhost", 6379)))
    }
  }
}
