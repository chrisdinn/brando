package brando

import akka.actor._
import akka.testkit._
import org.scalatest.FunSpecLike

class StashingRedisClientTest extends TestKit(ActorSystem("StashingRedisClientTest")) with FunSpecLike with ImplicitSender {

  import Connection._

  describe("stashing client should") {
    it("respond with Pong after connected") {
      val brando = system.actorOf(Redis(listeners = Set(self)))
      val stashing = system.actorOf(StashingRedis(brando))
      expectMsg(Connecting("localhost", 6379))
      expectMsg(Connected("localhost", 6379))
      stashing ! Request("PING")
      expectMsg(Some(Pong))
    }

    it("respond with Pong before connected") {
      val brando = system.actorOf(Redis(listeners = Set(self)))
      val stashing = system.actorOf(StashingRedis(brando))
      stashing ! Request("PING")
      expectMsg(Connecting("localhost", 6379))
      expectMsg(Connected("localhost", 6379))
      expectMsg(Some(Pong))
    }
  }
}
