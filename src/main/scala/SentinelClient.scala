package brando

import akka.actor.{ Props, Stash, ActorRef, Actor }
import akka.pattern._
import akka.util.{ ByteString, Timeout }
import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._
import scala.language.postfixOps

case class SentinelConfig(host: String, port: Int)
class SentinelFailoverOccurredException(message: String) extends Exception(message)

object SentinelClient {
  def apply(sentinelConfigs: Seq[SentinelConfig], shardNames: Seq[String]): Props =
    Props(classOf[Brando], sentinelConfigs, shardNames)
}

/**
 * Sentinel Redis client for HA.
 * Redis allows a slave to replicate data from a master.
 * Sentinel allows failover from the master to the replicated slave.
 *
 * Behavior:
 * - Gets redis connection information from sentinel
 * - Watches for failover events and will restart to get up-to-date redis master info.
 * - Built on sharding to support both HA and distribution
 *
 * @param sentinelConfigs Give configuration(host/port) of each of the sentinel instances.
 * @param shardNames Provide the name of the shard that is in sentinel configuration.
 */

class SentinelClient(sentinelConfigs: Seq[SentinelConfig], shardNames: Seq[String]) extends Actor with Stash {
  def this(sentinels: Seq[SentinelConfig], masterName: String) = this(sentinels, Seq(masterName))
  def this(sentinel: SentinelConfig, masterName: String) = this(Seq(sentinel), Seq(masterName))
  def this(sentinel: SentinelConfig, shardNames: Seq[String]) = this(Seq(sentinel), shardNames)

  private case class ConnectToMasters(masters: List[Shard])

  implicit val timeout = Timeout(5 seconds)
  import context.dispatcher

  var shards: Seq[Shard] = _
  lazy val shardManager = context.actorOf(ShardManager(shards), "ShardManager")
  lazy val sentinelConnection: ActorRef = sentinelConfigs.map { x ⇒
    context.actorOf(Brando(x.host, x.port, None, None))
  }.dropWhile { x ⇒
    val result = Await.result(x ? Request("PING"), 300 millis)
    result != Some(Pong)
  }.headOption.getOrElse(throw new Error("cannot connect to sentinel(s)"))

  override def preStart() {
    self ! ("init", sentinelConnection)
  }

  /**
   * Uninitialized hotswap state
   * @return
   */
  def receive: Actor.Receive = {
    case ("init", sentinel: ActorRef) ⇒
      val requestsForMaster = shardNames.map { x ⇒
        (sentinel ? Request("SENTINEL", "get-master-addr-by-name", x)).map(y ⇒ (x, y))
      }

      val mastersConfig = Future.sequence(requestsForMaster)
      mastersConfig.onSuccess {
        case x: (List[(String, Some[List[Some[ByteString]]])]) ⇒
          shards = x.map {
            case (name, Some(List(Some(host: ByteString), Some(port: ByteString)))) ⇒
              Shard(name, host.utf8String, port.utf8String.toInt, None)
          }

          sentinel ! Request("SUBSCRIBE", "failover-end")
          sentinel ! Request("SUBSCRIBE", "-slave-restart-as-master")

          context become (online)
          unstashAll()
      }

    case msg ⇒
      stash()
  }

  /**
   * online hotswap state
   * @return
   */
  def online: Receive = {
    case request: ShardRequest ⇒
      shardManager forward request
    case message: PubSubMessage if message.channel == "failover-end" || message.channel == "-slave-restart-as-master" ⇒
      if (shards.exists { x ⇒ message.message.contains(x.id) })
        throw new SentinelFailoverOccurredException("Change to redis master so restarting sentinel actor: " + message)

    case msg ⇒
  }
}