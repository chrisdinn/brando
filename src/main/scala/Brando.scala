package brando

import akka.actor.{ Actor, ActorRef, Props }
import akka.io.{ IO, Tcp }
import java.net.InetSocketAddress
import akka.util.ByteString

import collection.immutable.Queue
import annotation.tailrec

case class Connect(address: InetSocketAddress)
case class Available(connection: ActorRef)

class Connection extends Actor {
  import ReplyParser._

  var socket: ActorRef = _

  var owner: ActorRef = _
  var caller: ActorRef = _

  var expectedReplyCount = 0
  var replies = List.empty[Option[Any]]
  var buffer = ByteString.empty

  @tailrec private def parseReply(bytes: ByteString) {
    parse(buffer ++ bytes) match {
      case Failure(leftoverBytes) ⇒
        buffer = leftoverBytes

      case Success(reply, leftoverBytes) ⇒
        replies = replies :+ reply

        replies.length match {
          case x if x == expectedReplyCount ⇒
            if (replies.length > 1)
              caller ! replies
            else
              caller ! replies.head

            replies = List.empty
            buffer = ByteString.empty
            owner ! Available(self)

          case _ ⇒ parseReply(leftoverBytes)
        }
    }
  }

  def receive = {

    case Connect(address) ⇒
      owner = sender
      IO(Tcp)(context.system) ! Tcp.Connect(address)

    case Tcp.Connected(remoteAddress, localAddress) ⇒
      socket = sender
      socket ! Tcp.Register(self)
      owner ! Available(self)

    case Tcp.Received(data) ⇒ parseReply(data)

    case request: Request ⇒
      caller = sender
      expectedReplyCount = 1

      socket ! Tcp.Write(request.toByteString, Tcp.NoAck)

    case requests: List[_] ⇒
      caller = sender
      expectedReplyCount = requests.length
      val requestBytes = requests.map(_.asInstanceOf[Request].toByteString)
        .foldLeft(ByteString())(_ ++ _)

      socket ! Tcp.Write(requestBytes, Tcp.NoAck)

    case x ⇒ println("connection didn't expect - " + x)
  }

}

abstract class StatusReply(val status: String)
case object Ok extends StatusReply("OK")
case object Pong extends StatusReply("PONG")

object StatusReply {
  def apply(status: ByteString) = {
    status.utf8String match {
      case Ok.status   ⇒ Some(Ok)
      case Pong.status ⇒ Some(Pong)
      case _           ⇒ None
    }
  }

  def unapply(reply: ByteString) =
    if (reply.startsWith(ByteString("+")) && reply.endsWith(ByteString("\r\n")))
      apply(reply.drop(1).dropRight(2))
    else None
}

object Brando {
  def apply(host: String, port: Int): Props = Props(classOf[Brando], host, port)
  def apply(): Props = Props(classOf[Brando], "localhost", 6379)
}

class Brando(host: String, port: Int) extends Actor {

  val address = new InetSocketAddress(host, port)
  val connectionActor = context.actorOf(Props[Connection])

  var readyConnection: Option[ActorRef] = None
  var pendingRequests = Queue[Pair[Any, ActorRef]]()

  connectionActor ! Connect(address)

  def trySend(request: Any) =
    readyConnection match {
      case Some(connection) ⇒
        readyConnection = None
        connection forward request
      case None ⇒
        pendingRequests = pendingRequests.enqueue(Pair(request, sender))
    }

  def receive = {

    case Available(connection) ⇒
      if (pendingRequests.isEmpty) {
        readyConnection = Some(connection)
      } else {
        val ((request, caller), queue) = pendingRequests.dequeue
        pendingRequests = queue
        connection.tell(request, caller)
      }

    case request: Request  ⇒ trySend(request)

    case requests: List[_] ⇒ trySend(requests)

    case x                 ⇒ println("Unexpected " + x + "\r\n")

  }

}