package brando

import akka.actor._
import akka.pattern._
import akka.util._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure

object ConnectionSupervisor {
  private[brando] case class Connect(host: String, port: Int)
  private[brando] case object Reconnect
}

private[brando] abstract class RedisConnectionSupervisor(
    database: Int,
    auth: Option[String],
    var listeners: Set[ActorRef],
    connectionTimeout: FiniteDuration,
    connectionHeartbeatDelay: Option[FiniteDuration]
) extends Actor {

  import ConnectionSupervisor.{Connect, Reconnect}
  import context.dispatcher

  implicit val timeout = Timeout(connectionTimeout)

  var connection = context.system.deadLetters

  protected var status: Connection.StateChange = Connection.Disconnected("unknown", 0)

  def receive = disconnected

  def connected: Receive = handleListeners orElse {
    case m @ (_: Request | _: Batch) ⇒
      connection forward m

    case x: Connection.Disconnected ⇒
      notifyStateChange(x)
      context.become(disconnected)
      self ! Reconnect
  }

  def disconnected: Receive = handleListeners orElse {
    case Connect(host, port) ⇒
      connection ! PoisonPill
      connection =
        context.actorOf(Props(classOf[Connection], self, host, port, connectionTimeout, connectionHeartbeatDelay))

    case x: Connection.Connecting ⇒
      notifyStateChange(x)

    case x: Connection.Connected ⇒
      authenticate(x)

    case ("auth_ok", x: Connection.Connected) ⇒
      notifyStateChange(x)
      context.become(connected)

    case x: Connection.ConnectionFailed ⇒
      notifyStateChange(x)
      self ! Reconnect
  }

  def handleListeners: Receive = {
    case s: ActorRef ⇒
      listeners = listeners + s
      s ! status // notify the new listener about current status

    case Terminated(l) ⇒
      listeners = listeners - l
  }

  def notifyStateChange(newState: Connection.StateChange) {
    status = newState
    listeners foreach { _ ! newState }
  }

  def authenticate(x: Connection.Connected) {
    (for {
      auth ←
        if (auth.isDefined)
          connection ? Request(ByteString("AUTH"), ByteString(auth.get))
        else Future.successful(())
      database ←
        if (database != 0)
          connection ? Request(ByteString("SELECT"), ByteString(database.toString))
        else Future.successful(())
    } yield ("auth_ok", x)) map {
      self ! _
    } onComplete {
      case Failure(e: Exception) ⇒
        notifyStateChange(Redis.AuthenticationFailed(x.host, x.port))
      case _ => // everything is fine.
    }
  }
}
