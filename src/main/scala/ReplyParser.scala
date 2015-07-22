package brando

import annotation.tailrec
import akka.actor.Status
import akka.util.ByteString

sealed abstract class StatusReply(val status: String) {
  val bytes = ByteString(status)
}

object ValueType {
  case object String extends StatusReply("string")
  case object List extends StatusReply("list")
  case object Set extends StatusReply("set")
  case object ZSet extends StatusReply("set")
  case object Hash extends StatusReply("hash")
}

case object Ok extends StatusReply("OK")
case object Pong extends StatusReply("PONG")
case object Queued extends StatusReply("QUEUED")

private[brando] object StatusReply {
  import ValueType._

  def fromString(status: String) = {
    status match {
      case Ok.status     ⇒ Some(Ok)
      case Pong.status   ⇒ Some(Pong)
      case Queued.status ⇒ Some(Queued)

      case String.status ⇒ Some(String)
      case List.status   ⇒ Some(List)
      case Set.status    ⇒ Some(Set)
      case ZSet.status   ⇒ Some(ZSet)
      case Hash.status   ⇒ Some(Hash)
    }
  }
}

private[brando] trait ReplyParser {

  var remainingBuffer = ByteString.empty

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

  def splitLine(buffer: ByteString): Option[(String, ByteString)] = {
    val start = buffer.takeWhile(_ != '\r')
    val rest = buffer.drop(start.size)
    if (rest.take(2) == ByteString("\r\n")) {
      Some((start.drop(1).utf8String, rest.drop(2)))
    } else {
      None
    }
  }

  def readErrorReply(buffer: ByteString) = splitLine(buffer) match {
    case Some((error, rest)) ⇒
      Success(Some(Status.Failure(new RedisException(error))), rest)
    case _ ⇒ Failure(buffer)
  }

  def readSimpleStringReply(buffer: ByteString) = splitLine(buffer) match {
    case Some((status, rest)) ⇒
      Success(StatusReply.fromString(status), rest)
    case _ ⇒ Failure(buffer)
  }

  def readIntegerReply(buffer: ByteString) = splitLine(buffer) match {
    case Some((int, rest)) ⇒ Success(Some(int.toLong), rest)
    case x                 ⇒ Failure(buffer)
  }

  def readBulkStringReply(buffer: ByteString): Result = splitLine(buffer) match {
    case Some((length, rest)) ⇒
      val dataLength = length.toInt

      if (dataLength == -1) Success(None, rest) //null response
      else if (rest.length >= dataLength + 2) { //rest = data + "\r\n"
        val data = rest.take(dataLength)
        val remainder = rest.drop(dataLength + 2)
        Success(Some(data), remainder)
      } else Failure(buffer)

    case _ ⇒ Failure(buffer)
  }

  def readArrayReply(buffer: ByteString): Result = splitLine(buffer) match {
    case Some(("-1", rest)) ⇒
      Success(None, rest)

    case Some((count, rest)) ⇒
      val itemCount = count.toInt

      @tailrec def readElements(remaining: Int, result: Result): Result = remaining match {
        case 0                        ⇒ result
        case _ if result.next.isEmpty ⇒ Failure(buffer)
        case _ ⇒
          (parse(result.next), result.reply) match {
            case (failure: Failure, _) ⇒
              Failure(buffer)
            case (Success(element, next), Some(elements: List[_])) ⇒
              if (remaining == 1) //Add last element to the array reply and reorder
                readElements(0, Success(Some((element +: elements).reverse), next))
              else
                readElements(remaining - 1, Success(Some(element +: elements), next))
          }
      }

      readElements(itemCount, Success(Some(List.empty[Option[Any]]), rest))

    case _ ⇒ Failure(buffer)
  }

  def readPubSubMessage(buffer: ByteString) = splitLine(buffer) match {
    case Some((int, rest)) ⇒ Success(Some(int.toLong), rest)
    case x                 ⇒ Failure(buffer)
  }

  def parse(reply: ByteString) = reply(0) match {
    case '+' ⇒ readSimpleStringReply(reply)
    case '-' ⇒ readErrorReply(reply)
    case ':' ⇒ readIntegerReply(reply)
    case '$' ⇒ readBulkStringReply(reply)
    case '*' ⇒ readArrayReply(reply)
  }

  @tailrec final def parseReply(bytes: ByteString)(withReply: Any ⇒ Unit) {
    if (bytes.size > 0) {
      parse(remainingBuffer ++ bytes) match {
        case Failure(leftoverBytes) ⇒
          remainingBuffer = leftoverBytes

        case Success(reply, leftoverBytes) ⇒
          remainingBuffer = ByteString.empty
          withReply(reply)

          if (leftoverBytes.size > 0) {
            parseReply(leftoverBytes)(withReply)
          }
      }
    } else Failure(bytes)
  }
}
