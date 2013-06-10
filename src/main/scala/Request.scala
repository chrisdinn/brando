package brando

import akka.util.ByteString

object Request {
  def apply(command: String, params: String*) =
    new Request(ByteString(command), params map (ByteString(_)): _*)
}

//Helps creating a request like HMSET key k1 v1 k2 v2... 
object HashRequest {
  def apply(cmd: String, key: String, map: Map[String, String]) = {
    val args = Seq(key) ++ map.map(e ⇒ Seq(e._1, e._2)).flatten
    Request(cmd, args: _*)
  }
}

case class Request(command: ByteString, params: ByteString*) {
  val CRLF = ByteString("\r\n")

  def args = command :: params.toList

  def toByteString = args.map(argLine(_)).foldLeft(header)(_ ++ _)

  private def header = ByteString("*" + args.length) ++ CRLF

  private def argLine(bytes: ByteString) =
    ByteString("$" + bytes.length) ++ CRLF ++ bytes ++ CRLF
}

object ShardRequest {
  def apply(command: String, key: String, params: String*) = {
    new ShardRequest(ByteString(command), ByteString(key), params map (ByteString(_)): _*)
  }
}

case class ShardRequest(command: ByteString, key: ByteString, params: ByteString*)
