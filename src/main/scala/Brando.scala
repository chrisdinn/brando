package brando

import akka.actor.{ Actor, ActorRef, Props, Status }
import akka.io.{ IO, Tcp }
import java.net.InetSocketAddress
import akka.util.ByteString

import collection.mutable
import annotation.tailrec

case class Connect(address: InetSocketAddress)
case class Available(connection: ActorRef)

case class CommandAck(sender: ActorRef)

class Connection extends Actor with ReplyParser {

  var socket: ActorRef = _

  val requesterQueue = mutable.Queue[ActorRef]()
  var owner: ActorRef = _

  def receive = {
    case request: Request ⇒
      socket ! Tcp.Write(request.toByteString, CommandAck(sender))

    case CommandAck(sender) ⇒ requesterQueue.enqueue(sender)

    case Tcp.Received(data) ⇒
      parseReply(data) { reply ⇒
        requesterQueue.dequeue ! (reply match {
          case Some(failure) if failure.isInstanceOf[Status.Failure] ⇒ failure
          case success ⇒ success
        })
      }
    case Tcp.CommandFailed(writeMessage) ⇒
      socket ! writeMessage //just retry immediately

    case Connect(address) ⇒
      owner = sender
      IO(Tcp)(context.system) ! Tcp.Connect(address)

    case Tcp.Connected(remoteAddress, localAddress) ⇒
      socket = sender
      socket ! Tcp.Register(self, useResumeWriting = false)
      owner ! Available(self)

    case x ⇒ println("connection didn't expect - " + x)
  }
}

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
case object Ok extends StatusReply("OK")
case object Pong extends StatusReply("PONG")

object StatusReply {
  def apply(status: ByteString) = {
    status match {
      case Ok.bytes   ⇒ Some(Ok)
      case Pong.bytes ⇒ Some(Pong)
      case _          ⇒ None
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

  type PendingRequest = (Request, ActorRef)

  var connectionState: Either[mutable.Queue[PendingRequest], ActorRef] =
    Left(mutable.Queue())

  connectionActor ! Connect(address)

  def receive = {

    case request: Request ⇒ connectionState match {
      case Right(connection)     ⇒ connection forward request
      case Left(pendingRequests) ⇒ pendingRequests.enqueue((request, sender))
    }

    case Available(connection) ⇒ connectionState match {
      case Right(_) ⇒ connectionState = Right(connection)
      case Left(pendingRequests) ⇒
        pendingRequests foreach {
          case (request, caller) ⇒ connection.tell(request, caller)
        }
        connectionState = Right(connection)
    }

    case x ⇒ println("Unexpected " + x + "\r\n")

  }

}
