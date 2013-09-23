package brando

import org.scalatest.FunSpec

import akka.actor._
import akka.testkit.{ ImplicitSender, TestKit }
import akka.util.{ ByteString, Timeout }
import scala.concurrent.duration._
import scala.Some

/**
 * Test cases for sentinel client
 */
class SentinelTest extends TestKit(ActorSystem("SentinelTest")) with FunSpec
    with ImplicitSender {

  implicit val timeout = Timeout(5 seconds)

  val sentinelConfig = SentinelConfig("localhost", 26379)

  def newSentinelClient = system.actorOf(Props(classOf[SentinelClient], sentinelConfig, Seq("shard1")))

  describe("set") {
    it("should respond with OK") {
      val shardManager = newSentinelClient

      shardManager ! ShardRequest("SET", "shard_manager_test", "some value")

      expectMsg(Some(Ok))

      shardManager ! ShardRequest("GET", "shard_manager_test")

      expectMsg(Some(ByteString("some value")))
    }
  }

  describe("failover") {
    it("should recover from failoev") {

      val shardManager = newSentinelClient

      shardManager ! PubSubMessage("failover-end", "failover-end")

      shardManager ! ShardRequest("SET", "shard_manager_test", "some value")

      expectMsg(Some(Ok))

      shardManager ! ShardRequest("GET", "shard_manager_test")

      expectMsg(Some(ByteString("some value")))
    }
  }
}