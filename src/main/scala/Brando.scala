package brando

import akka.actor.{ Actor, ActorRef, Props }
import akka.io.{ IO, Tcp }
import java.net.InetSocketAddress
import akka.util.ByteString
import collection.immutable.Queue

case class Connect(manager: ActorRef, address: InetSocketAddress)
case class Connected(connection: ActorRef)
case class Available(connection: ActorRef)

class Connection extends Actor {

  var tcpConnection: ActorRef = _

  var owner: ActorRef = _
  var caller: ActorRef = _

  def receive = {

    case Connect(manager, address) ⇒
      owner = sender
      manager ! Tcp.Connect(address)

    case Tcp.Connected(remoteAddress, localAddress) ⇒
      tcpConnection = sender
      tcpConnection ! Tcp.Register(self)
      owner ! Connected(self)

    case Tcp.Received(data) ⇒
      caller ! Reply(data)
      owner ! Available(self)

    case request: Request ⇒
      caller = sender
      tcpConnection ! Tcp.Write(request.toByteString, Tcp.NoAck)

  }

}

class Brando extends Actor {

  val address = new InetSocketAddress("localhost", 6379)
  val connectionManager = IO(Tcp)(context.system)
  val connection = context.actorOf(Props[Connection])

  var availableConnection: Option[ActorRef] = None
  var pendingRequests = Queue[Pair[Request, ActorRef]]()

  connection ! Connect(connectionManager, address)

  def openConnection(connection: ActorRef) =
    if (pendingRequests.isEmpty) {
      availableConnection = Some(connection)
    } else {
      val ((request, caller), queue) = pendingRequests.dequeue
      pendingRequests = queue
      connection.tell(request, caller)
    }

  def receive = {

    case Connected(connection) ⇒ openConnection(connection)

    case Available(connection) ⇒ openConnection(connection)

    case request: Request ⇒
      availableConnection match {
        case Some(connection) ⇒
          availableConnection = None
          connection forward request
        case None ⇒
          pendingRequests = pendingRequests.enqueue(Pair(request, sender))
      }

    case x ⇒ println("Unexpected " + x + "\r\n")

  }

}