package zio.akka.cluster

import akka.actor.{ Actor, ActorSystem, Address, PoisonPill, Props }
import akka.cluster.ClusterEvent._
import zio.{ Exit, Queue, Runtime, Unsafe, ZIO }

object Cluster {

  private val cluster: ZIO[ActorSystem, Throwable, akka.cluster.Cluster] =
    for {
      actorSystem <- ZIO.service[ActorSystem]
      cluster     <- ZIO.attempt(akka.cluster.Cluster(actorSystem))
    } yield cluster

  /**
   *  Returns the current state of the cluster.
   */
  val clusterState: ZIO[ActorSystem, Throwable, CurrentClusterState] =
    for {
      cluster <- cluster
      state   <- ZIO.attempt(cluster.state)
    } yield state

  /**
   *  Joins a cluster using the provided seed nodes.
   */
  def join(seedNodes: List[Address]): ZIO[ActorSystem, Throwable, Unit] =
    for {
      cluster <- cluster
      _       <- ZIO.attempt(cluster.joinSeedNodes(seedNodes))
    } yield ()

  /**
   *  Leaves the current cluster.
   */
  val leave: ZIO[ActorSystem, Throwable, Unit] =
    for {
      cluster <- cluster
      _       <- ZIO.attempt(cluster.leave(cluster.selfAddress))
    } yield ()

  /**
   *  Subscribes to the current cluster events. It returns an unbounded queue that will be fed with cluster events.
   *  `initialStateAsEvents` indicates if you want to receive previous cluster events leading to the current state, or only future events.
   *  To unsubscribe, use `queue.shutdown`.
   *  To use a bounded queue, see `clusterEventsWith`.
   */
  def clusterEvents(
    initialStateAsEvents: Boolean = false
  ): ZIO[ActorSystem, Throwable, Queue[ClusterDomainEvent]] =
    Queue.unbounded[ClusterDomainEvent].tap(clusterEventsWith(_, initialStateAsEvents))

  /**
   *  Subscribes to the current cluster events, using the provided queue to push the events.
   *  `initialStateAsEvents` indicates if you want to receive previous cluster events leading to the current state, or only future events.
   *  To unsubscribe, use `queue.shutdown`.
   */
  def clusterEventsWith(
    queue: Queue[ClusterDomainEvent],
    initialStateAsEvents: Boolean = false
  ): ZIO[ActorSystem, Throwable, Unit] =
    for {
      rts         <- ZIO.runtime[ActorSystem]
      actorSystem <- ZIO.service[ActorSystem]
      _           <- ZIO.attempt(actorSystem.actorOf(Props(new SubscriberActor(rts, queue, initialStateAsEvents))))
    } yield ()

  private[cluster] class SubscriberActor(
    rts: Runtime[Any],
    queue: Queue[ClusterDomainEvent],
    initialStateAsEvents: Boolean
  ) extends Actor {

    val initialState: SubscriptionInitialStateMode =
      if (initialStateAsEvents) InitialStateAsEvents else InitialStateAsSnapshot
    akka.cluster.Cluster(context.system).subscribe(self, initialState, classOf[ClusterDomainEvent])

    def receive: Actor.Receive = {
      case ev: ClusterDomainEvent =>
        Unsafe.unsafeCompat { implicit u =>
          val fiber = rts.unsafe.fork(queue.offer(ev))
          fiber.unsafe.addObserver {
            case Exit.Success(_) => ()
            case Exit.Failure(c) => if (c.isInterrupted) self ! PoisonPill // stop listening if the queue was shut down
          }
        }
        ()
      case _                      => ()
    }
  }

}
