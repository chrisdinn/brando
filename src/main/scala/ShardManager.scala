package brando

import akka.actor._
import akka.util._
import collection.mutable
import java.util.zip.CRC32

import scala.util.Failure
import scala.concurrent._
import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory
import java.util.concurrent.TimeUnit

object ShardManager {
  def apply(
    shards: Seq[Shard],
    listeners: Set[ActorRef] = Set(),
    sentinelClient: Option[ActorRef] = None,
    hashFunction: (Array[Byte] ⇒ Long) = defaultHashFunction,
    connectionTimeout: Option[FiniteDuration] = None,
    connectionRetryDelay: Option[FiniteDuration] = None,
    connectionHeartbeatDelay: Option[FiniteDuration] = None): Props = {

    val config = ConfigFactory.load()
    Props(classOf[ShardManager], shards, hashFunction, listeners, sentinelClient,
      connectionTimeout.getOrElse(
        config.getDuration("brando.connection.timeout", TimeUnit.MILLISECONDS).millis),
      connectionRetryDelay.getOrElse(
        config.getDuration("brando.connection.retry.delay", TimeUnit.MILLISECONDS).millis),
      connectionHeartbeatDelay)
  }

  def defaultHashFunction(input: Array[Byte]): Long = {
    val crc32 = new CRC32
    crc32.update(input)
    crc32.getValue
  }

  private[brando] trait Shard { val id: String }
  case class RedisShard(id: String, host: String,
    port: Int, database: Int = 0,
    auth: Option[String] = None) extends Shard
  case class SentinelShard(id: String, database: Int = 0,
    auth: Option[String] = None) extends Shard

  private[brando] case class SetShard(shard: Shard)
}

class ShardManager(
    shards: Seq[ShardManager.Shard],
    hashFunction: (Array[Byte] ⇒ Long),
    var listeners: Set[ActorRef] = Set(),
    sentinelClient: Option[ActorRef] = None,
    connectionTimeout: FiniteDuration,
    connectionRetryDelay: FiniteDuration,
    connectionHeartbeatDelay: Option[FiniteDuration]) extends Actor {

  import ShardManager._
  import context.dispatcher

  val pool = mutable.Map.empty[String, ActorRef]
  val shardLookup = mutable.Map.empty[ActorRef, Shard]
  var poolKeys: Seq[String] = Seq()

  override def preStart: Unit = {
    listeners.map(context.watch(_))
    shards.map(self ! SetShard(_))
  }

  def receive = {
    case (key: ByteString, request: Request) ⇒
      forward(key, request)

    case (key: String, request: Request) ⇒
      forward(ByteString(key), request)

    case request: Request ⇒
      request.params.length match {
        case 0 ⇒
          sender ! Failure(new IllegalArgumentException("Received empty Request params, can not shard without a key"))

        case s ⇒
          forward(request.params.head, request)
      }

    case broadcast: BroadcastRequest ⇒
      for ((_, shard) ← pool) {
        shard forward Request(broadcast.command, broadcast.params: _*)
      }

    case SetShard(shard) ⇒
      pool.get(shard.id) map (context.stop(_))
      (shard, sentinelClient) match {
        case (RedisShard(id, host, port, database, auth), _) ⇒
          val brando =
            context.actorOf(Redis(host, port, database, auth, listeners,
              Some(connectionTimeout), Some(connectionRetryDelay), None,
              connectionHeartbeatDelay))
          add(shard, brando)
        case (SentinelShard(id, database, auth), Some(sClient)) ⇒
          val brando =
            context.actorOf(RedisSentinel(id, sClient, database, auth,
              listeners, Some(connectionTimeout), Some(connectionRetryDelay),
              connectionHeartbeatDelay))
          add(shard, brando)
        case _ ⇒
      }

    case Terminated(l) ⇒
      listeners = listeners - l

    case _ ⇒
  }

  def forward(key: ByteString, req: Request) = {
    val s = sender
    Future { lookup(key).tell(req, s) }
  }

  def lookup(key: ByteString) = {
    val hash = hashFunction(key.toArray)
    val mod = hash % poolKeys.size
    val id = poolKeys(mod.toInt)
    pool(id)
  }

  def add(shard: Shard, brando: ActorRef) {
    shardLookup(brando) = shard
    pool(shard.id) = brando
    poolKeys = pool.keys.toIndexedSeq
  }
}
