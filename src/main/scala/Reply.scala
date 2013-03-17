package brando

import akka.util.ByteString

case class Response[+A](value: A, buffer: ByteString)

object Reply {

  abstract class Status(val status: String) {
    def unapply(s: ByteString) =
      if (s.equals(ByteString("+" + status + "\r\n"))) Some(this) else None
  }
  case object Ok extends Status("OK")
  case object Pong extends Status("PONG")

  def readIntegerReply(reply: Response[Any]) = {
    val intBytes = reply.buffer.takeWhile(_ != '\r').drop(1)
    val remainder = reply.buffer.drop(1 + intBytes.length + 2)
    Response(Some(intBytes.utf8String.toInt), remainder)
  }

  def readBulkReply(reply: Response[Any]) = {
    val dataLengthBytes = reply.buffer.takeWhile(_ != '\r').drop(1)

    val headerLength = 1 + dataLengthBytes.length + 2
    val dataLength = dataLengthBytes.utf8String.toInt

    if (dataLength != -1) {
      val data = reply.buffer.drop(headerLength).take(dataLength)
      val remainder = reply.buffer.drop(headerLength + dataLength + 2)
      Response(Some(data), remainder)
    } else Response(None, reply.buffer.drop(headerLength))
  }

  def readMultiBulkReply(reply: Response[Any]): Response[List[Any]] = {
    val itemCountBytes = reply.buffer.takeWhile(_ != '\r').drop(1)
    val itemCount = itemCountBytes.utf8String.toInt

    val headerLength = 1 + itemCountBytes.length + 2
    var itemsBuffer = reply.buffer.drop(headerLength)

    def readComponents(remaining: Int, response: Response[List[Any]]): Response[List[Any]] =
      remaining match {
        case 0 ⇒ response
        case i ⇒
          val nextResponse = readComponent(response)
          val newValue = response.value :+ nextResponse.value
          readComponents(i - 1, Response(newValue, nextResponse.buffer))
      }

    readComponents(itemCount, Response(Nil, itemsBuffer))
  }

  def readComponent(response: Response[Any]) = {
    response.buffer(0) match {
      case '+' ⇒
        val length = response.buffer.prefixLength(_ != '\r') + 2
        val status = response.buffer.take(length) match {
          case Ok(data)   ⇒ Ok
          case Pong(data) ⇒ Pong
          case x ⇒
            println("+unknown status" + x.utf8String)
            Response(None, ByteString.empty)
        }
        Response(status, response.buffer.drop(length))

      case ':' ⇒ readIntegerReply(response)
      case '$' ⇒ readBulkReply(response)
      case '*' ⇒ readMultiBulkReply(response)
      case x   ⇒ println("hey " + x); Response(None, ByteString.empty)
    }
  }

  def parse(response: Response[Any]): Response[Any] = {
    response match {
      case Response(_, ByteString.empty) ⇒ response

      case reply                         ⇒ parse(readComponent(reply))
    }
  }

  def apply(data: ByteString) = parse(Response(None, data)).value
}