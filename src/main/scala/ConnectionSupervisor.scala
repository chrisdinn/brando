package brando

import akka.actor._
import akka.pattern._
import akka.util._

import scala.concurrent.duration._
import scala.concurrent.Future

object ConnectionSupervisor {
  private[brando] case class Connect(host: String, port: Int)
  private[brando] case object Reconnect
}

private[brando] abstract class ConnectionSupervisor(
    database: Int,
    auth: Option[String],
    var listeners: Set[ActorRef],
    connectionTimeout: FiniteDuration,
    connectionHeartbeatDelay: Option[FiniteDuration]) extends Actor with Stash {

  import ConnectionSupervisor.{ Connect, Reconnect }
  import context.dispatcher

  implicit val timeout = Timeout(connectionTimeout)

  var connection = context.system.deadLetters

  def receive = disconnected

  def connected: Receive = handleListeners orElse {
    case request: Request ⇒
      connection forward request

    case batch: Batch ⇒
      connection forward batch

    case x: Connection.Disconnected ⇒
      notifyStateChange(x)
      context.become(disconnected)
      self ! Reconnect
  }

  def disconnected: Receive = handleListeners orElse {
    case request: Request ⇒
      stash()

    case batch: Batch ⇒
      stash()

    case Connect(host, port) ⇒
      connection ! PoisonPill
      connection = context.actorOf(Props(classOf[Connection],
        self, host, port, connectionTimeout, connectionHeartbeatDelay))

    case x: Connection.Connecting ⇒
      notifyStateChange(x)

    case x: Connection.Connected ⇒
      authenticate(x)

    case ("auth_ok", x: Connection.Connected) ⇒
      notifyStateChange(x)
      context.become(connected)
      unstashAll()

    case x: Connection.ConnectionFailed ⇒
      notifyStateChange(x)
      self ! Reconnect
  }

  def handleListeners: Receive = {
    case s: ActorRef ⇒
      listeners = listeners + s

    case Terminated(l) ⇒
      listeners = listeners - l
  }

  def notifyStateChange(newState: Connection.StateChange) {
    listeners foreach { _ ! newState }
  }

  def authenticate(x: Connection.Connected) {
    (for {
      auth ← if (auth.isDefined)
        connection ? Request(ByteString("AUTH"), ByteString(auth.get)) else Future.successful(Unit)
      database ← if (database != 0)
        connection ? Request(ByteString("SELECT"), ByteString(database.toString)) else Future.successful(Unit)
    } yield ("auth_ok", x)) map {
      self ! _
    } onFailure {
      case e: Exception ⇒
        notifyStateChange(Brando.AuthenticationFailed(x.host, x.port))
    }
  }
}
