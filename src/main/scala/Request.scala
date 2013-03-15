package brando

import akka.util.ByteString

case class Request(command: String, params: String*) {
  val CRLF = ByteString("\r\n")

  def args = command :: params.toList

  def toByteString = args.map(argLine(_)).foldLeft(header)(_ ++ _)

  private def header = ByteString("*" + args.length) ++ CRLF

  private def argLine(arg: String) = {
    val bytes = ByteString(arg)
    ByteString("$" + bytes.length) ++ CRLF ++ bytes ++ CRLF
  }
}