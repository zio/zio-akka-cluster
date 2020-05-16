package zio.akka.cluster

import zio.{ Has, Ref, Tagged, Task, UIO, ZIO }

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

    def replyToSender[State: Tagged, R](msg: R) =
      ZIO.accessM[Entity[State]](_.get.replyToSender(msg))
    def id[State: Tagged] =
      ZIO.access[Entity[State]](_.get.id)
    def state[State: Tagged] =
      ZIO.access[Entity[State]](_.get.state)
    def stop[State: Tagged] =
      ZIO.accessM[Entity[State]](_.get.stop)
    def passivate[State: Tagged] =
      ZIO.accessM[Entity[State]](_.get.passivate)
    def passivateAfter[State: Tagged](duration: Duration) =
      ZIO.accessM[Entity[State]](_.get.passivateAfter(duration))

  }
}
