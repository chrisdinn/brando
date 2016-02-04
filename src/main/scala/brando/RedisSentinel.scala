package brando

import java.util.concurrent.TimeUnit

import akka.actor._
import akka.pattern._
import com.typesafe.config.ConfigFactory

import scala.concurrent._
import scala.concurrent.duration._

object RedisSentinel {
  def apply(
    master: String,
    sentinelClient: ActorRef,
    database: Int = 0,
    auth: Option[String] = None,
    listeners: Set[ActorRef] = Set(),
    connectionTimeout: Option[FiniteDuration] = None,
    connectionRetryDelay: Option[FiniteDuration] = None,
    connectionHeartbeatDelay: Option[FiniteDuration] = None): Props = {

    val config = ConfigFactory.load()
    Props(classOf[RedisSentinel],
      master,
      sentinelClient,
      database,
      auth,
      listeners,
      connectionTimeout.getOrElse(
        config.getDuration("brando.connection.timeout", TimeUnit.MILLISECONDS).millis),
      connectionRetryDelay.getOrElse(
        config.getDuration("brando.connection.retry.delay", TimeUnit.MILLISECONDS).millis),
      connectionHeartbeatDelay)
  }

  private[brando] case object SentinelConnect
}

class RedisSentinel(
  master: String,
  sentinelClient: ActorRef,
  database: Int,
  auth: Option[String],
  listeners: Set[ActorRef],
  connectionTimeout: FiniteDuration,
  connectionRetryDelay: FiniteDuration,
  connectionHeartbeatDelay: Option[FiniteDuration]) extends RedisConnectionSupervisor(database, auth,
  listeners, connectionTimeout, connectionHeartbeatDelay) {

  import ConnectionSupervisor.{Connect, Reconnect}
  import RedisSentinel._
  import context.dispatcher

  override def preStart: Unit = {
    listeners.map(context.watch)
    self ! SentinelConnect
  }

  override def disconnected: Receive =
    disconnectedWithSentinel orElse super.disconnected

  def disconnectedWithSentinel: Receive = {
    case _@ (_: Request | _: Batch) ⇒
      sender ! Status.Failure(new RedisDisconnectedException(s"Disconnected from $master"))

    case Reconnect ⇒
      context.system.scheduler.scheduleOnce(connectionRetryDelay, self, SentinelConnect)

    case SentinelConnect ⇒
      (sentinelClient ? Request("SENTINEL", "MASTER", master)) map {
        case Response.AsStrings(res) ⇒
          val (ip, port) = extractIpPort(res)
          self ! Connect(ip, port)
      } recover { case _ ⇒ self ! Reconnect }

    case x: Connection.Connected ⇒
      isValidMaster map {
        case true ⇒
          authenticate(x)
        case false ⇒
          self ! Reconnect
      } recover { case _ ⇒ self ! Reconnect }
  }

  def extractIpPort(config: Seq[String]): (String, Int) = {
    var i, port = 0
    var ip: String = ""
    while ((i < config.size) && (ip == "" || port == 0)) {
      if (config(i) == "port") port = config(i + 1).toInt
      if (config(i) == "ip") ip = config(i + 1)
      i = i + 1
    }
    (ip, port)
  }

  def isValidMaster: Future[Boolean] = {
    (connection ? Request("INFO")) map {
      case Response.AsString(res) ⇒
        res.contains("role:master")
    }
  }
}
