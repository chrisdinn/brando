package brando

import akka.actor._
import akka.pattern._
import akka.util._

import scala.concurrent._
import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory
import java.util.concurrent.TimeUnit

object SentinelClient {
  def apply(): Props = apply(Seq(Sentinel("localhost", 26379)))
  def apply(
    instances: Seq[Sentinel],
    listeners: Set[ActorRef] = Set(),
    connectionTimeout: Option[FiniteDuration] = None,
    connectionHeartbeatDelay: Option[FiniteDuration] = None): Props = {

    val config = ConfigFactory.load()
    Props(classOf[SentinelClient], instances, listeners,
      connectionTimeout.getOrElse(
        config.getDuration("brando.connection.timeout", TimeUnit.MILLISECONDS).millis),
      connectionHeartbeatDelay)
  }

  case class Sentinel(host: String, port: Int)
  private[brando] case class Connect(instances: Seq[Sentinel])
  case class ConnectionFailed(sentinels: Seq[Sentinel]) extends Connection.StateChange
}

class SentinelClient(
    var sentinels: Seq[SentinelClient.Sentinel],
    var listeners: Set[ActorRef],
    connectionTimeout: FiniteDuration,
    connectionHeartbeatDelay: Option[FiniteDuration]) extends Actor with Stash {

  import SentinelClient._
  import context.dispatcher

  implicit val timeout = Timeout(connectionTimeout)

  var connection = context.system.deadLetters
  var retries = 0

  override def preStart: Unit = {
    listeners.map(context.watch(_))
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
    case Connect(Sentinel(host, port) :: tail) ⇒
      notifyStateChange(Connection.Connecting(host, port))
      retries += 1
      connection = context.actorOf(Props(classOf[Connection],
        self, host, port, connectionTimeout, connectionHeartbeatDelay))

    case x: Connection.Connected ⇒
      context.become(connected)
      unstashAll()
      retries = 0
      val Sentinel(host, port) = sentinels.head
      notifyStateChange(Connection.Connected(host, port))

    case x: Connection.ConnectionFailed ⇒
      context.become(disconnected)
      (retries < sentinels.size) match {
        case true ⇒
          sentinels = sentinels.tail :+ sentinels.head
          self ! Connect(sentinels)
        case false ⇒
          notifyStateChange(ConnectionFailed(sentinels))
      }

    case request: Request ⇒
      stash()

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
