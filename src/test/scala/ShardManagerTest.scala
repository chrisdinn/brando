package brando

import org.scalatest.{ FunSpec }
import akka.testkit._

import akka.actor._
import akka.actor.Status._
import scala.concurrent.duration._
import akka.util.ByteString

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
  }
}
