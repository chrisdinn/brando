package brando

import org.scalatest.{ FunSpecLike, BeforeAndAfterAll }
import akka.testkit._
import akka.actor._
import scala.concurrent.duration._
import akka.util.ByteString
import collection.mutable

class TestHealthMonitor(responder: ActorRef, listeners: Set[ActorRef])
    extends ShardManager(Seq(), ShardManager.defaultHashFunction, listeners)
    with HealthMonitor {

  val shard = Shard("1", "localhost", 6379)

  override val healthCheckRate = 500.milliseconds

  override val pool = mutable.Map("1" -> responder)
  override val shardLookup = mutable.Map(responder -> shard)
}

class HealthMonitorTest extends TestKit(ActorSystem("HealthMonitorTest"))
    with FunSpecLike with BeforeAndAfterAll {

  override def afterAll { system.shutdown() }

  describe("the health monitor") {

    it("should send pings to the redis shard") {
      val probe = TestProbe()

      val manager = TestActorRef(new TestHealthMonitor(probe.ref, Set()))

      probe.expectMsg(Request("PING"))
      probe.expectMsg(Request("PING"))
      manager ! PoisonPill
    }

    it("should cleaned up any dead listeners") {

      val probe1 = TestProbe()
      val probe2 = TestProbe()

      val brando = TestActorRef(new Brando("localhost", 6379, None, None, listeners = Set(probe1.ref, probe2.ref))).underlyingActor
      assertResult(2)(brando.listeners.size)

      probe1.ref ! PoisonPill
      probe2.expectMsg(Connected)

      assertResult(1)(brando.listeners.size)

    }

    it("should restart the shard, and notify, when healthcheck fails") {
      val probe = TestProbe()
      val listener = TestProbe()

      val manager = TestActorRef(new TestHealthMonitor(probe.ref, Set(listener.ref)))

      val shard = manager.underlyingActor.shard

      listener.expectMsg(ShardStateChange(shard, NonRespondingShardRestarted))
      manager ! PoisonPill
    }
  }
}
