package brando

import annotation.tailrec
import akka.util.ByteString

object ReplyParser {

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
    val intBytes = buffer.takeWhile(_ != '\r').drop(1)
    val remainder = buffer.drop(1 + intBytes.length + 2)

    Success(Some(intBytes.utf8String.toInt), remainder)
  }

  def readBulkReply(buffer: ByteString) = {
    val dataLengthBytes = buffer.takeWhile(_ != '\r').drop(1)

    val headerLength = 1 + dataLengthBytes.length + 2
    val dataLength = dataLengthBytes.utf8String.toInt

    dataLength match {
      case -1 ⇒ Success(None, buffer.drop(headerLength))

      case _ ⇒
        val data = buffer.drop(headerLength).take(dataLength)
        if (data.length == dataLength) {
          val remainder = buffer.drop(headerLength + dataLength + 2)
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
    }

  def parse(reply: ByteString) =
    if (reply.isEmpty) {
      Failure(reply)
    } else {
      readComponent(reply)
    }
}
