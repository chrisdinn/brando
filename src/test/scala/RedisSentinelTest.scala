package brando

import akka.actor._
import akka.pattern._
import akka.testkit._

import scala.concurrent._
import scala.concurrent.duration._

import org.scalatest._

class RedisClientSentinelTest extends TestKit(ActorSystem("RedisClientSentinelTest")) with FunSpecLike
    with ImplicitSender {

  import RedisSentinel._
  import Sentinel._
  import Connection._

  describe("RedisClientSentinel") {
    describe("when connecting") {
      it("should use sentinel to resolve the ip and port") {
        val sentinelProbe = TestProbe()
        val brando = system.actorOf(
          RedisSentinel("mymaster", sentinelProbe.ref, 0, None))

        sentinelProbe.expectMsg(Request("SENTINEL", "MASTER", "mymaster"))
      }

      it("should connect to sentinel and redis") {
        val redisProbe = TestProbe()
        val sentinelProbe = TestProbe()

        val sentinel = system.actorOf(Sentinel(
          sentinels = Seq(Server("localhost", 26379)),
          listeners = Set(sentinelProbe.ref)))
        val brando = system.actorOf(RedisSentinel(
          master = "mymaster",
          sentinelClient = sentinel,
          listeners = Set(redisProbe.ref)))

        sentinelProbe.expectMsg(
          Connecting("localhost", 26379))
        sentinelProbe.expectMsg(
          Connected("localhost", 26379))
        redisProbe.expectMsg(
          Connecting("127.0.0.1", 6379))
        redisProbe.expectMsg(
          Connected("127.0.0.1", 6379))
      }
    }

    describe("when disconnected") {
      it("should recreate a connection using sentinel") {
        val redisProbe = TestProbe()
        val sentinelProbe = TestProbe()

        val sentinel = system.actorOf(Sentinel(
          sentinels = Seq(Server("localhost", 26379)),
          listeners = Set(sentinelProbe.ref)))
        val brando = system.actorOf(RedisSentinel(
          master = "mymaster",
          sentinelClient = sentinel,
          listeners = Set(redisProbe.ref)))

        sentinelProbe.expectMsg(
          Connecting("localhost", 26379))
        sentinelProbe.expectMsg(
          Connected("localhost", 26379))
        redisProbe.expectMsg(
          Connecting("127.0.0.1", 6379))
        redisProbe.expectMsg(
          Connected("127.0.0.1", 6379))

        brando ! Disconnected("127.0.0.1", 6379)

        redisProbe.expectMsg(
          Disconnected("127.0.0.1", 6379))
        redisProbe.expectMsg(
          Connecting("127.0.0.1", 6379))
        redisProbe.expectMsg(
          Connected("127.0.0.1", 6379))
      }

      it("should stash requests") {
        val redisProbe = TestProbe()
        val sentinelProbe = TestProbe()

        val sentinel = system.actorOf(Sentinel(
          sentinels = Seq(Server("localhost", 26379)),
          listeners = Set(sentinelProbe.ref)))
        val brando = system.actorOf(RedisSentinel(
          master = "mymaster",
          sentinelClient = sentinel,
          listeners = Set(redisProbe.ref)))

        redisProbe.expectMsg(
          Connecting("127.0.0.1", 6379))
        redisProbe.expectMsg(
          Connected("127.0.0.1", 6379))

        brando ! Disconnected("127.0.0.1", 6379)
        redisProbe.expectMsg(
          Disconnected("127.0.0.1", 6379))

        brando ! Request("PING")

        redisProbe.expectMsg(
          Connecting("127.0.0.1", 6379))
        redisProbe.expectMsg(
          Connected("127.0.0.1", 6379))

        expectMsg(Some(Pong))
      }
    }
  }
}

