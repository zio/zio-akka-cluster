package zio.akka.cluster

import akka.actor.ActorContext
import zio.{ Ref, Tag, Task, UIO, URIO, ZIO }

import scala.concurrent.duration.Duration

package object sharding {
  type Entity[State] = Entity.Service[State]

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
      ZIO.serviceWithZIO[Entity[State]](_.replyToSender(msg))
    def context[State: Tag]: URIO[Entity[State], ActorContext]                            =
      ZIO.service[Entity[State]].map(_.context)
    def id[State: Tag]: URIO[Entity[State], String]                                       =
      ZIO.service[Entity[State]].map(_.id)
    def state[State: Tag]: URIO[Entity[State], Ref[Option[State]]]                        =
      ZIO.service[Entity[State]].map(_.state)
    def stop[State: Tag]: ZIO[Entity[State], Nothing, Unit]                               =
      ZIO.serviceWithZIO[Entity[State]](_.stop)
    def passivate[State: Tag]: ZIO[Entity[State], Nothing, Unit]                          =
      ZIO.serviceWithZIO[Entity[State]](_.passivate)
    def passivateAfter[State: Tag](duration: Duration): ZIO[Entity[State], Nothing, Unit] =
      ZIO.serviceWithZIO[Entity[State]](_.passivateAfter(duration))

  }
}
