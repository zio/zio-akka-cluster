package zio.akka.cluster.pubsub.impl

import akka.actor.ActorRef
import akka.cluster.pubsub.DistributedPubSubMediator.Publish
import zio.akka.cluster.pubsub.{ MessageEnvelope, Publisher }
import zio.{ Task, ZIO }

private[pubsub] trait PublisherImpl[A] extends Publisher[A] {
  val getMediator: ActorRef

  override def publish(topic: String, data: A, sendOneMessageToEachGroup: Boolean = false): Task[Unit] =
    ZIO.attempt(getMediator ! Publish(topic, MessageEnvelope(data), sendOneMessageToEachGroup))
}
