package zio.akka.cluster.sharding

import akka.actor.{ Actor, ActorContext, ActorRef, ActorSystem, PoisonPill, Props }
import akka.cluster.sharding.{ ClusterSharding, ClusterShardingSettings }
import zio.akka.cluster.sharding
import zio.akka.cluster.sharding.MessageEnvelope.{ MessagePayload, PoisonPillPayload }
import zio.{ Ref, Runtime, Task, UIO, ZIO }

/**
 *  A `Sharding[M]` is able to send messages of type `M` to a sharded entity or to stop one.
 */
trait Sharding[M] {

  def send(entityId: String, data: M): Task[Unit]

  def stop(entityId: String): Task[Unit]

}

object Sharding {

  /**
   *  Starts cluster sharding on this node for a given entity type.
   *
   * @param name the name of the entity type
   * @param onMessage the behavior of the entity when it receives a message
   * @param numberOfShards a fixed number of shards
   * @return a [[Sharding]] object that can be used to send messages to sharded entities
   */
  def start[Msg, State](
    name: String,
    onMessage: Msg => ZIO[Entity[State], Nothing, Unit],
    numberOfShards: Int = 100
  ): ZIO[ActorSystem, Throwable, Sharding[Msg]] =
    for {
      rts         <- ZIO.runtime[ActorSystem]
      actorSystem = rts.Environment
      shardingRegion <- Task(
                         ClusterSharding(actorSystem).start(
                           typeName = name,
                           entityProps = Props(new ShardEntity(rts)(onMessage)),
                           settings = ClusterShardingSettings(actorSystem),
                           extractEntityId = {
                             case MessageEnvelope(entityId, payload) =>
                               payload match {
                                 case MessageEnvelope.PoisonPillPayload    => (entityId, PoisonPill)
                                 case p: MessageEnvelope.MessagePayload[_] => (entityId, p)
                               }
                           },
                           extractShardId = {
                             case msg: MessageEnvelope => (math.abs(msg.entityId.hashCode) % numberOfShards).toString
                           }
                         )
                       )
    } yield
      new ShardingImpl[Msg] {
        override val getShardingRegion: ActorRef = shardingRegion
      }

  /**
   *  Starts cluster sharding in proxy mode for a given entity type.
   *
   * @param name the name of the entity type
   * @param role an optional role to specify that this entity type is located on cluster nodes with a specific role
   * @param numberOfShards a fixed number of shards
   * @return a [[Sharding]] object that can be used to send messages to sharded entities on other nodes
   */
  def startProxy[Msg, State](
    name: String,
    role: Option[String],
    numberOfShards: Int = 100
  ): ZIO[ActorSystem, Throwable, Sharding[Msg]] =
    for {
      rts         <- ZIO.runtime[ActorSystem]
      actorSystem = rts.Environment
      shardingRegion <- Task(
                         ClusterSharding(actorSystem).startProxy(
                           typeName = name,
                           role,
                           extractEntityId = {
                             case MessageEnvelope(entityId, payload) =>
                               payload match {
                                 case MessageEnvelope.PoisonPillPayload    => (entityId, PoisonPill)
                                 case p: MessageEnvelope.MessagePayload[_] => (entityId, p)
                               }
                           },
                           extractShardId = {
                             case msg: MessageEnvelope => (math.abs(msg.entityId.hashCode) % numberOfShards).toString
                           }
                         )
                       )
    } yield
      new ShardingImpl[Msg] {
        override val getShardingRegion: ActorRef = shardingRegion
      }

  private[sharding] trait ShardingImpl[Msg] extends Sharding[Msg] {

    val getShardingRegion: ActorRef

    override def send(entityId: String, data: Msg): Task[Unit] =
      Task(getShardingRegion ! sharding.MessageEnvelope(entityId, MessagePayload(data)))

    override def stop(entityId: String): Task[Unit] =
      Task(getShardingRegion ! sharding.MessageEnvelope(entityId, PoisonPillPayload))
  }

  private[sharding] class ShardEntity[Msg, State](rts: Runtime[Any])(
    onMessage: Msg => ZIO[Entity[State], Nothing, Unit]
  ) extends Actor {

    val ref: Ref[Option[State]]    = rts.unsafeRun(Ref.make[Option[State]](None))
    val actorContext: ActorContext = context
    val entity: Entity[State] = new Entity[State] {
      override def state: Ref[Option[State]] = ref
      override def stop: UIO[Unit]           = UIO(actorContext.stop(self))
    }

    def receive: PartialFunction[Any, Unit] = {
      case MessagePayload(msg) =>
        rts.unsafeRunSync(onMessage(msg.asInstanceOf[Msg]).provide(entity))
        ()
      case _ =>
    }
  }
}
