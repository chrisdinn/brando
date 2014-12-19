package brando

import akka.util.ByteString

object Response {
  private def recUnapply[T](list: List[Any], mapper: ByteString ⇒ T, result: Seq[T] = Seq.empty): Option[Seq[T]] = {
    if (list.isEmpty) Some(result)
    else list match {
      case Some(s: ByteString) :: tail ⇒ recUnapply(list.tail, mapper, result :+ mapper(s))
      case _                           ⇒ None
    }
  }

  object AsStrings {
    def unapply(value: Any) = {
      value match {
        case Some(list: List[Any]) ⇒ recUnapply[String](list, { _.utf8String })
        case _                     ⇒ None
      }
    }
  }

  object AsByteSeqs {
    def unapply(value: Any) = {
      value match {
        case Some(list: List[Any]) ⇒ recUnapply[Seq[Byte]](list, { t ⇒ t.toArray })
        case _                     ⇒ None
      }
    }
  }

  object AsStringsHash {
    def unapply(value: Any) = {
      value match {
        case AsStrings(result) if (result.size % 2 == 0) ⇒
          val map = result.grouped(2).map {
            subseq ⇒ subseq(0) -> subseq(1)
          }.toMap
          Some(map)
        case _ ⇒ None
      }
    }
  }

  object AsString {
    def unapply(value: Any) = {
      value match {
        case Some(str: ByteString) ⇒ Some(str.utf8String)
        case _                     ⇒ None
      }
    }
  }

  object AsStringOption {
    def unapply(value: Any) = {
      value match {
        case Some(str: ByteString) ⇒ Some(Some(str.utf8String))
        case _                     ⇒ Some(None)
      }
    }
  }
}

