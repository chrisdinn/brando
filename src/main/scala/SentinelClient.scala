package brando

import akka.actor.{ Stash, ActorRef, Actor }
import akka.pattern._
import akka.util.{ ByteString, Timeout }
import scala.concurrent.{ Future, Await }
import scala.concurrent.duration._
import scala.language.postfixOps

case class SentinelConfig(host: String, port: Int)
class SentinelFailoverOccurredException(message: String) extends Exception(message) //used for actor restart

/**
 * Sentinel solution - supports sharding (currently required).
 * @param sentinels
 * @param shardNames
 */

class SentinelClient(sentinels: Seq[SentinelConfig], shardNames: Seq[String]) extends Actor with Stash {
  def this(sentinels: Seq[SentinelConfig], masterName: String) = this(sentinels, Seq(masterName))
  def this(sentinel: SentinelConfig, masterName: String) = this(Seq(sentinel), Seq(masterName))
  def this(sentinel: SentinelConfig, shardNames: Seq[String]) = this(Seq(sentinel), shardNames)

  private case class ConnectToMasters(masters: List[Shard])

  implicit val timeout = Timeout(5 seconds)
  import context.dispatcher

  var shards: Seq[Shard] = _
  lazy val shardManager = context.actorOf(ShardManager(shards), "ShardManager")
  lazy val sentinelConnection: ActorRef = sentinels.map { x ⇒
    context.actorOf(Brando(x.host, x.port, None, None))
  }.dropWhile { x ⇒
    val result = Await.result(x ? Request("PING"), 300 millis)
    result != Some(Pong)
  }.headOption.getOrElse(throw new Error("cannot connect to sentinel(s)"))

  /**
   * initialization message ensures the actor gets redis info from sentinel on restart.
   */
  override def preStart() {
    self ! ("init", sentinelConnection)
  }

  /**
   * Uninitialized state
   * Init message will determine redis master(s) to connect to.
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
          //          sentinel ! Request("SUBSCRIBE", "+odown")
          //          sentinel ! Request("SUBSCRIBE", "-odown")

          context become (online)
          unstashAll()
      }

    case msg ⇒
      stash()
  }

  /**
   * Online state.
   * Rely on actor restart to get updated master information on failover.
   */
  def online: Receive = {
    case request: ShardRequest ⇒
      shardManager forward request
    case message: PubSubMessage if message.channel == "failover-end" ⇒
      //TODO - confirm the master is in use by this actor
      throw new SentinelFailoverOccurredException("Failover occurred so restarting sentinel: " + message)
    case msg ⇒
  }
}