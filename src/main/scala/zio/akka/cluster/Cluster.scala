package zio.akka.cluster

import akka.actor.{ Actor, ActorSystem, Address, PoisonPill, Props }
import akka.cluster.ClusterEvent.{ ClusterDomainEvent, CurrentClusterState }
import zio.Exit.{ Failure, Success }
import zio.{ Queue, Runtime, Task, ZIO }

object Cluster {

  private val cluster: ZIO[ActorSystem, Throwable, akka.cluster.Cluster] =
    for {
      actorSystem <- ZIO.environment[ActorSystem]
      cluster     <- Task(akka.cluster.Cluster(actorSystem))
    } yield cluster

  /**
   *  Returns the current state of the cluster.
   */
  val clusterState: ZIO[ActorSystem, Throwable, CurrentClusterState] =
    for {
      cluster <- cluster
      state   <- Task(cluster.state)
    } yield state

  /**
   *  Joins a cluster using the provided seed nodes.
   */
  def join(seedNodes: List[Address]): ZIO[ActorSystem, Throwable, Unit] =
    for {
      cluster <- cluster
      _       <- Task(cluster.joinSeedNodes(seedNodes))
    } yield ()

  /**
   *  Leaves the current cluster.
   */
  val leave: ZIO[ActorSystem, Throwable, Unit] =
    for {
      cluster <- cluster
      _       <- Task(cluster.leave(cluster.selfAddress))
    } yield ()

  /**
   *  Subscribes to the current cluster events. It returns an unbounded queue that will be fed with cluster events.
   *  To unsubscribe, use `queue.shutdown`.
   *  To use a bounded queue, see `clusterEventsWith`.
   */
  val clusterEvents: ZIO[ActorSystem, Throwable, Queue[ClusterDomainEvent]] =
    Queue.unbounded[ClusterDomainEvent].tap(clusterEventsWith)

  /**
   *  Subscribes to the current cluster events, using the provided queue to push the events.
   *  To unsubscribe, use `queue.shutdown`.
   */
  def clusterEventsWith(queue: Queue[ClusterDomainEvent]): ZIO[ActorSystem, Throwable, Unit] =
    for {
      rts         <- Task.runtime[Any]
      actorSystem <- ZIO.environment[ActorSystem]
      _           <- Task(actorSystem.actorOf(Props(new SubscriberActor(rts, queue))))
    } yield ()

  private[cluster] class SubscriberActor(rts: Runtime[Any], queue: Queue[ClusterDomainEvent]) extends Actor {

    akka.cluster.Cluster(context.system).subscribe(self, classOf[ClusterDomainEvent])

    def receive: PartialFunction[Any, Unit] = {
      case ev: ClusterDomainEvent =>
        rts.unsafeRunAsync(queue.offer(ev)) {
          case Success(_)     => ()
          case Failure(cause) => if (cause.interrupted) self ! PoisonPill // stop listening if the queue was shut down
        }
      case _ =>
    }
  }

}
