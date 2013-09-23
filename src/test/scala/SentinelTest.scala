package brando

import akka.testkit.{ ImplicitSender, TestKit }
import akka.actor._
import akka.pattern._
import brando.PubSubMessage
import brando.SentinelConfig
import scala.concurrent.duration._
import org.scalatest.FunSpec
import akka.util.{ ByteString, Timeout }
import scala.concurrent.Await
import scala.collection.JavaConversions._
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

//
///**
// * Class with stub of sentinel connection
// * @param sentinels
// * @param shardNames
// */
//class TestSentinelClient(sentinels: Seq[SentinelConfig], shardNames: Seq[String])
//    extends SentinelClient(sentinels, shardNames) {
//  override lazy val sentinelConnection: ActorRef = context.actorOf(Props(classOf[SentinelConnectionStub]))
//}
//
///**
// * Stubbed sentinel connection
// */
//class SentinelConnectionStub extends Actor {
//  var senderRef: ActorRef = _
//  def receive: Actor.Receive = {
//    case r: Request if r.command == ByteString("SENTINEL") && r.params.head == ByteString("get-master-addr-by-name") ⇒
//      println("got the request for master")
//      sender ! Seq("shard1", None)
//    case Request(command: ByteString, params: Seq[ByteString]) if command == ByteString("subscribe") && params.head == ByteString("failover-end") ⇒
//      senderRef = sender
//    case Request(command: ByteString) if command == ByteString("failmeover") ⇒
//      println("Failme over!!")
//      senderRef ! PubSubMessage("failover-end", "fish goes blub, seal goes ow ow ow.")
//    case msg: Request ⇒ println(msg.command.utf8String + msg.params.head.utf8String)
//  }
//}