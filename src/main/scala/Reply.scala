package brando

import akka.util.ByteString

object Reply {
  abstract class Status(val status: String)

  case object Ok extends Status("OK") {
    def unapply(s: ByteString) =
      if (s.equals(ByteString("+OK\r\n"))) Some(Ok) else None
  }

  def parseIntegerReply(data: ByteString) =
    Some(data.takeWhile(_ != '\r').drop(1).utf8String.toInt)

  def parseBulkReply(data: ByteString) = {
    val lengthBytes = data.takeWhile(_ != '\r').drop(1)
    val length = lengthBytes.utf8String.toInt
    if (length == -1)
      None
    else
      Some(data.drop(lengthBytes.length + 3).take(length))
  }

  def parseMultiBulkReply(data: ByteString) = {
    val numItems = data.takeWhile(_ != '\r').drop(1)
    var itemsBuffer = data.drop(numItems.length + 3)

    parseItems(numItems.utf8String.toInt, itemsBuffer, List())
  }

  def parseItems(remaining: Int, buffer: ByteString,
    items: List[Option[Any]]): List[Option[Any]] = {
    remaining match {
      case 0 ⇒ items
      case x ⇒
        buffer(0) match {
          case '$' ⇒
            val item = parseBulkReply(buffer)
            val remainingBuffer = item match {
              case Some(i) ⇒
                buffer.dropWhile(_ != '\r').drop(2).drop(i.length).drop(2)
              case None ⇒ buffer.dropWhile(_ != '\r').drop(2)
            }
            parseItems(x - 1, remainingBuffer, items :+ item)

          case ':' ⇒
            val item = parseIntegerReply(buffer)
            parseItems(x - 1, buffer.dropWhile(_ != '\r').drop(2), items :+ item)

        }
    }
  }

  def apply(data: ByteString) = {
    data(0) match {
      case '+' ⇒ // Status reply
        data match {
          case Ok(data) ⇒ Ok
          case x        ⇒ println("+unknown " + x.utf8String)
        }

      case ':' ⇒ parseIntegerReply(data)

      case '$' ⇒ parseBulkReply(data)

      case '*' ⇒ parseMultiBulkReply(data)

      case x   ⇒ println("*unknown " + data.utf8String)
    }
  }
}
