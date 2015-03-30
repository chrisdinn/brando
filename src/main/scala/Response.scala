package brando

import akka.util.ByteString

case class PubSubMessage(channel: String, message: String)

class BrandoException(message: String) extends Exception(message) {
  override lazy val toString = "%s: %s\n".format(getClass.getName, message)
}

object Response {

  def collectItems[T](value: Any, mapper: PartialFunction[Any, T]): Option[Seq[T]] = {
    value match {
      case Some(v: List[_]) ⇒ v.foldLeft(Option(Seq[T]())) {
        case (Some(acc), e @ (Some(_: ByteString) | None)) if (mapper.isDefinedAt(e)) ⇒
          Some(acc :+ mapper(e))
        case (Some(acc), e @ (Some(_: ByteString) | None)) ⇒ Some(acc)
        case _ ⇒ None
      }
      case _ ⇒ None
    }
  }

  object AsStrings {
    def unapply(value: Any) = {
      collectItems(value, { case Some(v: ByteString) ⇒ v.utf8String })
    }
  }

  object AsStringOptions {
    def unapply(value: Any) = {
      collectItems(value, { case Some(v: ByteString) ⇒ Some(v.utf8String); case _ ⇒ None })
    }
  }

  object AsByteSeqs {
    def unapply(value: Any) = {
      collectItems(value, { case Some(v: ByteString) ⇒ v.toArray.toList })
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

