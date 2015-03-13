package brando

import akka.actor.{ Actor, ActorContext, ActorRef, Props, Status, Stash, Terminated }
import akka.io.{ IO, Tcp }
import akka.pattern._
import akka.util.{ ByteString, Timeout }
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.Try

import com.typesafe.config.ConfigFactory
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

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
    maxConnectionAttempts: Option[Int],
    connectionTimeout: FiniteDuration) extends Actor with ReplyParser {
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
              case Some(failure: Status.Failure) ⇒
                failure
              case success ⇒
                success
            })
        }
      }

    case Tcp.CommandFailed(writeMessage: Tcp.Write) ⇒
      socket ! writeMessage //just retry immediately

    case Tcp.CommandFailed(_: Tcp.Connect) ⇒
      if (maxConnectionAttempts.isDefined && connectionAttempts >= maxConnectionAttempts.get) {
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
      IO(Tcp)(context.system) ! Tcp.Connect(address, timeout = Some(connectionTimeout))

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
    private[brando] var listeners: Set[ActorRef]) extends Actor with Stash {
  import context.dispatcher

  val config = context.system.settings.config
  val connectionTimeout: Long = config.getDuration("redis.timeout", TimeUnit.MILLISECONDS)
  val connectionRetry: Long = config.getDuration("brando.connection_retry", TimeUnit.MILLISECONDS)
  val maxConnectionAttempts: Option[Int] = Try(config.getInt("brando.connection_attempts")).toOption
  implicit val timeout = Timeout(connectionTimeout, TimeUnit.MILLISECONDS)

  case object Authenticating
  case object Authenticated

  val address = new InetSocketAddress(host, port)
  val connection = context.actorOf(Props(classOf[Connection],
    self, address, connectionRetry, maxConnectionAttempts, connectionTimeout.millis))

  listeners.map(context.watch(_))

  def receive = disconnected orElse cleanListeners

  def authenticated: Receive = {
    case request: Request ⇒
      connection forward request

    case requests: Requests ⇒
      stash()
      requests.list.foreach(connection forward _)

    case x: Tcp.ConnectionClosed ⇒
      notifyStateChange(Disconnected)
      context.become(disconnected orElse cleanListeners)
    case s: ActorRef ⇒
      listeners = listeners + s
  }

  def disconnected: Receive = {
    case requests: Requests ⇒ stash()

    case request: Request   ⇒ stash()

    case x: Tcp.Connected ⇒

      context.become(authenticating orElse cleanListeners)

      (for {
        auth ← if (auth.isDefined) connection ? Request(ByteString("AUTH"), ByteString(auth.get)) else Future.successful(Unit)
        database ← if (database.isDefined) connection ? Request(ByteString("SELECT"), ByteString(database.get.toString)) else Future.successful(Unit)
      } yield (Connected)) map {
        self ! _
      } onFailure {
        case e: Exception ⇒
          self ! AuthenticationFailed
      }

    case ConnectionFailed ⇒
      notifyStateChange(ConnectionFailed)

    case s: ActorRef ⇒
      listeners = listeners + s
  }

  def authenticating: Receive = {
    case requests: Requests ⇒ stash()

    case request: Request   ⇒ stash()

    case x: Tcp.ConnectionClosed ⇒
      notifyStateChange(Disconnected)
      context.become(disconnected orElse cleanListeners)

    case Connected ⇒
      unstashAll()
      notifyStateChange(Connected)
      context.become(authenticated orElse cleanListeners)

    case AuthenticationFailed ⇒
      notifyStateChange(AuthenticationFailed)

    case s: ActorRef ⇒
      listeners = listeners + s
  }

  def cleanListeners: Receive = {
    case Terminated(l) ⇒
      listeners = listeners - l
  }

  private def notifyStateChange(newState: BrandoStateChange) {
    listeners foreach { _ ! newState }
  }

}
