package brando

import java.util.concurrent.TimeUnit

import akka.actor._
import akka.util._
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object Sentinel {
  def apply(
    sentinels: Seq[Server] = Seq(Server("localhost", 26379)),
    listeners: Set[ActorRef] = Set(),
    connectionTimeout: Option[FiniteDuration] = None,
    connectionHeartbeatDelay: Option[FiniteDuration] = None): Props = {

    val config = ConfigFactory.load()
    Props(classOf[Sentinel], sentinels, listeners,
      connectionTimeout.getOrElse(
        config.getDuration("brando.connection.timeout", TimeUnit.MILLISECONDS).millis),
      connectionHeartbeatDelay)
  }

  case class Server(host: String, port: Int)
  private[brando] case class Connect(sentinels: Seq[Server])
  case class ConnectionFailed(sentinels: Seq[Server]) extends Connection.StateChange
}

class Sentinel(
    var sentinels: Seq[Sentinel.Server],
    var listeners: Set[ActorRef],
    connectionTimeout: FiniteDuration,
    connectionHeartbeatDelay: Option[FiniteDuration]) extends Actor {

  import Sentinel._

  implicit val timeout = Timeout(connectionTimeout)

  var connection = context.system.deadLetters
  var retries = 0

  override def preStart: Unit = {
    listeners.map(context.watch)
    self ! Connect(sentinels)
  }

  def receive: Receive = disconnected

  def connected: Receive = handleListeners orElse {
    case x: Connection.Disconnected ⇒
      connection ! PoisonPill
      context.become(disconnected)
      notifyStateChange(x)
      self ! Connect(sentinels)

    case request: Request ⇒
      connection forward request

    case _ ⇒
  }

  def disconnected: Receive = handleListeners orElse {
    case Connect(Server(host, port) :: tail) ⇒
      notifyStateChange(Connection.Connecting(host, port))
      retries += 1
      connection = context.actorOf(Props(classOf[Connection],
        self, host, port, connectionTimeout, connectionHeartbeatDelay))

    case x: Connection.Connected ⇒
      context.become(connected)
      retries = 0
      val Server(host, port) = sentinels.head
      notifyStateChange(Connection.Connected(host, port))

    case x: Connection.ConnectionFailed ⇒
      context.become(disconnected)
      retries < sentinels.size match {
        case true ⇒
          sentinels = sentinels.tail :+ sentinels.head
          self ! Connect(sentinels)
        case false ⇒
          notifyStateChange(ConnectionFailed(sentinels))
      }

    case request: Request ⇒
      sender ! Status.Failure(new RedisException("Disconnected from the sentinel cluster"))

    case _ ⇒
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
}
