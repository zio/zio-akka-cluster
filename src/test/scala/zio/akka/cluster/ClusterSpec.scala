package zio.akka.cluster

import akka.actor.ActorSystem
import akka.cluster.ClusterEvent.MemberLeft
import com.typesafe.config.{ Config, ConfigFactory }
import zio.{ Managed, Task }
import zio.test._
import zio.test.Assertion._

object ClusterSpec
    extends DefaultRunnableSpec(
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
            actorSystem.use(
              actorSystem =>
                (for {
                  queue <- Cluster.clusterEvents()
                  _     <- Cluster.leave
                  item  <- queue.take
                } yield item).provide(actorSystem)
            ), isSubtype[MemberLeft](anything)
          )
        }
      )
    )
