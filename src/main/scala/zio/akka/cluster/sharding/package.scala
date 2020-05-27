package zio.akka.cluster

import akka.actor.ActorContext
import zio.{ Has, Ref, Tag, Task, UIO, URIO, ZIO }

import scala.concurrent.duration.Duration

package object sharding {
  type Entity[State] = Has[Entity.Service[State]]

  object Entity {

    trait Service[State] {
      def context: ActorContext
      def replyToSender[R](msg: R): Task[Unit]
      def id: String
      def state: Ref[Option[State]]
      def stop: UIO[Unit]
      def passivate: UIO[Unit]
      def passivateAfter(duration: Duration): UIO[Unit]
    }

    def replyToSender[State: Tag, R](msg: R): ZIO[Entity[State], Throwable, Unit]         =
      ZIO.accessM[Entity[State]](_.get.replyToSender(msg))
    def context[State: Tag]: URIO[Entity[State], ActorContext]                            =
      ZIO.access[Entity[State]](_.get.context)
    def id[State: Tag]: URIO[Entity[State], String]                                       =
      ZIO.access[Entity[State]](_.get.id)
    def state[State: Tag]: URIO[Entity[State], Ref[Option[State]]]                        =
      ZIO.access[Entity[State]](_.get.state)
    def stop[State: Tag]: ZIO[Entity[State], Nothing, Unit]                               =
      ZIO.accessM[Entity[State]](_.get.stop)
    def passivate[State: Tag]: ZIO[Entity[State], Nothing, Unit]                          =
      ZIO.accessM[Entity[State]](_.get.passivate)
    def passivateAfter[State: Tag](duration: Duration): ZIO[Entity[State], Nothing, Unit] =
      ZIO.accessM[Entity[State]](_.get.passivateAfter(duration))

  }
}
