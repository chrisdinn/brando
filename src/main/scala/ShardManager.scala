package brando

import akka.actor.{ Actor, ActorRef, Props, Terminated }
import akka.util.ByteString
import collection.mutable
import java.util.zip.CRC32
import concurrent.Future
import concurrent.duration.FiniteDuration
import scala.util.Failure

case class Shard(id: String, host: String, port: Int, database: Option[Int] = None, auth: Option[String] = None)

case class ShardStateChange(shard: Shard, state: BrandoStateChange)

object ShardManager {
  def defaultHashFunction(input: Array[Byte]): Long = {
    val crc32 = new CRC32
    crc32.update(input)
    crc32.getValue
  }

  def apply(shards: Seq[Shard],
    hashFunction: (Array[Byte] ⇒ Long) = defaultHashFunction,
    listeners: Set[ActorRef] = Set()): Props = {
    Props(classOf[ShardManager], shards, hashFunction, listeners)
  }

  def withHealthMonitor(shards: Seq[Shard],
    healthChkRate: FiniteDuration,
    hashFunction: (Array[Byte] ⇒ Long) = defaultHashFunction,
    listeners: Set[ActorRef] = Set()): Props = {
    Props(new ShardManager(shards, hashFunction, listeners) with HealthMonitor { val healthCheckRate = healthChkRate })
  }
}

class ShardManager(
    shards: Seq[Shard],
    hashFunction: (Array[Byte] ⇒ Long),
    private[brando] var listeners: Set[ActorRef] = Set()) extends Actor {

  import context.dispatcher

  val pool = mutable.Map.empty[String, ActorRef]
  val shardLookup = mutable.Map.empty[ActorRef, Shard]

  shards.map(create(_))
  listeners.map(context.watch(_))

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

    case shard: Shard ⇒
      pool.get(shard.id) match {
        case Some(client) ⇒
          context.stop(client)
          create(shard)

        case _ ⇒
          println("Update received for unknown shard ID " + shard.id + "\r\n")
      }

    case stateChange: BrandoStateChange ⇒
      shardLookup.get(sender) match {
        case Some(shard) ⇒
          listeners foreach { l ⇒ l ! ShardStateChange(shard, stateChange) }
        case None ⇒ println("Update received for unknown shard actorRef " + sender + "\r\n")
      }

    case Terminated(l) ⇒
      listeners = listeners - l

    case x ⇒ println("ShardManager received unexpected " + x + "\r\n")
  }

  def forward(key: ByteString, req: Request) =
    Future(lookup(key)).map(_ forward req)

  def lookup(key: ByteString) = {
    val hash = hashFunction(key.toArray)
    val mod = hash % pool.size
    val id = pool.keys.toIndexedSeq(mod.toInt)
    pool(id)
  }

  def create(shard: Shard) {
    val client = context.actorOf(
      Brando(shard.host, shard.port, shard.database, shard.auth, Set(self)))
    pool(shard.id) = client
    shardLookup(client) = shard
  }
}
