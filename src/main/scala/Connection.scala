package brando

import akka.actor._
import akka.actor.ActorDSL._
import akka.pattern._
import akka.io._
import akka.util._

import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration._

import java.net.InetSocketAddress

object Connection {
  trait StateChange
  case class Connecting(host: String, port: Int) extends StateChange
  case class Connected(host: String, port: Int) extends StateChange
  case class Disconnected(host: String, port: Int) extends StateChange
  case class ConnectionFailed(host: String, port: Int) extends StateChange

  private[brando] case object Connect
  private[brando] case class CommandAck(sender: ActorRef) extends Tcp.Event
  private[brando] case class Heartbeat(delay: FiniteDuration)
}

private[brando] class Connection(
    listener: ActorRef,
    host: String,
    port: Int,
    connectionTimeout: FiniteDuration,
    heartbeatDelay: Option[FiniteDuration]) extends Actor with ReplyParser {

  import Connection._
  import context.dispatcher

  var socket: ActorRef = _

  var lastDataReceived = now

  val requesterQueue = mutable.Queue.empty[ActorRef]
  var subscribers: Map[ByteString, Seq[ActorRef]] = Map.empty

  def getSubscribers(channel: ByteString): Seq[ActorRef] =
    subscribers.get(channel).getOrElse(Seq.empty[ActorRef])

  override def preStart(): Unit = self ! Connect

  def receive = {
    case subRequest: Request if (subRequest.command.utf8String.toLowerCase == "subscribe") ⇒
      subRequest.params map { x ⇒
        subscribers = subscribers + ((x, getSubscribers(x).+:(sender)))
      }
      socket ! Tcp.Write(subRequest.toByteString, CommandAck(sender))

    case request: Request ⇒
      socket ! Tcp.Write(request.toByteString, CommandAck(sender))

    case batch: Batch ⇒
      val requester = sender
      val batcher = actor(new Act {
        var responses = List[Any]()
        become {
          case response if (responses.size + 1) < batch.requests.size ⇒
            responses = responses :+ response
          case response ⇒
            requester ! (responses :+ response)
            self ! PoisonPill
        }
      })
      batch.requests.foreach(self.tell(_, batcher))

    case CommandAck(sender) ⇒
      requesterQueue.enqueue(sender)

    case Tcp.Received(data) ⇒
      lastDataReceived = now
      parseReply(data) { reply ⇒
        reply match {
          case Some(List(
            Some(x: ByteString),
            Some(channel: ByteString),
            Some(message: ByteString))) if (x.utf8String == "message") ⇒

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
      listener ! ConnectionFailed(host, port)

    case x: Tcp.ConnectionClosed ⇒
      requesterQueue.clear
      listener ! Disconnected(host, port)

    case Connect ⇒
      val address = new InetSocketAddress(host, port)
      listener ! Connecting(host, port)
      IO(Tcp)(context.system) ! Tcp.Connect(address, timeout = Some(connectionTimeout))

    case x: Tcp.Connected ⇒
      socket = sender
      socket ! Tcp.Register(self, useResumeWriting = false)
      (self ? Request("PING"))(connectionTimeout) map {
        case _ ⇒
          listener ! Connected(host, port)
          heartbeatDelay map (d ⇒
            context.system.scheduler.schedule(0.seconds, 1.seconds, self, Heartbeat(d)))
      } recover {
        case _ ⇒
          listener ! ConnectionFailed(host, port)
      }

    case Heartbeat(delay) ⇒
      val idle = now - lastDataReceived
      ((idle > delay.toMillis * 2), (idle > (delay.toMillis))) match {
        case (true, true) ⇒
          socket ! Tcp.Close
        case (false, true) ⇒
          self ! Request("PING")
        case _ ⇒
      }

    case _ ⇒
  }

  def now = System.currentTimeMillis
}

