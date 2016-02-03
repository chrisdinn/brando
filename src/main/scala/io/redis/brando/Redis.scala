package io.redis.brando

import java.util.concurrent.TimeUnit

import akka.actor._
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object Redis {
  def apply(
    host: String = "localhost",
    port: Int = 6379,
    database: Int = 0,
    auth: Option[String] = None,
    listeners: Set[ActorRef] = Set(),
    connectionTimeout: Option[FiniteDuration] = None,
    connectionRetryDelay: Option[FiniteDuration] = None,
    connectionRetryAttempts: Option[Int] = None,
    connectionHeartbeatDelay: Option[FiniteDuration] = None): Props = {

    val config = ConfigFactory.load()
    Props(classOf[Redis],
      host,
      port,
      database,
      auth,
      listeners,
      connectionTimeout.getOrElse(
        config.getDuration("brando.connection.timeout", TimeUnit.MILLISECONDS).millis),
      Some(connectionRetryDelay.getOrElse(
        config.getDuration("brando.connection.retry.delay", TimeUnit.MILLISECONDS).millis)),
      connectionRetryAttempts,
      connectionHeartbeatDelay)
  }

  case class AuthenticationFailed(host: String, port: Int) extends Connection.StateChange
}

class Redis(
  host: String,
  port: Int,
  database: Int,
  auth: Option[String],
  listeners: Set[ActorRef],
  connectionTimeout: FiniteDuration,
  connectionRetryDelay: Option[FiniteDuration],
  connectionRetryAttempts: Option[Int],
  connectionHeartbeatDelay: Option[FiniteDuration]) extends RedisConnectionSupervisor(
  database, auth, listeners, connectionTimeout, connectionHeartbeatDelay) {

  import ConnectionSupervisor.{Connect, Reconnect}
  import context.dispatcher

  var retries = 0

  override def preStart: Unit = {
    listeners.foreach(context.watch)
    self ! Connect(host, port)
  }

  override def disconnected: Receive =
    disconnectedWithRetry orElse super.disconnected

  def disconnectedWithRetry: Receive = {
    case _@ (_: Request | _: Batch) ⇒
      sender ! Status.Failure(new RedisDisconnectedException(s"Disconnected from $host:$port"))

    case ("auth_ok", x: Connection.Connected) ⇒
      retries = 0
      notifyStateChange(x)
      context.become(connected)

    case Reconnect ⇒
      (connectionRetryDelay, connectionRetryAttempts) match {
        case (Some(delay), Some(maxAttempts)) if maxAttempts > retries ⇒
          retries += 1
          context.system.scheduler.scheduleOnce(delay, connection, Connection.Connect)
        case (Some(delay), None) ⇒
          context.system.scheduler.scheduleOnce(delay, connection, Connection.Connect)
        case _ ⇒
      }
  }
}
