package brando

import akka.actor.{ Actor, ActorContext, ActorRef, Props, Status, Stash }
import akka.io.{ IO, Tcp }
import akka.pattern.ask
import akka.util.{ ByteString, Timeout }
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.Future

import com.typesafe.config.ConfigFactory
import java.net.InetSocketAddress

class BrandoException(message: String) extends Exception(message) {
  override lazy val toString = "%s: %s\n".format(getClass.getName, message)
}
case class PubSubMessage(channel: String, message: String)
private case class Connect(address: InetSocketAddress)
private case class CommandAck(sender: ActorRef) extends Tcp.Event

trait BrandoStateChange
case object Disconnected extends BrandoStateChange
case object Connected extends BrandoStateChange
case object AuthenticationFailed extends BrandoStateChange
case object ConnectionFailed extends BrandoStateChange

private class Connection(
    brando: ActorRef,
    address: InetSocketAddress,
    connectionRetry: Long,
    maxConnectionAttempts: Long) extends Actor with ReplyParser {
  import context.dispatcher

  var socket: ActorRef = _

  val requesterQueue = mutable.Queue.empty[ActorRef]
  var subscribers: Map[ByteString, Seq[ActorRef]] = Map.empty

  var connectionAttempts = 0

  self ! Connect(address)

  def getSubscribers(channel: ByteString): Seq[ActorRef] =
    subscribers.get(channel).getOrElse(Seq.empty[ActorRef])

  def receive = {
    case subRequest: Request if (subRequest.command.utf8String.toLowerCase == "subscribe") ⇒

      subRequest.params map { x ⇒
        subscribers = subscribers + ((x, getSubscribers(x).+:(sender)))
      }
      socket ! Tcp.Write(subRequest.toByteString, CommandAck(sender))

    case request: Request ⇒
      socket ! Tcp.Write(request.toByteString, CommandAck(sender))

    case CommandAck(sender) ⇒ requesterQueue.enqueue(sender)

    case Tcp.Received(data) ⇒
      parseReply(data) { reply ⇒
        reply match {
          case Some(List(Some(x: ByteString), Some(channel: ByteString), Some(message: ByteString))) if (x.utf8String == "message") ⇒

            val pubSubMessage = PubSubMessage(channel.utf8String, message.utf8String)
            getSubscribers(channel).map { x ⇒
              x ! pubSubMessage
            }

          case _ ⇒
            requesterQueue.dequeue ! (reply match {
              case Some(failure) if failure.isInstanceOf[Status.Failure] ⇒ failure
              case success ⇒ success
            })
        }
      }

    case Tcp.CommandFailed(writeMessage: Tcp.Write) ⇒
      socket ! writeMessage //just retry immediately

    case Tcp.CommandFailed(_: Tcp.Connect) ⇒
      if (connectionAttempts >= maxConnectionAttempts) {
        brando ! ConnectionFailed
      } else {
        connectionAttempts += 1
        context.system.scheduler.scheduleOnce(connectionRetry.milliseconds, self, Connect(address))
      }

    case x: Tcp.ConnectionClosed ⇒
      requesterQueue.clear
      brando ! x
      context.system.scheduler.scheduleOnce(connectionRetry.milliseconds, self, Connect(address))

    case Connect(address) ⇒
      IO(Tcp)(context.system) ! Tcp.Connect(address)

    case x: Tcp.Connected ⇒
      socket = sender
      connectionAttempts = 0
      socket ! Tcp.Register(self, useResumeWriting = false)
      brando ! x

    case x ⇒ println("connection didn't expect - " + x)
  }
}

object Brando {
  def apply(
    host: String,
    port: Int,
    database: Option[Int] = None,
    auth: Option[String] = None,
    listeners: Set[ActorRef] = Set()): Props = Props(classOf[Brando], host, port, database, auth, listeners)
  def apply(): Props = apply("localhost", 6379, None, None, Set())
}

class Brando(
    host: String,
    port: Int,
    database: Option[Int],
    auth: Option[String],
    listeners: Set[ActorRef]) extends Actor with Stash {
  import context.dispatcher

  val config = context.system.settings.config
  val timeoutDuration: Long = config.getMilliseconds("brando.timeout")
  val connectionRetry: Long = config.getMilliseconds("brando.connection_retry")
  val maxConnectionAttempts: Long = config.getMilliseconds("brando.connection_attempts")

  implicit val timeout = Timeout(timeoutDuration)

  case object Authenticating
  case object Authenticated

  val address = new InetSocketAddress(host, port)
  val connection = context.actorOf(Props(classOf[Connection],
    self, address, connectionRetry, maxConnectionAttempts))

  def receive = disconnected

  def authenticated: Receive = {
    case request: Request ⇒ connection forward request
    case x: Tcp.ConnectionClosed ⇒
      notifyStateChange(Disconnected)
      context.become(disconnected)
  }

  def disconnected: Receive = {
    case request: Request ⇒ stash()

    case x: Tcp.Connected ⇒

      context.become(authenticating)

      (for {
        auth ← if (auth.isDefined) connection ? Request(ByteString("AUTH"), ByteString(auth.get)) else Future.successful()
        database ← if (database.isDefined) connection ? Request(ByteString("SELECT"), ByteString(database.get.toString)) else Future.successful()
      } yield (Connected)) map {
        self ! _
      } onFailure {
        case e: Exception ⇒
          self ! AuthenticationFailed
      }

    case ConnectionFailed ⇒
      notifyStateChange(ConnectionFailed)
  }

  def authenticating: Receive = {
    case request: Request ⇒ stash()

    case x: Tcp.ConnectionClosed ⇒
      notifyStateChange(Disconnected)
      context.become(disconnected)

    case Connected ⇒
      unstashAll()
      notifyStateChange(Connected)
      context.become(authenticated)

    case AuthenticationFailed ⇒
      notifyStateChange(AuthenticationFailed)

  }

  private def notifyStateChange(newState: BrandoStateChange) {
    listeners foreach { _ ! newState }
  }

}
