package akka.actor

import akka.dispatch.Envelope

/** Custom implementation of akka.actor.Stash leaking when the capacity is full. Oldest messages are being dropped. */
trait LeakyStash extends Stash { outer: Actor ⇒

  /* The private stash of the actor. It is only accessible using `stash()` and
   * `unstashAll()`.
   */
  private var theStash = Vector.empty[Envelope]

  /** stash capacity before it starts leaking */
  protected def capacity: Long

  private def actorCell = context.asInstanceOf[ActorCell]

  override def stash(): Unit = {
    val currMsg = actorCell.currentMessage
    if (theStash.nonEmpty && (currMsg eq theStash.last))
      throw new IllegalStateException("Can't stash the same message " + currMsg + " more than once")
    // capacity must be positive
    if (capacity <= 0)
      throw new StashOverflowException("Couldn't enqueue message " + currMsg + " to stash of " + self)
    // drop all messages while the stash is full
    while (theStash.size >= capacity) theStash = theStash.tail
    // enqueue the message
    theStash :+= currMsg
  }

  private[akka] override def unstashAll(filterPredicate: Any ⇒ Boolean): Unit = {
    try {
      val i = theStash.reverseIterator.filter(envelope ⇒ filterPredicate(envelope.message))
      while (i.hasNext) enqueueFirst(i.next())
    } finally {
      theStash = Vector.empty[Envelope]
    }
  }

  /**
   * Enqueues `envelope` at the first position in the mailbox. If the message contained in
   * the envelope is a `Terminated` message, it will be ensured that it can be re-received
   * by the actor.
   */
  private def enqueueFirst(envelope: Envelope): Unit = {
    mailbox.enqueueFirst(self, envelope)
    envelope.message match {
      case Terminated(ref) ⇒ actorCell.terminatedQueuedFor(ref)
      case _               ⇒
    }
  }
}
