package zio.akka.cluster

import akka.actor.ActorSystem
import akka.cluster.ClusterEvent.MemberLeft
import com.typesafe.config.{ Config, ConfigFactory }
import zio.stream.{ ZSink, ZStream }
import zio.test.Assertion._
import zio.test._
import zio.test.TestEnvironment
import zio.test.ZIOSpecDefault
import zio.{ Managed, Task, ZLayer }

object ClusterSpec extends ZIOSpecDefault {

  def spec: ZSpec[TestEnvironment, Any] =
    suite("ClusterSpec")(
      test("receive cluster events") {
        val config: Config = ConfigFactory.parseString(s"""
                                                          |akka {
                                                          |  actor {
                                                          |    provider = "cluster"
                                                          |  }
                                                          |  remote {
                                                          |    enabled-transports = ["akka.remote.artery.canonical"]
                                                          |    artery.canonical {
                                                          |      hostname = "127.0.0.1"
                                                          |      port = 2551
                                                          |    }
                                                          |  }
                                                          |  cluster {
                                                          |    seed-nodes = ["akka://Test@127.0.0.1:2551"]
                                                          |    downing-provider-class = "akka.cluster.sbr.SplitBrainResolverProvider"
                                                          |  }
                                                          |}
                  """.stripMargin)

        val actorSystem: Managed[Throwable, ActorSystem] =
          Managed.acquireReleaseWith(Task(ActorSystem("Test", config)))(sys =>
            Task.fromFuture(_ => sys.terminate()).either
          )

        assertM(
          for {
            queue <- Cluster.clusterEvents()
            _     <- Cluster.leave
            items <- ZStream
                       .fromQueue(queue)
                       .takeUntil {
                         case _: MemberLeft => true
                         case _             => false
                       }
                       .run(ZSink.collectAll)
          } yield items
        )(isNonEmpty).provideLayer(ZLayer.fromManaged(actorSystem))
      }
    )
}
