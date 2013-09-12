package brando

import akka.actor.{ Actor, ActorRef, Props }
import akka.util.ByteString
import collection.mutable
import java.util.zip.CRC32

case class Shard(id: String, host: String, port: Int, database: Option[Int] = None, auth: Option[String] = None)

object ShardManager {
  def defaultHashFunction(input: Array[Byte]): Long = {
    val crc32 = new CRC32
    crc32.update(input)
    crc32.getValue
  }

  def apply(shards: Seq[Shard],
    hashFunction: (Array[Byte] ⇒ Long) = defaultHashFunction): Props = {
    Props(classOf[ShardManager], shards, hashFunction)
  }
}

class ShardManager(shards: Seq[Shard], hashFunction: (Array[Byte] ⇒ Long))
    extends Actor {

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

  def lookup(key: ByteString) = {
    val hash = hashFunction(key.toArray)
    val mod = hash % pool.size
    val id = pool.keys.toIndexedSeq(mod.toInt)
    pool(id)
  }

  def create(shard: Shard) {
    val client = context.actorOf(Brando(shard.host, shard.port, shard.database, shard.auth))
    pool(shard.id) = client
  }
}

