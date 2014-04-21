package brando

import org.scalatest.FunSpecLike
import akka.testkit._

import akka.actor._
import akka.util.ByteString
import scala.concurrent.duration._

class ShardManagerTest extends TestKit(ActorSystem("ShardManagerTest"))
    with FunSpecLike with ImplicitSender {

  describe("creating shards") {
    it("should create a pool of clients mapped to ids") {
      val shards = Seq(
        Shard("server1", "localhost", 6379, Some(0)),
        Shard("server2", "localhost", 6379, Some(1)),
        Shard("server3", "localhost", 6379, Some(2)))

      val shardManager = TestActorRef(new ShardManager(shards, ShardManager.defaultHashFunction))

      assert(shardManager.underlyingActor.pool.keys === Set("server1", "server2", "server3"))
    }

    it("should support updating existing shards but not creating new ones") {
      val shards = Seq(
        Shard("server1", "localhost", 6379, Some(0)),
        Shard("server2", "localhost", 6379, Some(1)),
        Shard("server3", "localhost", 6379, Some(2)))

      val shardManager = TestActorRef(new ShardManager(shards, ShardManager.defaultHashFunction))

      assert(shardManager.underlyingActor.pool.keys === Set("server1", "server2", "server3"))

      shardManager ! Shard("server1", "localhost", 6379, Some(6))

      assert(shardManager.underlyingActor.pool.keys === Set("server1", "server2", "server3"))

      shardManager ! Shard("new_server", "localhost", 6378, Some(3))

      assert(shardManager.underlyingActor.pool.keys === Set("server1", "server2", "server3"))
    }
  }

  describe("sending requests") {
    it("should forward each request to the appropriate client transparently") {
      val shards = Seq(
        Shard("server1", "localhost", 6379, Some(0)),
        Shard("server2", "localhost", 6379, Some(1)),
        Shard("server3", "localhost", 6379, Some(2)))

      val shardManager = TestActorRef(new ShardManager(shards, ShardManager.defaultHashFunction))

      shardManager ! ShardRequest("SET", "shard_manager_test", "some value")

      expectMsg(Some(Ok))

      shardManager ! ShardRequest("GET", "shard_manager_test")

      expectMsg(Some(ByteString("some value")))
    }
  }

  describe("Listening to Shard state changes") {

    it("should notify listeners when a shard connect successfully") {
      val shards = Seq(Shard("server1", "localhost", 6379, Some(0)))

      val probe = TestProbe()

      val shardManager = TestActorRef(new ShardManager(shards, ShardManager.defaultHashFunction, Set(probe.ref)))

      probe.expectMsg(ShardStateChange(shards(0), Connected))
    }

    it("should notify listeners when a shard fails to connect") {
      val shards = Seq(
        Shard("server1", "localhost", 6379, Some(0)),
        Shard("server2", "localhost", 13579, Some(1)))

      val probe = TestProbe()

      val shardManager = TestActorRef(new ShardManager(shards, ShardManager.defaultHashFunction, Set(probe.ref)))

      probe.expectMsg(ShardStateChange(shards(0), Connected))
      probe.expectNoMsg(5900.milliseconds)
      probe.expectMsg(ShardStateChange(shards(1), ConnectionFailed))
    }

    it("should cleaned up any dead listeners") {

      val shards = Seq(
        Shard("server1", "localhost", 6379, Some(0)),
        Shard("server2", "localhost", 13579, Some(1)))

      val probe1 = TestProbe()
      val probe2 = TestProbe()

      val shardManager = TestActorRef(new ShardManager(shards, ShardManager.defaultHashFunction, Set(probe1.ref, probe2.ref))).underlyingActor
      assertResult(2)(shardManager.listeners.size)

      probe1.ref ! PoisonPill
      probe2.expectMsg(ShardStateChange(shards(0), Connected))

      assertResult(1)(shardManager.listeners.size)

    }

    it("should notify listeners when a shard fails to authenticate") {
      val shards = Seq(
        Shard("server1", "localhost", 6379, Some(0)),
        Shard("server2", "localhost", 6379, Some(1), auth = Some("not-valid-auth")))

      val probe = TestProbe()

      val shardManager = TestActorRef(new ShardManager(shards, ShardManager.defaultHashFunction, Set(probe.ref)))

      probe.expectMsg(ShardStateChange(shards(0), Connected))
      probe.expectMsg(ShardStateChange(shards(1), AuthenticationFailed))
    }
  }
}
