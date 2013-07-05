package brando

import annotation.tailrec
import akka.util.ByteString
import akka.actor.Status

object IntegerReply {
  def unapply(reply: ByteString) =
    if (reply.startsWith(ByteString(":")) && reply.endsWith(ByteString("\r\n")))
      Some(reply.drop(1).dropRight(2))
    else None
}

object ErrorReply {
  def unapply(reply: ByteString) =
    if (reply.startsWith(ByteString("-")) && reply.endsWith(ByteString("\r\n")))
      Some(reply.drop(1).dropRight(2))
    else None
}

abstract class StatusReply(val status: String) {
  val bytes = ByteString(status)
}

object ValueType {
  case object String extends StatusReply("string")
  case object List extends StatusReply("list")
  case object Set extends StatusReply("set")
  case object ZSet extends StatusReply("set")
  case object Hash extends StatusReply("hash")

  private[brando] def unapply(reply: ByteString) =
    reply match {
      case String.bytes ⇒ Some(String)
      case List.bytes   ⇒ Some(List)
      case Set.bytes    ⇒ Some(Set)
      case ZSet.bytes   ⇒ Some(ZSet)
      case Hash.bytes   ⇒ Some(Hash)
      case _            ⇒ None
    }
}

case object Ok extends StatusReply("OK")
case object Pong extends StatusReply("PONG")
case object Queued extends StatusReply("QUEUED")

private[brando] object StatusReply {
  def apply(status: ByteString) = {
    status match {
      case Ok.bytes             ⇒ Some(Ok)
      case Pong.bytes           ⇒ Some(Pong)
      case Queued.bytes         ⇒ Some(Queued)
      case ValueType(valueType) ⇒ Some(valueType)
      case _                    ⇒ None
    }
  }

  def unapply(reply: ByteString) =
    if (reply.startsWith(ByteString("+")) && reply.endsWith(ByteString("\r\n")))
      apply(reply.drop(1).dropRight(2))
    else None
}

private[brando] trait ReplyParser {

  var buffer = ByteString.empty

  trait Result {
    val reply: Option[Any]
    val next: ByteString
  }
  case class Success(reply: Option[Any], next: ByteString = ByteString.empty)
    extends Result
  case class Failure(next: ByteString)
      extends Result {
    val reply = None
  }

  def readErrorReply(buffer: ByteString) = {
    val length = buffer.prefixLength(_ != '\r') + 2
    buffer.take(length) match {
      case ErrorReply(reply) ⇒
        val remainder = buffer.drop(length)
        Success(Some(Status.Failure(new BrandoException(reply.utf8String))), remainder)
      case x ⇒
        Failure(buffer)
    }
  }

  def readStatusReply(buffer: ByteString) = {
    val length = buffer.prefixLength(_ != '\r') + 2
    buffer.take(length) match {
      case StatusReply(reply) ⇒
        Success(Some(reply), buffer.drop(length))
      case x ⇒
        Failure(buffer)
    }
  }

  def readIntegerReply(buffer: ByteString) = {
    val length = buffer.prefixLength(_ != '\r') + 2
    buffer.take(length) match {
      case IntegerReply(reply) ⇒
        Success(Some(reply.utf8String.toLong), buffer.drop(length))
      case x ⇒
        Failure(buffer)
    }
  }

  def readBulkReply(buffer: ByteString) = {
    val dataLengthBytes = buffer.takeWhile(_ != '\r').drop(1)
    val headerLength = 1 + dataLengthBytes.length + 2
    val dataLength = dataLengthBytes.utf8String.toInt
    val responseLenght = headerLength + dataLength + 2

    dataLength match {
      case -1 ⇒ Success(None, buffer.drop(headerLength))
      case _ ⇒
        val data = buffer.drop(headerLength).take(dataLength)
        if ((data.length == dataLength) && (buffer.length >= responseLenght)) {
          val remainder = buffer.drop(responseLenght)
          Success(Some(data), remainder)
        } else Failure(buffer)
    }
  }

  def readMultiBulkReply(buffer: ByteString): Result = {
    val itemCountBytes = buffer.takeWhile(_ != '\r').drop(1)
    val itemCount = itemCountBytes.utf8String.toInt

    val headerLength = 1 + itemCountBytes.length + 2
    var items = buffer.drop(headerLength)

    @tailrec def readComponents(remaining: Int, result: Result): Result =
      remaining match {
        case 0 ⇒ result
        case i ⇒
          readComponent(result.next) match {
            case failure: Failure ⇒ Failure(buffer)

            case Success(newReply, next) ⇒
              var replyList =
                result.reply.map(_.asInstanceOf[List[Option[Any]]])
              var newReplyList = replyList map (_ :+ newReply)

              readComponents(i - 1, Success(newReplyList, next))
          }
      }

    readComponents(itemCount, Success(Some(List.empty[Option[Any]]), items))
  }

  def readComponent(reply: ByteString): Result =
    reply(0) match {
      case '+' ⇒ readStatusReply(reply)
      case ':' ⇒ readIntegerReply(reply)
      case '$' ⇒ readBulkReply(reply)
      case '*' ⇒ readMultiBulkReply(reply)
      case '-' ⇒ readErrorReply(reply)
    }

  def parse(reply: ByteString) =
    if (reply.isEmpty) {
      Failure(reply)
    } else {
      readComponent(reply)
    }

  @tailrec final def parseReply(bytes: ByteString)(withReply: Any ⇒ Unit) {
    parse(buffer ++ bytes) match {
      case Failure(leftoverBytes) ⇒
        buffer = leftoverBytes

      case Success(reply, leftoverBytes) ⇒
        buffer = ByteString.empty
        withReply(reply)

        if (leftoverBytes.size > 0) {
          parseReply(leftoverBytes)(withReply)
        }
    }
  }
}
