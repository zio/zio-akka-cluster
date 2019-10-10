package zio.akka.cluster.sharding

import scala.language.postfixOps
import akka.actor.ActorSystem
import com.typesafe.config.{ Config, ConfigFactory }
import zio.clock.Clock
import zio.duration._
import zio.{ Managed, Promise, Task, ZIO }
import zio.akka.cluster.sharding.ShardingSpecUtil._
import zio.test.Assertion._
import zio.test._

object ShardingSpec
    extends DefaultRunnableSpec(
      suite("ShardingSpec")(
        testM("send and receive a single message") {
          assertM(
            actorSystem.use(
              sys =>
                for {
                  p         <- Promise.make[Nothing, String]
                  onMessage = (msg: String) => p.succeed(msg).unit
                  sharding  <- Sharding.start(shardName, onMessage).provide(sys)
                  _         <- sharding.send(shardId, msg)
                  res       <- p.await
                } yield res
            ),
            equalTo(msg)
          )
        },
        testM("gather state") {
          assertM(
            actorSystem.use {
              sys =>
                for {
                  p <- Promise.make[Nothing, Boolean]
                  onMessage = (_: String) =>
                    for {
                      state <- ZIO.access[Entity[Int]](_.state)
                      newState <- state.update {
                                   case None    => Some(1)
                                   case Some(x) => Some(x + 1)
                                 }
                      _ <- ZIO.when(newState.contains(3))(p.succeed(true)) // complete the promise after the 3rd message
                    } yield ()
                  sharding  <- Sharding.start(shardName, onMessage).provide(sys)
                  _         <- sharding.send(shardId, msg)
                  _         <- sharding.send(shardId, msg)
                  earlyPoll <- p.poll
                  _         <- sharding.send(shardId, msg)
                  res       <- p.await
                } yield (earlyPoll, res)
            },
            equalTo[(Option[zio.IO[Nothing, Boolean]], Boolean)]((None, true))
          )
        },
        testM("kill itself") {
          assertM(
            actorSystem.use {
              sys =>
                for {
                  p <- Promise.make[Nothing, Option[Unit]]
                  onMessage = (msg: String) =>
                    msg match {
                      case "set" => ZIO.accessM[Entity[Unit]](_.state.set(Some(())))
                      case "get" => ZIO.accessM[Entity[Unit]](_.state.get.flatMap(s => p.succeed(s).unit))
                      case "die" => ZIO.accessM[Entity[Unit]](_.stop)
                    }
                  sharding <- Sharding.start(shardName, onMessage).provide(sys)
                  _        <- sharding.send(shardId, "set")
                  _        <- sharding.send(shardId, "die")
                  _ <- ZIO
                        .sleep(3 seconds)
                        .provide(Clock.Live) // give time to the ShardCoordinator to notice the death of the actor and recreate one
                  _   <- sharding.send(shardId, "get")
                  res <- p.await
                } yield res
            },
            isNone
          )
        },
        testM("work with 2 actor systems") {
          assertM(
            (actorSystem zip actorSystem2).use {
              case (sys1, sys2) =>
                for {
                  p1         <- Promise.make[Nothing, Unit]
                  p2         <- Promise.make[Nothing, Unit]
                  onMessage1 = (_: String) => p1.succeed(()).unit
                  onMessage2 = (_: String) => p2.succeed(()).unit
                  sharding1  <- Sharding.start(shardName, onMessage1).provide(sys1)
                  _          <- Sharding.start(shardName, onMessage2).provide(sys2)
                  _          <- sharding1.send("1", "hi")
                  _          <- sharding1.send("2", "hi")
                  _          <- p1.await
                  _          <- p2.await
                } yield ()
            },
            isUnit
          )
        }
      ),
      List(TestAspect.executionStrategy(ExecutionStrategy.Sequential))
    )

object ShardingSpecUtil {
  import zio._

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
                                                    |    jmx.multi-mbeans-in-same-jvm = on
                                                    |  }
                                                    |}
      """.stripMargin)

  val actorSystem: Managed[Throwable, ActorSystem] =
    Managed.make(Task(ActorSystem("Test", config)))(sys => Task.fromFuture(_ => sys.terminate()).either)

  val config2: Config = ConfigFactory.parseString(s"""
                                                     |akka {
                                                     |  actor {
                                                     |    provider = "cluster"
                                                     |  }
                                                     |  remote {
                                                     |    netty.tcp {
                                                     |      hostname = "127.0.0.1"
                                                     |      port = 2552
                                                     |    }
                                                     |  }
                                                     |  cluster {
                                                     |    seed-nodes = ["akka.tcp://Test@127.0.0.1:2551"]
                                                     |    jmx.multi-mbeans-in-same-jvm = on
                                                     |  }
                                                     |}
      """.stripMargin)

  val actorSystem2: Managed[Throwable, ActorSystem] =
    Managed.make(Task(ActorSystem("Test", config2)))(sys => Task.fromFuture(_ => sys.terminate()).either)

  val shardId   = "shard"
  val shardName = "name"
  val msg       = "yo"
}
