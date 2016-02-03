package io.redis.brando

import akka.actor._

/**
 * This actor implements Akka stashing to stash requests to Redis when the connection
 * is not established. Instead of direct invocation of the Brando, which throws an
 * exception, this actor stashes all requests and passes them to the Brando when the
 * connection is established. It avoids exceptions during the Brando startup or when
 * the connection is temporarily broken down.
 *
 * Note: This actor re-enables the behavior of the Brando in version 2.x.x
 *
 * To use it:
 *
 * 1. create Brando actor
 * 2. create this actor with the Brando as parameter
 * 3. use this actor to querying redis
 */
class StashingRedis(redis: ActorRef) extends Actor with Stash {

  override def receive: Receive = setDisconnected()

  protected def stateChanges: Receive = {
    case Connection.Connecting(_, _)       ⇒ // do nothing
    case Connection.ConnectionFailed(_, _) ⇒ // do nothing
    case Sentinel.ConnectionFailed(_)      ⇒ // do nothing
    case Redis.AuthenticationFailed(_, _)  ⇒ // do nothing
    case Connection.Connected(_, _)        ⇒ setConnected()
    case Connection.Disconnected(_, _)     ⇒ setDisconnected()
  }

  /** pass everything through, connection is established */
  protected def connected: Receive = {
    case message ⇒ redis.tell(message, sender)
  }

  /** stash everything, the connection is not established */
  protected def disconnected: Receive = {
    case message ⇒ stash()
  }

  /** set the actor's behavior as 'connected' */
  protected def setConnected() = {
    unstashAll() // restore all stashed messages
    context.become(stateChanges orElse connected)
  }

  /** set the actor's behavior as 'disconnected' */
  protected def setDisconnected(): Receive = {
    val behavior = stateChanges orElse disconnected
    context.become(behavior)
    behavior
  }

  // register self as a listener on the redis instance to listen on state changes
  redis ! self
}

object StashingRedis {

  def apply(redis: ActorRef) =
    Props(classOf[StashingRedis], redis)

}
