package brando

import akka.actor.{ Actor, ActorRef, Props, Status }
import akka.io.{ IO, Tcp }
import akka.util.{ ByteString, Timeout }
import akka.pattern.{ ask, pipe }
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory
import java.net.InetSocketAddress
import collection.mutable
import annotation.tailrec

import ExecutionContext.Implicits.global

case class Connect(address: InetSocketAddress)
case class CommandAck(sender: ActorRef)
object StartProcess
object Available
object UnAvailable

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

    case Tcp.CommandFailed(writeMessage: Tcp.Write) ⇒
      socket ! writeMessage //just retry immediately

    case Tcp.CommandFailed(_: Tcp.Connect) ⇒
      owner ! UnAvailable

    case x: Tcp.ConnectionClosed ⇒
      owner ! UnAvailable

    case Connect(address) ⇒
      owner = sender
      IO(Tcp)(context.system) ! Tcp.Connect(address)

    case Tcp.Connected(remoteAddress, localAddress) ⇒
      socket = sender
      socket ! Tcp.Register(self, useResumeWriting = false)
      owner ! Available

    case x ⇒ println("connection didn't expect - " + x)
  }
}

object Brando {
  def apply(
    host: String,
    port: Int,
    database: Option[Int] = None,
    auth: Option[String] = None): Props = Props(classOf[Brando], host, port, database, auth)
  def apply(): Props = Props(classOf[Brando], "localhost", 6379, None, None)
}

class Brando(
    host: String,
    port: Int,
    database: Option[Int],
    auth: Option[String]) extends Actor {

  type PendingRequest = (Request, ActorRef)

  val config = ConfigFactory.load()
  val retryDuration: Long = config.getMilliseconds("brando.connection_retry")
  val timeoutDuration: Long = config.getMilliseconds("brando.timeout")

  implicit val timeout = Timeout(timeoutDuration.milliseconds)

  val address = new InetSocketAddress(host, port)
  val connection = context.actorOf(Props[Connection])

  var connectionState: Either[mutable.Queue[PendingRequest], ActorRef] =
    Left(mutable.Queue())

  connection ! Connect(address)

  def receive = {

    case request: Request ⇒ connectionState match {
      case Right(connection)     ⇒ connection forward request
      case Left(pendingRequests) ⇒ pendingRequests.enqueue((request, sender))
    }

    case StartProcess ⇒
      connectionState match {
        case Right(_) ⇒
          connectionState = Right(connection)
        case Left(pendingRequests) ⇒
          pendingRequests foreach {
            case (request, caller) ⇒ connection.tell(request, caller)
          }
          connectionState = Right(connection)
      }

    case Available ⇒
      (for {
        auth ← if (auth.isDefined) connection ? Request(ByteString("AUTH"), ByteString(auth.get)) else Future.successful()
        database ← if (database.isDefined) connection ? Request(ByteString("SELECT"), ByteString(database.get.toString)) else Future.successful()
      } yield (StartProcess)) map {
        self ! _
      } onFailure {
        case e: Exception ⇒
          throw e
      }

    case UnAvailable ⇒
      context.system.scheduler.scheduleOnce(retryDuration.milliseconds, connection, Connect(address))
      connectionState match {
        case Right(_) ⇒
          connectionState = Left(mutable.Queue())
        case Left(_) ⇒
      }

    case x ⇒ println("Unexpected " + x + "\r\n")
  }
}
