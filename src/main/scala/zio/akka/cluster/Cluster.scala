package zio.akka.cluster

import akka.actor.{ Actor, ActorSystem, Address, PoisonPill, Props }
import akka.cluster.ClusterEvent._
import zio.Exit.{ Failure, Success }
import zio.{ Has, Queue, Runtime, Task, ZIO }

object Cluster {

  private val cluster: ZIO[Has[ActorSystem], Throwable, akka.cluster.Cluster] =
    for {
      actorSystem <- ZIO.access[Has[ActorSystem]](_.get)
      cluster     <- Task(akka.cluster.Cluster(actorSystem))
    } yield cluster

  /**
   *  Returns the current state of the cluster.
   */
  val clusterState: ZIO[Has[ActorSystem], Throwable, CurrentClusterState] =
    for {
      cluster <- cluster
      state   <- Task(cluster.state)
    } yield state

  /**
   *  Joins a cluster using the provided seed nodes.
   */
  def join(seedNodes: List[Address]): ZIO[Has[ActorSystem], Throwable, Unit] =
    for {
      cluster <- cluster
      _       <- Task(cluster.joinSeedNodes(seedNodes))
    } yield ()

  /**
   *  Leaves the current cluster.
   */
  val leave: ZIO[Has[ActorSystem], Throwable, Unit] =
    for {
      cluster <- cluster
      _       <- Task(cluster.leave(cluster.selfAddress))
    } yield ()

  /**
   *  Subscribes to the current cluster events. It returns an unbounded queue that will be fed with cluster events.
   *  `initialStateAsEvents` indicates if you want to receive previous cluster events leading to the current state, or only future events.
   *  To unsubscribe, use `queue.shutdown`.
   *  To use a bounded queue, see `clusterEventsWith`.
   */
  def clusterEvents(
    initialStateAsEvents: Boolean = false
  ): ZIO[Has[ActorSystem], Throwable, Queue[ClusterDomainEvent]] =
    Queue.unbounded[ClusterDomainEvent].tap(clusterEventsWith(_, initialStateAsEvents))

  /**
   *  Subscribes to the current cluster events, using the provided queue to push the events.
   *  `initialStateAsEvents` indicates if you want to receive previous cluster events leading to the current state, or only future events.
   *  To unsubscribe, use `queue.shutdown`.
   */
  def clusterEventsWith(
    queue: Queue[ClusterDomainEvent],
    initialStateAsEvents: Boolean = false
  ): ZIO[Has[ActorSystem], Throwable, Unit] =
    for {
      rts         <- Task.runtime
      actorSystem <- ZIO.access[Has[ActorSystem]](_.get)
      _           <- Task(actorSystem.actorOf(Props(new SubscriberActor(rts, queue, initialStateAsEvents))))
    } yield ()

  private[cluster] class SubscriberActor(
    rts: Runtime[Any],
    queue: Queue[ClusterDomainEvent],
    initialStateAsEvents: Boolean
  ) extends Actor {

    val initialState: SubscriptionInitialStateMode =
      if (initialStateAsEvents) InitialStateAsEvents else InitialStateAsSnapshot
    akka.cluster.Cluster(context.system).subscribe(self, initialState, classOf[ClusterDomainEvent])

    def receive: PartialFunction[Any, Unit] = {
      case ev: ClusterDomainEvent =>
        rts.unsafeRunAsync(queue.offer(ev)) {
          case Success(_)     => ()
          case Failure(cause) => if (cause.interrupted) self ! PoisonPill // stop listening if the queue was shut down
        }
      case _                      =>
    }
  }

}
