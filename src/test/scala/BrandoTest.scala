package brando

import org.scalatest.FunSpecLike
import akka.testkit._

import akka.actor._
import akka.io.{ IO, Tcp }
import akka.util._
import akka.pattern._
import scala.concurrent.duration._
import java.util.UUID
import scala.concurrent._

class BrandoTest extends TestKit(ActorSystem("BrandoTest")) with FunSpecLike
    with ImplicitSender {

  describe("ping") {
    it("should respond with Pong") {
      val brando = system.actorOf(Brando())

      brando ! Request("PING")

      expectMsg(Some(Pong))
    }
  }

  describe("flushdb") {
    it("should respond with OK") {
      val brando = system.actorOf(Brando())

      brando ! Request("FLUSHDB")

      expectMsg(Some(Ok))
    }
  }

  describe("set") {
    it("should respond with OK") {
      val brando = system.actorOf(Brando())

      brando ! Request("SET", "mykey", "somevalue")

      expectMsg(Some(Ok))

      brando ! Request("FLUSHDB")
      expectMsg(Some(Ok))
    }
  }

  describe("get") {
    it("should respond with value option for existing key") {
      val brando = system.actorOf(Brando())

      brando ! Request("SET", "mykey", "somevalue")

      expectMsg(Some(Ok))

      brando ! Request("GET", "mykey")

      expectMsg(Some(ByteString("somevalue")))

      brando ! Request("FLUSHDB")
      expectMsg(Some(Ok))
    }

    it("should respond with None for non-existent key") {
      val brando = system.actorOf(Brando())

      brando ! Request("GET", "mykey")

      expectMsg(None)
    }
  }

  describe("incr") {
    it("should increment and return value for existing key") {
      val brando = system.actorOf(Brando())

      brando ! Request("SET", "incr-test", "10")

      expectMsg(Some(Ok))

      brando ! Request("INCR", "incr-test")

      expectMsg(Some(11))

      brando ! Request("FLUSHDB")
      expectMsg(Some(Ok))
    }

    it("should return 1 for non-existent key") {
      val brando = system.actorOf(Brando())

      brando ! Request("INCR", "incr-test")

      expectMsg(Some(1))

      brando ! Request("FLUSHDB")
      expectMsg(Some(Ok))
    }
  }

  describe("sadd") {
    it("should return number of members added to set") {
      val brando = system.actorOf(Brando())

      brando ! Request("SADD", "sadd-test", "one")

      expectMsg(Some(1))

      brando ! Request("SADD", "sadd-test", "two", "three")

      expectMsg(Some(2))

      brando ! Request("SADD", "sadd-test", "one", "four")

      expectMsg(Some(1))

      brando ! Request("FLUSHDB")
      expectMsg(Some(Ok))
    }
  }

  describe("smembers") {
    it("should return all members in a set") {
      val brando = system.actorOf(Brando())

      brando ! Request("SADD", "smembers-test", "one", "two", "three", "four")

      expectMsg(Some(4))

      brando ! Request("SMEMBERS", "smembers-test")

      val resp = receiveOne(500.millis).asInstanceOf[Option[List[Any]]]
      assert(resp.getOrElse(List()).toSet ===
        Set(Some(ByteString("one")), Some(ByteString("two")),
          Some(ByteString("three")), Some(ByteString("four"))))

      brando ! Request("FLUSHDB")
      expectMsg(Some(Ok))
    }

  }

  describe("pipelining") {
    it("should respond to a Seq of multiple requests all at once") {
      val brando = system.actorOf(Brando())
      val ping = Request("PING")

      brando ! ping
      brando ! ping
      brando ! ping

      expectMsg(Some(Pong))
      expectMsg(Some(Pong))
      expectMsg(Some(Pong))

    }

    it("should support pipelines of setex commands") {
      val brando = system.actorOf(Brando())
      val setex = Request("SETEX", "pipeline-setex-path", "10", "Some data")

      brando ! setex
      brando ! setex
      brando ! setex

      expectMsg(Some(Ok))
      expectMsg(Some(Ok))
      expectMsg(Some(Ok))
    }

    it("should receive responses in the right order") {
      val brando = system.actorOf(Brando())
      val ping = Request("PING")
      val setex = Request("SETEX", "pipeline-setex-path", "10", "Some data")

      brando ! setex
      brando ! ping
      brando ! setex
      brando ! ping
      brando ! setex

      expectMsg(Some(Ok))
      expectMsg(Some(Pong))
      expectMsg(Some(Ok))
      expectMsg(Some(Pong))
      expectMsg(Some(Ok))
    }
  }

  describe("large data sets") {
    it("should read and write large files") {
      import java.io.{ File, FileInputStream }

      val file = new File("src/test/resources/crime_and_punishment.txt")
      val in = new FileInputStream(file)
      val bytes = new Array[Byte](file.length.toInt)
      in.read(bytes)
      in.close()

      val largeText = new String(bytes, "UTF-8")

      val brando = system.actorOf(Brando())

      brando ! Request("SET", "crime+and+punishment", largeText)

      expectMsg(Some(Ok))

      brando ! Request("GET", "crime+and+punishment")

      expectMsg(Some(ByteString(largeText)))

      brando ! Request("FLUSHDB")
      expectMsg(Some(Ok))
    }
  }

  describe("error reply") {
    it("should receive a failure with the redis error message") {
      val brando = system.actorOf(Brando())

      brando ! Request("SET", "key")

      expectMsgPF(5.seconds) {
        case Status.Failure(e) ⇒
          assert(e.isInstanceOf[BrandoException])
          assert(e.getMessage === "ERR wrong number of arguments for 'set' command")
      }

      brando ! Request("EXPIRE", "1", "key")

      expectMsgPF(5.seconds) {
        case Status.Failure(e) ⇒
          assert(e.isInstanceOf[BrandoException])
          assert(e.getMessage === "ERR value is not an integer or out of range")
      }
    }
  }

  describe("select") {
    it("should execute commands on the selected database") {
      val brando = system.actorOf(Brando("localhost", 6379, Some(5)))

      brando ! Request("SET", "mykey", "somevalue")

      expectMsg(Some(Ok))

      brando ! Request("GET", "mykey")

      expectMsg(Some(ByteString("somevalue")))

      brando ! Request("SELECT", "0")

      expectMsg(Some(Ok))

      brando ! Request("GET", "mykey")

      expectMsg(None)

      brando ! Request("SELECT", "5")
      expectMsg(Some(Ok))

      brando ! Request("FLUSHDB")
      expectMsg(Some(Ok))
    }
  }

  describe("multi") {
    it("should return the multi responses after the exec command") {
      val brando = system.actorOf(Brando("localhost", 6379, Some(5)))

      brando ! Request("MULTI")
      expectMsg(Some(Ok))

      brando ! Request("SET", "mykey", "somevalue")
      expectMsg(Some(Queued))

      brando ! Request("GET", "mykey")
      expectMsg(Some(Queued))

      brando ! Request("EXEC")
      expectMsg(Some(List(Some(Ok), Some(ByteString("somevalue")))))
    }

    it("should pipeline multi requests") {
      val brando = system.actorOf(Brando("localhost", 6379, Some(5)))

      brando ! Request("MULTI")
      brando ! Request("SET", "mykey", "somevalue")
      brando ! Request("GET", "mykey")
      brando ! Request("EXEC")

      expectMsg(Some(Ok))
      expectMsg(Some(Queued))
      expectMsg(Some(Queued))
      expectMsg(Some(List(Some(Ok), Some(ByteString("somevalue")))))
    }

  }

  describe("blocking requests") {
    describe("subscribe") {

      it("should be able to subscribe to a pubsub channel") {
        val channel = UUID.randomUUID().toString
        val subscriber = system.actorOf(Brando())

        subscriber ! Request("SUBSCRIBE", channel)

        expectMsg(Some(List(Some(
          ByteString("subscribe")),
          Some(ByteString(channel)),
          Some(1))))
      }

      it("should receive published messages from a pubsub channel") {
        val channel = UUID.randomUUID().toString
        val subscriber = system.actorOf(Brando())
        val publisher = system.actorOf(Brando())

        subscriber ! Request("SUBSCRIBE", channel)

        expectMsg(Some(List(Some(
          ByteString("subscribe")),
          Some(ByteString(channel)),
          Some(1))))

        publisher ! Request("PUBLISH", channel, "test")
        expectMsg(Some(1)) //publisher gets back number of subscribers when publishing

        expectMsg(PubSubMessage(channel, "test"))
      }

      it("should be able to unsubscribe from a pubsub channel") {
        val channel = UUID.randomUUID().toString
        val subscriber = system.actorOf(Brando())
        val publisher = system.actorOf(Brando())

        subscriber ! Request("SUBSCRIBE", channel)

        expectMsg(Some(List(Some(
          ByteString("subscribe")),
          Some(ByteString(channel)),
          Some(1))))

        subscriber ! Request("UNSUBSCRIBE", channel)

        expectMsg(Some(List(Some(
          ByteString("unsubscribe")),
          Some(ByteString(channel)),
          Some(0))))

        publisher ! Request("PUBLISH", channel, "test")
        expectMsg(Some(0))

        expectNoMsg
      }
    }

    describe("should be able to block on blpop") {
      val brando = system.actorOf(Brando())
      try {
        val channel = UUID.randomUUID().toString
        val popBrando = system.actorOf(Brando())
        popBrando ! Request("BLPOP", "blpop:list", "0")

        expectNoMsg

        brando ! Request("LPUSH", "blpop:list", "blpop-value")
        expectMsgType[Option[Long]]

        expectMsg(Some(List(Some(
          ByteString("blpop:list")),
          Some(ByteString("blpop-value")))))

      } finally {
        implicit val timeout = Timeout(1.seconds)
        Await.ready((brando ? Request("del", "blpop:list")), 1.seconds)
      }
    }
  }

  describe("State notifications") {

    it("should send an Authenticated event if connecting succeeds") {
      val probe = TestProbe()
      val brando = system.actorOf(Brando("localhost", 6379, listeners = Set(probe.ref)))

      probe.expectMsg(Connected)
    }

    it("should send an ConnectionFailed event if connecting fails after the configured number of retries") {
      val probe = TestProbe()
      val brando = system.actorOf(Brando("localhost", 13579, listeners = Set(probe.ref)))

      //3 retries * 2 seconds = 6 seconds
      probe.expectNoMsg(5900.milliseconds)
      probe.expectMsg(ConnectionFailed)
    }

    it("should send an AuthenticationFailed event if connecting succeeds but authentication fails") {
      val probe = TestProbe()
      val brando = system.actorOf(Brando("localhost", 6379, auth = Some("not-the-auth"), listeners = Set(probe.ref)))

      probe.expectMsg(AuthenticationFailed)
    }

    it("should send a notification to later added listener") {
      val probe = TestProbe()
      val probe2 = TestProbe()
      val brando = system.actorOf(Brando("localhost", 13579, listeners = Set(probe2.ref)))
      brando ! probe.ref

      //3 retries * 2 seconds = 6 seconds
      probe.expectNoMsg(5900.milliseconds)
      probe2.expectMsg(ConnectionFailed)
      probe.expectMsg(ConnectionFailed)
    }
  }

  describe("Connection") {
    it("should keep retrying to connect if brando.connection_attempts is not defined") {
      val socket = TestProbe()
      val brando = TestProbe()
      val address = new java.net.InetSocketAddress("test.com", 16379)
      val connection = TestActorRef(new Connection(brando.ref, address, 1000000, None))

      for (i ← 1 to 10) {
        connection ! Tcp.CommandFailed(Tcp.Connect(address))
        assert(connection.underlyingActor.connectionAttempts === i)
      }
      brando.expectNoMsg
    }
  }
}
