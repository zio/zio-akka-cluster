package zio.akka.cluster.pubsub.impl

import akka.actor.{ Actor, ActorRef, ActorSystem, PoisonPill, Props }
import akka.cluster.pubsub.DistributedPubSubMediator.{ Subscribe, SubscribeAck }
import zio.akka.cluster.pubsub.impl.SubscriberImpl.SubscriberActor
import zio.akka.cluster.pubsub.{ MessageEnvelope, Subscriber }
import zio.{ Exit, Promise, Queue, Runtime, Task, Unsafe, ZIO }

private[pubsub] trait SubscriberImpl[A] extends Subscriber[A] {
  val getActorSystem: ActorSystem
  val getMediator: ActorRef

  override def listenWith(topic: String, queue: Queue[A], group: Option[String] = None): Task[Unit] =
    for {
      rts        <- ZIO.runtime[Any]
      subscribed <- Promise.make[Nothing, Unit]
      _          <- ZIO.attempt(
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

    def receive: Actor.Receive = {
      case SubscribeAck(_)      =>
        Unsafe.unsafeCompat { implicit u =>
          rts.unsafe.run(subscribed.succeed(())).getOrThrow()
        }
        ()
      case MessageEnvelope(msg) =>
        Unsafe.unsafeCompat { implicit u =>
          val fiber = rts.unsafe.fork(queue.offer(msg.asInstanceOf[A]))
          fiber.unsafe.addObserver {
            case Exit.Success(_) => ()
            case Exit.Failure(c) => if (c.isInterrupted) self ! PoisonPill // stop listening if the queue was shut down
          }
        }
        ()
    }
  }
}
