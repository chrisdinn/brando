package brando

import akka.actor._
import akka.util._
import akka.pattern._
import akka.testkit._

import scala.concurrent._
import scala.concurrent.duration._

import org.scalatest._

class SentinelTest extends TestKit(ActorSystem("SentinelTest")) with FunSpecLike
    with ImplicitSender {

  import Sentinel._
  import Connection._

  describe("Sentinel") {
    describe("connection to sentinel instances") {
      it("should connect to the first working sentinel instance") {
        val probe = TestProbe()
        val sentinel = system.actorOf(Sentinel(Seq(
          Server("wrong-host", 26379),
          Server("localhost", 26379)), Set(probe.ref)))

        probe.expectMsg(Connecting("wrong-host", 26379))
        probe.expectMsg(Connecting("localhost", 26379))
        probe.expectMsg(Connected("localhost", 26379))
      }

      it("should send a notification to the listeners when connecting") {
        val probe = TestProbe()
        val sentinel = system.actorOf(Sentinel(Seq(
          Server("localhost", 26379)),
          Set(probe.ref)))

        probe.expectMsg(Connecting("localhost", 26379))
      }

      it("should send a notification to the listeners when connected") {
        val probe = TestProbe()
        val sentinel = system.actorOf(Sentinel(Seq(
          Server("localhost", 26379)), Set(probe.ref)))

        probe.receiveN(1)
        probe.expectMsg(Connected("localhost", 26379))
      }

      it("should send a notification to the listeners when disconnected") {
        val probe = TestProbe()
        val sentinel = system.actorOf(Sentinel(Seq(
          Server("localhost", 26379)), Set(probe.ref)))

        probe.receiveN(1)
        probe.expectMsg(Connected("localhost", 26379))

        sentinel ! Disconnected("localhost", 26379)

        probe.expectMsg(Disconnected("localhost", 26379))
      }

      it("should send a notification to the listeners for connection failure") {
        val probe = TestProbe()
        val sentinels = Seq(Server("wrong-host", 26379))
        val sentinel = system.actorOf(Sentinel(sentinels, Set(probe.ref)))

        probe.receiveN(1)
        probe.expectMsg(Sentinel.ConnectionFailed(sentinels))
      }

      it("should make sure the working instance will be tried first next reconnection") {
        val probe = TestProbe()
        val sentinel = system.actorOf(Sentinel(Seq(
          Server("wrong-host", 26379),
          Server("localhost", 26379)), Set(probe.ref)))

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
          Server("wrong-host-1", 26379),
          Server("wrong-host-2", 26379))
        val sentinel = system.actorOf(Sentinel(sentinels.reverse, Set(probe.ref)))

        probe.receiveN(2)
        probe.expectMsg(Sentinel.ConnectionFailed(sentinels))
      }
    }

    describe("Request") {
      it("should return a failure when disconnected") {
        val sentinel = system.actorOf(Sentinel(Seq(
          Server("localhost", 26379))))

        sentinel ! Request("PING")

        expectMsg(Status.Failure(new RedisException("Disconnected from the sentinel cluster")))

      }
    }

    describe("Subscriptions") {
      it("should receive pub/sub notifications") {
        val sentinel = system.actorOf(Sentinel(Seq(
          Server("localhost", 26379)), Set(self)))
        val sentinel2 = system.actorOf(Sentinel(Seq(
          Server("localhost", 26379)), Set(self)))

        expectMsg(Connecting("localhost", 26379))
        expectMsg(Connecting("localhost", 26379))
        expectMsg(Connected("localhost", 26379))
        expectMsg(Connected("localhost", 26379))

        sentinel ! Request("subscribe", "+failover-end")

        expectMsg(Some(List(
          Some(ByteString("subscribe")),
          Some(ByteString("+failover-end")),
          Some(1))))

        sentinel2 ! Request("sentinel", "failover", "mymaster")
        expectMsg(Some(Ok))

        expectMsg(PubSubMessage("+failover-end", "master mymaster 127.0.0.1 6379"))

        Thread.sleep(2000)

        sentinel2 ! Request("sentinel", "failover", "mymaster")
        expectMsg(Some(Ok))

        expectMsg(PubSubMessage("+failover-end", "master mymaster 127.0.0.1 6380"))
      }
    }
  }
}
