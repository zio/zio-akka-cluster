package zio.akka.cluster.pubsub

case class MessageEnvelope[Msg](msg: Msg)
