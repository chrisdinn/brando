package brando

import concurrent.duration._
import akka.actor.{ ActorRef, Props }
import akka.pattern.ask
import akka.util.{ ByteString, Timeout }

case object NonRespondingShardRestarted extends BrandoStateChange

trait HealthMonitor extends ShardManager {

  val healthCheckRate: FiniteDuration

  implicit val ec = context.dispatcher
  implicit lazy val timeout = Timeout(healthCheckRate)

  override def preStart() = {
    context.system.scheduler.schedule(healthCheckRate, healthCheckRate)(healthCheck)
    super.preStart()
  }

  def healthCheck = {
    pool.foreach { case (id, shard) ⇒ checkShard(shard) }
  }

  private def checkShard(shardRef: ActorRef) = {
    def ping = (shardRef ? Request(ByteString("PING"))) map {
      case None ⇒ throw new Exception("Unexpected response")
      case _    ⇒
    }

    ping recoverWith { case e: Exception ⇒ ping } recover { case e: Exception ⇒ restartShard(shardRef) }
  }

  private def restartShard(shardRef: ActorRef) = {
    shardLookup.get(shardRef) map { shard ⇒
      listeners foreach { l ⇒ l ! ShardStateChange(shard, NonRespondingShardRestarted) }
      self ! shard
    }
  }
}
