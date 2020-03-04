package zio.akka.cluster.pubsub

import akka.actor.{ ActorRef, ActorSystem }
import akka.cluster.pubsub.DistributedPubSub
import zio.akka.cluster.pubsub.impl.{ PublisherImpl, SubscriberImpl }
import zio.{ Has, Queue, Task, ZIO }

/**
 *  A `Publisher[A]` is able to send messages of type `A` through Akka PubSub.
 */
trait Publisher[A] {
  def publish(topic: String, data: A, sendOneMessageToEachGroup: Boolean = false): Task[Unit]
}

/**
 *  A `Subscriber[A]` is able to receive messages of type `A` through Akka PubSub.
 */
trait Subscriber[A] {

  def listen(topic: String, group: Option[String] = None): Task[Queue[A]] =
    Queue.unbounded[A].tap(listenWith(topic, _, group))

  def listenWith(topic: String, queue: Queue[A], group: Option[String] = None): Task[Unit]
}

/**
 *  A `PubSub[A]` is able to both send and receive messages of type `A` through Akka PubSub.
 */
trait PubSub[A] extends Publisher[A] with Subscriber[A]

object PubSub {

  private def getMediator(actorSystem: ActorSystem): Task[ActorRef] = Task(DistributedPubSub(actorSystem).mediator)

  /**
   *  Creates a new `Publisher[A]`.
   */
  def createPublisher[A]: ZIO[Has[ActorSystem], Throwable, Publisher[A]] =
    for {
      actorSystem <- ZIO.access[Has[ActorSystem]](_.get)
      mediator    <- getMediator(actorSystem)
    } yield new Publisher[A] with PublisherImpl[A] {
      override val getMediator: ActorRef = mediator
    }

  /**
   *  Creates a new `Subscriber[A]`.
   */
  def createSubscriber[A]: ZIO[Has[ActorSystem], Throwable, Subscriber[A]] =
    for {
      actorSystem <- ZIO.access[Has[ActorSystem]](_.get)
      mediator    <- getMediator(actorSystem)
    } yield new Subscriber[A] with SubscriberImpl[A] {
      override val getActorSystem: ActorSystem = actorSystem
      override val getMediator: ActorRef       = mediator
    }

  /**
   *  Creates a new `PubSub[A]`.
   */
  def createPubSub[A]: ZIO[Has[ActorSystem], Throwable, PubSub[A]] =
    for {
      actorSystem <- ZIO.access[Has[ActorSystem]](_.get)
      mediator    <- getMediator(actorSystem)
    } yield new PubSub[A] with PublisherImpl[A] with SubscriberImpl[A] {
      override val getActorSystem: ActorSystem = actorSystem
      override val getMediator: ActorRef       = mediator
    }

}
