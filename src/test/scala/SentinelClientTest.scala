package brando

import akka.actor._
import akka.pattern._
import akka.testkit._

import scala.concurrent._
import scala.concurrent.duration._

import org.scalatest._

class SentinelClientTest extends TestKit(ActorSystem("SentinelTest")) with FunSpecLike
    with ImplicitSender {

  import SentinelClient._
  import Connection._

  describe("SentinelClient") {
    describe("connection to sentinel instances") {
      it("should connect to the first working sentinel instance") {
        val probe = TestProbe()
        val sentinel = system.actorOf(SentinelClient(Seq(
          Sentinel("wrong-host", 26379),
          Sentinel("localhost", 26379)), Set(probe.ref)))

        probe.expectMsg(Connecting("wrong-host", 26379))
        probe.expectMsg(Connecting("localhost", 26379))
        probe.expectMsg(Connected("localhost", 26379))
      }

      it("should send a notification to the listeners when connecting") {
        val probe = TestProbe()
        val sentinel = system.actorOf(SentinelClient(Seq(
          Sentinel("localhost", 26379)),
          Set(probe.ref)))

        probe.expectMsg(Connecting("localhost", 26379))
      }

      it("should send a notification to the listeners when connected") {
        val probe = TestProbe()
        val sentinel = system.actorOf(SentinelClient(Seq(
          Sentinel("localhost", 26379)), Set(probe.ref)))

        probe.receiveN(1)
        probe.expectMsg(Connected("localhost", 26379))
      }

      it("should send a notification to the listeners when disconnected") {
        val probe = TestProbe()
        val sentinel = system.actorOf(SentinelClient(Seq(
          Sentinel("localhost", 26379)), Set(probe.ref)))

        probe.receiveN(1)
        probe.expectMsg(Connected("localhost", 26379))

        sentinel ! Disconnected("localhost", 26379)

        probe.expectMsg(Disconnected("localhost", 26379))
      }

      it("should send a notification to the listeners for connection failure") {
        val probe = TestProbe()
        val sentinels = Seq(Sentinel("wrong-host", 26379))
        val sentinel = system.actorOf(SentinelClient(sentinels, Set(probe.ref)))

        probe.receiveN(1)
        probe.expectMsg(SentinelClient.ConnectionFailed(sentinels))
      }

      it("should make sure the working instance will be tried first next reconnection") {
        val probe = TestProbe()
        val sentinel = system.actorOf(SentinelClient(Seq(
          Sentinel("wrong-host", 26379),
          Sentinel("localhost", 26379)), Set(probe.ref)))

        probe.expectMsg(Connecting("wrong-host", 26379))
        probe.expectMsg(Connecting("localhost", 26379))
        probe.expectMsg(Connected("localhost", 26379))

        sentinel ! Disconnected("localhost", 26379)

        probe.expectMsg(Disconnected("localhost", 26379))

        probe.expectMsg(Connecting("localhost", 26379))
      }

      it("should send a notification to the listeners if it can't connect to any instance") {
        val probe = TestProbe()
        val sentinels = Seq(
          Sentinel("wrong-host-1", 26379),
          Sentinel("wrong-host-2", 26379))
        val sentinel = system.actorOf(SentinelClient(sentinels.reverse, Set(probe.ref)))

        probe.receiveN(2)
        probe.expectMsg(SentinelClient.ConnectionFailed(sentinels))
      }
    }

    describe("Request") {
      it("should stash requests when disconnected") {
        val probe = TestProbe()
        val sentinel = system.actorOf(SentinelClient(Seq(
          Sentinel("localhost", 26379)), Set(probe.ref)))

        probe.expectMsg(Connecting("localhost", 26379))
        probe.expectMsg(Connected("localhost", 26379))

        sentinel ! Disconnected("localhost", 26379)
        probe.expectMsg(Disconnected("localhost", 26379))

        sentinel ! Request("PING")

        probe.expectMsg(Connecting("localhost", 26379))
        probe.expectMsg(Connected("localhost", 26379))

        expectMsg(Some(Pong))
      }
    }
  }
}

