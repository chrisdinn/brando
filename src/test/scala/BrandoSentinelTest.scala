package brando

import akka.actor._
import akka.pattern._
import akka.testkit._

import scala.concurrent._
import scala.concurrent.duration._

import org.scalatest._

class BrandoSentinelTest extends TestKit(ActorSystem("SentinelTest")) with FunSpecLike
    with ImplicitSender {

  import BrandoSentinel._
  import SentinelClient._
  import Connection._

  describe("BrandoSentinel") {
    describe("when connecting") {
      it("should use sentinel to resolve the ip and port") {
        val brandoProbe = TestProbe()
        val sentinelProbe = TestProbe()
        val brando = system.actorOf(BrandoSentinel("mymaster", sentinelProbe.ref, 0, None, Set(brandoProbe.ref)))

        sentinelProbe.expectMsg(Request("SENTINEL", "MASTER", "mymaster"))
      }

      it("should connect to sentinel and redis") {
        val brandoProbe = TestProbe()
        val sentinelProbe = TestProbe()
        val sentinel = system.actorOf(SentinelClient(Seq(
          Sentinel("localhost", 26379)), Set(sentinelProbe.ref)))
        val brando = system.actorOf(BrandoSentinel("mymaster", sentinel, 0, None, Set(brandoProbe.ref)))

        sentinelProbe.expectMsg(
          Connecting("localhost", 26379))
        sentinelProbe.expectMsg(
          Connected("localhost", 26379))
        brandoProbe.expectMsg(
          Connecting("127.0.0.1", 6379))
        brandoProbe.expectMsg(
          Connected("127.0.0.1", 6379))
      }
    }

    describe("when disconnected") {
      it("should recreate a connection using sentinel") {
        val brandoProbe = TestProbe()
        val sentinelProbe = TestProbe()
        val sentinel = system.actorOf(SentinelClient(Seq(
          Sentinel("localhost", 26379)), Set(sentinelProbe.ref)))
        val brando = system.actorOf(BrandoSentinel("mymaster", sentinel, 0, None, Set(brandoProbe.ref)))

        sentinelProbe.expectMsg(
          Connecting("localhost", 26379))
        sentinelProbe.expectMsg(
          Connected("localhost", 26379))
        brandoProbe.expectMsg(
          Connecting("127.0.0.1", 6379))
        brandoProbe.expectMsg(
          Connected("127.0.0.1", 6379))

        brando ! Disconnected("127.0.0.1", 6379)

        brandoProbe.expectMsg(
          Disconnected("127.0.0.1", 6379))
        brandoProbe.expectMsg(
          Connecting("127.0.0.1", 6379))
        brandoProbe.expectMsg(
          Connected("127.0.0.1", 6379))
      }

      it("should stash requests") {
        val brandoProbe = TestProbe()
        val sentinelProbe = TestProbe()
        val sentinel = system.actorOf(SentinelClient(Seq(
          Sentinel("localhost", 26379)), Set(sentinelProbe.ref)))
        val brando = system.actorOf(BrandoSentinel("mymaster", sentinel, 0, None, Set(brandoProbe.ref)))

        brandoProbe.expectMsg(
          Connecting("127.0.0.1", 6379))
        brandoProbe.expectMsg(
          Connected("127.0.0.1", 6379))

        brando ! Disconnected("127.0.0.1", 6379)
        brandoProbe.expectMsg(
          Disconnected("127.0.0.1", 6379))

        brando ! Request("PING")

        brandoProbe.expectMsg(
          Connecting("127.0.0.1", 6379))
        brandoProbe.expectMsg(
          Connected("127.0.0.1", 6379))

        expectMsg(Some(Pong))
      }
    }
  }
}

