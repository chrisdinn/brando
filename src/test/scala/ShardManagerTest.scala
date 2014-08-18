package brando

import org.scalatest.FunSpec
import akka.testkit._

import akka.actor._
import akka.util.ByteString
import scala.concurrent.duration._

class ShardManagerTest extends TestKit(ActorSystem("ShardManagerTest")) with FunSpec with ImplicitSender {
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

    it("should broadcast a request to all shards") {
      val shards = Seq(
        Shard("server1", "localhost", 6379, Some(0)),
        Shard("server2", "localhost", 6379, Some(1)),
        Shard("server3", "localhost", 6379, Some(2)))

      val shardManager = TestActorRef(new ShardManager(shards, ShardManager.defaultHashFunction))

      shardManager ! ShardBroadcast(Request("RPUSH", "shard_list_test", "some value"))

      for (i ← 1 to shards.length)
        expectMsgPF(1.second) { case Some(n: java.lang.Long) ⇒ true }

      shardManager ! ShardBroadcast(Request("RPOP", "shard_list_test"))

      for (i ← 1 to shards.length) expectMsg(Some(ByteString("some value")))

      shardManager ! ShardBroadcast(Request("DEL", "shard_list_test"))

      for (i ← 1 to shards.length) expectMsg(Some(0))
    }

    it("should override the shard key when requested") {
      val shards = Seq(
        Shard("server1", "localhost", 6379, Some(0)),
        Shard("server2", "localhost", 6379, Some(1)),
        Shard("server3", "localhost", 6379, Some(2)))

      val shardManager = TestActorRef(new ShardManager(shards, ShardManager.defaultHashFunction))

      shardManager ! ShardRequest.withKeyOverride("override_key", "RPUSH", "override_list", "a_value")

      expectMsgPF(1.second) { case Some(n: java.lang.Long) ⇒ true }

      shardManager ! ShardRequest.withKeyOverride("override_key", "RPOP", "override_list")

      expectMsg(Some(ByteString("a_value")))
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
