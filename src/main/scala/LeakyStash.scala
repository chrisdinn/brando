package akka.actor

import akka.dispatch.Envelope

/** Custom implementation of akka.actor.Stash leaking when the capacity is full. Oldest messages are being dropped. */
trait LeakyStash extends Stash { outer: Actor ⇒

  /* The private stash of the actor. It is only accessible using `stash()` and
   * `unstashAll()`.
   */
  private var theStash = Vector.empty[Envelope]

  /** stash capacity before it starts leaking */
  protected def stashCapacity: Long

  private def actorCell = context.asInstanceOf[ActorCell]

  override def stash(): Unit = {
    val currMsg = actorCell.currentMessage
    if (theStash.nonEmpty && (currMsg eq theStash.last))
      throw new IllegalStateException("Can't stash the same message " + currMsg + " more than once")
    // capacity must be positive
    if (stashCapacity <= 0)
      throw new StashOverflowException("Couldn't enqueue message " + currMsg + " to stash of " + self)
    // drop all messages while the stash is full
    while (theStash.size >= stashCapacity) theStash = theStash.tail
    // enqueue the message
    theStash :+= currMsg
  }
}
