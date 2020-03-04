package zio.akka.cluster

import akka.actor.ActorSystem
import akka.cluster.ClusterEvent.MemberLeft
import com.typesafe.config.{ Config, ConfigFactory }
import zio.test.Assertion._
import zio.test._
import zio.test.environment.TestEnvironment
import zio.{ Managed, Task, ZLayer }

object ClusterSpec extends DefaultRunnableSpec {

  def spec: ZSpec[TestEnvironment, Any] =
    suite("ClusterSpec")(
      testM("receive cluster events") {
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

        assertM(
          for {
            queue <- Cluster.clusterEvents()
            _     <- Cluster.leave
            item  <- queue.take
          } yield item
        )(isSubtype[MemberLeft](anything)).provideLayer(ZLayer.fromManaged(actorSystem))
      }
    )
}
