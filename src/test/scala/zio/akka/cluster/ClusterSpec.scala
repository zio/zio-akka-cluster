package zio.akka.cluster

import akka.actor.ActorSystem
import akka.cluster.ClusterEvent.MemberLeft
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.{ FlatSpec, Matchers }
import zio.{ DefaultRuntime, Managed, Task }

class ClusterSpec extends FlatSpec with Matchers with DefaultRuntime {

  val config: Config = ConfigFactory.parseString(s"""
                                                    |akka {
                                                    |  actor {
                                                    |    provider = "cluster"
                                                    |  }
                                                    |  remote {
                                                    |    netty.tcp {
                                                    |      hostname = "127.0.0.1"
                                                    |      port = 2551
                                                    |    }
                                                    |  }
                                                    |  cluster {
                                                    |    seed-nodes = ["akka.tcp://Test@127.0.0.1:2551"]
                                                    |  }
                                                    |}
           """.stripMargin)

  val actorSystem: Managed[Throwable, ActorSystem] =
    Managed.make(Task(ActorSystem("Test", config)))(sys => Task.fromFuture(_ => sys.terminate()).either)

  "ClusterSpec" should "receive cluster events" in {
    unsafeRun(
      actorSystem.use(
        actorSystem =>
          (for {
            queue <- Cluster.clusterEvents
            _     <- Cluster.leave
            item  <- queue.take
          } yield item).provide(actorSystem)
      )
    ) shouldBe a[MemberLeft]
  }

}
