package brando

import java.util.zip.CRC32
import collection.mutable
import akka.actor.{ Actor, ActorRef }

case class Shard(id: String, host: String, port: Int, database: Option[Int] = None, auth: Option[String] = None)

class ShardManager(shards: Seq[Shard]) extends Actor {
  val pool = mutable.Map.empty[String, ActorRef]

  shards.map(create(_))

  def receive = {
    case request: ShardRequest ⇒
      val client = lookup(request.key)
      client forward Request(request.command, (request.key +: request.params): _*)

    case shard: Shard ⇒
      pool.get(shard.id) match {
        case Some(client) ⇒
          context.stop(client)
          create(shard)

        case _ ⇒
          println("Update received for unknown shard ID " + shard.id + "\r\n")
      }

    case x ⇒ println("ShardManager received unexpected " + x + "\r\n")
  }

  def lookup(key: String) = {
    val crc32 = new CRC32
    crc32.update(key.getBytes)
    val mod = crc32.getValue % pool.size
    val id = pool.keys.toIndexedSeq(mod.toInt)
    pool(id)
  }

  def create(shard: Shard) {
    val client = context.actorOf(Brando(shard.host, shard.port, shard.database, shard.auth))
    pool(shard.id) = client
  }
}
