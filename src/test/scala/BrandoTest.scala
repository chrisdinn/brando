package brando

import org.scalatest.{ FunSpec, BeforeAndAfterAll }
import akka.testkit._

import akka.actor._
import scala.concurrent.duration._
import akka.util.ByteString

class BrandoTest extends TestKit(ActorSystem("BrandoTest")) with FunSpec
    with ImplicitSender {
  import Reply.{ Ok, Pong }

  describe("ping") {
    it("should respond with Pong") {
      val brando = system.actorOf(Props[Brando])

      brando ! Request("PING")

      expectMsg(Pong)
    }
  }

  describe("flushdb") {
    it("should respond with OK") {
      val brando = system.actorOf(Props[Brando])

      brando ! Request("FLUSHDB")

      expectMsg(Ok)
    }
  }

  describe("set") {
    it("should respond with OK") {
      val brando = system.actorOf(Props[Brando])

      brando ! Request("SET", "mykey", "somevalue")

      expectMsg(Ok)

      brando ! Request("FLUSHDB")
      expectMsg(Ok)
    }
  }

  describe("get") {
    it("should respond with value option for existing key") {
      val brando = system.actorOf(Props[Brando])

      brando ! Request("SET", "mykey", "somevalue")

      expectMsg(Ok)

      brando ! Request("GET", "mykey")

      expectMsg(Some(ByteString("somevalue")))

      brando ! Request("FLUSHDB")
      expectMsg(Ok)
    }

    it("should respond with None for non-existent key") {
      val brando = system.actorOf(Props[Brando])

      brando ! Request("GET", "mykey")

      expectMsg(None)
    }
  }

  describe("incr") {
    it("should increment and return value for existing key") {
      val brando = system.actorOf(Props[Brando])

      brando ! Request("SET", "incr-test", "10")

      expectMsg(Ok)

      brando ! Request("INCR", "incr-test")

      expectMsg(Some(11))

      brando ! Request("FLUSHDB")
      expectMsg(Ok)
    }

    it("should return 1 for non-existent key") {
      val brando = system.actorOf(Props[Brando])

      brando ! Request("INCR", "incr-test")

      expectMsg(Some(1))

      brando ! Request("FLUSHDB")
      expectMsg(Ok)
    }
  }

  describe("sadd") {
    it("should return number of members added to set") {
      val brando = system.actorOf(Props[Brando])

      brando ! Request("SADD", "sadd-test", "one")

      expectMsg(Some(1))

      brando ! Request("SADD", "sadd-test", "two", "three")

      expectMsg(Some(2))

      brando ! Request("SADD", "sadd-test", "one", "four")

      expectMsg(Some(1))

      brando ! Request("FLUSHDB")
      expectMsg(Ok)
    }
  }

  describe("smembers") {
    it("should return all members in a set") {
      val brando = system.actorOf(Props[Brando])

      brando ! Request("SADD", "smembers-test", "one", "two", "three", "four")

      expectMsg(Some(4))

      brando ! Request("SMEMBERS", "smembers-test")

      val resp = receiveOne(500.millis).asInstanceOf[List[Any]]
      assert(resp.toSet ===
        Set(Some(ByteString("one")), Some(ByteString("two")),
          Some(ByteString("three")), Some(ByteString("four"))))

      brando ! Request("FLUSHDB")
      expectMsg(Ok)
    }

  }

  describe("piplining") {
    it("should respond to a Seq of multiple requests all at once") {
      val brando = system.actorOf(Props[Brando])
      val ping = Request("PING")

      brando ! List(ping, ping, ping)

      expectMsg(List(Pong, Pong, Pong))
    }
  }
}