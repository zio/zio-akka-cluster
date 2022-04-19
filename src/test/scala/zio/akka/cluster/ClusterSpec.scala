package zio.akka.cluster

import scala.language.postfixOps

import akka.actor.ActorSystem
import akka.cluster.ClusterEvent.MemberLeft
import com.typesafe.config.{ Config, ConfigFactory }
import zio.stream.ZStream
import zio.test.Assertion._
import zio.test._
import zio.test.TestEnvironment
import zio.test.ZIOSpecDefault
import zio._

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

        val actorSystem: ZIO[Scope, Throwable, ActorSystem] =
          ZIO.acquireRelease(Task.attempt(ActorSystem("Test", config)))(sys =>
            Task.fromFuture(_ => sys.terminate()).either
          )

        assertM(
          for {
            queue <- Cluster.clusterEvents()
            _     <- Clock.sleep(5 seconds)
            _     <- Cluster.leave
            items <- ZStream
                       .fromQueue(queue)
                       .takeUntil {
                         case _: MemberLeft => true
                         case _             => false
                       }
                       .runCollect
                       .timeoutFail(new Exception("Timeout"))(10 seconds)
          } yield items
        )(isNonEmpty).provideLayer(ZLayer.scoped(actorSystem))
      }
    )
}
