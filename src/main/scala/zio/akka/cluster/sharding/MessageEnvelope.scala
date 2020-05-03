package zio.akka.cluster.sharding

import zio.akka.cluster.sharding.MessageEnvelope.Payload

case class MessageEnvelope(entityId: String, data: Payload)

object MessageEnvelope {

  sealed trait Payload
  case object PoisonPillPayload            extends Payload
  case object PassivatePayload             extends Payload
  case class MessagePayload[Msg](msg: Msg) extends Payload

}
