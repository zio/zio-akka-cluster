package zio.akka.cluster.pubsub.impl

import akka.actor.{ Actor, ActorRef, ActorSystem, PoisonPill, Props }
import akka.cluster.pubsub.DistributedPubSubMediator.{ Subscribe, SubscribeAck }
import zio.Exit.{ Failure, Success }
import zio.akka.cluster.pubsub.impl.SubscriberImpl.SubscriberActor
import zio.akka.cluster.pubsub.{ MessageEnvelope, Subscriber }
import zio.{ Promise, Queue, Runtime, Task }

private[pubsub] trait SubscriberImpl[A] extends Subscriber[A] {
  val getActorSystem: ActorSystem
  val getMediator: ActorRef

  override def listenWith(topic: String, queue: Queue[A], group: Option[String] = None): Task[Unit] =
    for {
      rts        <- Task.runtime
      subscribed <- Promise.make[Nothing, Unit]
      _          <- Task(
                      getActorSystem.actorOf(Props(new SubscriberActor[A](getMediator, topic, group, rts, queue, subscribed)))
                    )
      _          <- subscribed.await
    } yield ()
}

object SubscriberImpl {
  private[impl] class SubscriberActor[A](
    mediator: ActorRef,
    topic: String,
    group: Option[String],
    rts: Runtime[Any],
    queue: Queue[A],
    subscribed: Promise[Nothing, Unit]
  ) extends Actor {

    mediator ! Subscribe(topic, group, self)

    def receive: PartialFunction[Any, Unit] = {
      case SubscribeAck(_)      =>
        rts.unsafeRunSync(subscribed.succeed(()))
        ()
      case MessageEnvelope(msg) =>
        rts.unsafeRunAsync(queue.offer(msg.asInstanceOf[A])) {
          case Success(_)     => ()
          case Failure(cause) => if (cause.interrupted) self ! PoisonPill // stop listening if the queue was shut down
        }
    }
  }
}
