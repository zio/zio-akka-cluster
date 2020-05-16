package zio.akka.cluster

import zio.{ Has, Ref, Tag, Task, UIO, ZIO }

import scala.concurrent.duration.Duration

package object sharding {
  type Entity[State] = Has[Entity.Service[State]]

  object Entity {

    trait Service[State] {
      def replyToSender[R](msg: R): Task[Unit]
      def id: String
      def state: Ref[Option[State]]
      def stop: UIO[Unit]
      def passivate: UIO[Unit]
      def passivateAfter(duration: Duration): UIO[Unit]
    }

    def replyToSender[State: Tag, R](msg: R) =
      ZIO.accessM[Entity[State]](_.get.replyToSender(msg))
    def id[State: Tag] =
      ZIO.access[Entity[State]](_.get.id)
    def state[State: Tag] =
      ZIO.access[Entity[State]](_.get.state)
    def stop[State: Tag] =
      ZIO.accessM[Entity[State]](_.get.stop)
    def passivate[State: Tag] =
      ZIO.accessM[Entity[State]](_.get.passivate)
    def passivateAfter[State: Tag](duration: Duration) =
      ZIO.accessM[Entity[State]](_.get.passivateAfter(duration))

  }
}
