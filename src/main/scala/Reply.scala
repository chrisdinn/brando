package brando

import scala.language.higherKinds
import annotation.tailrec

import akka.util.ByteString

trait Response[+A] {
  val value: A
  val buffer: ByteString
}

case class RawReply(buffer: ByteString)
    extends Response[Option[ByteString]] {
  val value = None
}

case class Reply[+A](value: A, buffer: ByteString)
  extends Response[A]

case class ReplySet[A](value: A, buffer: ByteString)
  extends Response[A]

object Reply {

  abstract class Status(val status: String) {
    def unapply(s: ByteString) =
      if (s.equals(ByteString("+" + status + "\r\n"))) Some(this) else None
  }
  case object Ok extends Status("OK")
  case object Pong extends Status("PONG")

  def readIntegerReply(buffer: ByteString) = {
    val intBytes = buffer.takeWhile(_ != '\r').drop(1)
    val remainder = buffer.drop(1 + intBytes.length + 2)
    Reply(Some(intBytes.utf8String.toInt), remainder)
  }

  def readBulkReply(buffer: ByteString) = {
    val dataLengthBytes = buffer.takeWhile(_ != '\r').drop(1)

    val headerLength = 1 + dataLengthBytes.length + 2
    val dataLength = dataLengthBytes.utf8String.toInt

    if (dataLength != -1) {
      val data = buffer.drop(headerLength).take(dataLength)
      val remainder = buffer.drop(headerLength + dataLength + 2)
      Reply(Some(data), remainder)
    } else Reply(None, buffer.drop(headerLength))
  }

  def readMultiBulkReply(buffer: ByteString): Reply[List[Any]] = {
    val itemCountBytes = buffer.takeWhile(_ != '\r').drop(1)
    val itemCount = itemCountBytes.utf8String.toInt

    val headerLength = 1 + itemCountBytes.length + 2
    var itemsBuffer = buffer.drop(headerLength)

    @tailrec def readComponents(remaining: Int, response: Reply[List[Any]]): Reply[List[Any]] =
      remaining match {
        case 0 ⇒ response
        case i ⇒
          val nextResponse = readComponent(response)
          val newValue = response.value :+ nextResponse.value
          readComponents(i - 1, Reply(newValue, nextResponse.buffer))
      }

    readComponents(itemCount, Reply(Nil, itemsBuffer))
  }

  def readComponent(response: Response[Any]) =
    response.buffer(0) match {
      case '+' ⇒
        val length = response.buffer.prefixLength(_ != '\r') + 2
        val status = response.buffer.take(length) match {
          case Ok(data)   ⇒ Ok
          case Pong(data) ⇒ Pong
          case x ⇒
            println("+unknown status" + x.utf8String)
            Reply(None, ByteString.empty)
        }
        Reply(status, response.buffer.drop(length))

      case ':' ⇒ readIntegerReply(response.buffer)
      case '$' ⇒ readBulkReply(response.buffer)
      case '*' ⇒ readMultiBulkReply(response.buffer)
      case x   ⇒ println("hey " + x); Reply(None, ByteString.empty)
    }

  def readReply(response: Response[Any]) = {
    val next = readComponent(response)

    response match {
      case e: RawReply ⇒
        next

      case s: Reply[_] ⇒
        val mergedValue = List(s.value, next.value)
        ReplySet(mergedValue, next.buffer)

      case m: ReplySet[_] ⇒
        ReplySet(m.value.asInstanceOf[List[Any]] :+ next.value, next.buffer)
    }
  }

  @tailrec def parse(response: Response[Any]): Response[Any] = {
    response match {
      case Reply(_, ByteString.empty)    ⇒ response

      case ReplySet(_, ByteString.empty) ⇒ response

      case reply                         ⇒ parse(readReply(reply))
    }
  }

  def apply(data: ByteString) = parse(RawReply(data)).value
}