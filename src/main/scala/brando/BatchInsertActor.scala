package brando

import akka.actor.{Actor, ActorRef, PoisonPill, Props}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

class BatchInsertActor(requester:ActorRef, batch: Batch)(implicit ex: ExecutionContext) extends Actor{
  val timer = context.system.scheduler.scheduleOnce(15.seconds, self, "terminate") // Todo: Parameterise this
  context.system.log.debug(s"Starting batch actor: ${self.path}")
  var responses = List[Any]()

  override def receive: Receive = {
    case "terminate" ⇒
      context.system.log.error(s"Terminating batch actor due to timeout.  Responses recieved: $responses on actor ${self.path}")
      self ! PoisonPill
    case response if (responses.size + 1) < batch.requests.size ⇒
      responses = responses :+ response
    case response ⇒
      requester ! (responses :+ response)
      context.system.log.debug(s"Batch request terminating normally on actor ${self.path}")
      timer.cancel()
      self ! PoisonPill
  }
}


object BatchInsertActor{
  def props(requester:ActorRef, batch: Batch)(implicit ex: ExecutionContext): Props ={
    Props(new BatchInsertActor(requester,batch))
  }
}