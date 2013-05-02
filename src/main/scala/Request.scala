package brando

import akka.util.ByteString

object Request {
  def apply(command: String, params: String*) =
    new Request(ByteString(command), params map (ByteString(_)): _*)
}

case class Request(command: ByteString, params: ByteString*) {
  val CRLF = ByteString("\r\n")

  def args = command :: params.toList

  def toByteString = args.map(argLine(_)).foldLeft(header)(_ ++ _)

  private def header = ByteString("*" + args.length) ++ CRLF

  private def argLine(bytes: ByteString) =
    ByteString("$" + bytes.length) ++ CRLF ++ bytes ++ CRLF
}