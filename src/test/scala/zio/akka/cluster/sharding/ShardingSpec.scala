package zio.akka.cluster.sharding

import scala.language.postfixOps
import akka.actor.ActorSystem
import com.typesafe.config.{ Config, ConfigFactory }
import zio.clock.Clock
import zio.duration._
import zio.test.Assertion._
import zio.test._
import zio.test.environment.TestEnvironment
import zio.{ Has, Managed, Promise, Task, ZIO, ZLayer }

object ShardingSpec extends DefaultRunnableSpec {

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

  val actorSystem: ZLayer[Any, Throwable, Has[ActorSystem]] =
    ZLayer.fromManaged(
      Managed.make(Task(ActorSystem("Test", config)))(sys => Task.fromFuture(_ => sys.terminate()).either)
    )

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

  val actorSystem2: ZLayer[Any, Throwable, Has[ActorSystem]] =
    ZLayer.fromManaged(
      Managed.make(Task(ActorSystem("Test", config2)))(sys => Task.fromFuture(_ => sys.terminate()).either)
    )

  val shardId   = "shard"
  val shardName = "name"
  val msg       = "yo"

  def spec: ZSpec[TestEnvironment, Any] =
    suite("ShardingSpec")(
      testM("send and receive a single message") {
        assertM(
          for {
            p         <- Promise.make[Nothing, String]
            onMessage = (msg: String) => p.succeed(msg).unit
            sharding  <- Sharding.start(shardName, onMessage)
            _         <- sharding.send(shardId, msg)
            res       <- p.await
          } yield res
        )(equalTo(msg)).provideLayer(actorSystem)
      },
      testM("send and receive a message using ask") {
        val onMessage: String => ZIO[Entity[Any], Nothing, Unit] =
          incomingMsg => ZIO.accessM[Entity[Any]](r => r.replyToSender(incomingMsg).orDie)
        assertM(
          for {
            sharding <- Sharding.start(shardName, onMessage)
            reply    <- sharding.ask[String](shardId, msg)
          } yield reply
        )(equalTo(msg)).provideLayer(actorSystem)
      },
      testM("gather state") {
        assertM(
          for {
            p <- Promise.make[Nothing, Boolean]
            onMessage = (_: String) =>
              for {
                state <- ZIO.access[Entity[Int]](_.state)
                newState <- state.updateAndGet {
                             case None    => Some(1)
                             case Some(x) => Some(x + 1)
                           }
                _ <- ZIO.when(newState.contains(3))(p.succeed(true)) // complete the promise after the 3rd message
              } yield ()
            sharding  <- Sharding.start(shardName, onMessage)
            _         <- sharding.send(shardId, msg)
            _         <- sharding.send(shardId, msg)
            earlyPoll <- p.poll
            _         <- sharding.send(shardId, msg)
            res       <- p.await
          } yield (earlyPoll, res)
        )(equalTo((None, true))).provideLayer(actorSystem)
      },
      testM("kill itself") {
        assertM(
          for {
            p <- Promise.make[Nothing, Option[Unit]]
            onMessage = (msg: String) =>
              msg match {
                case "set" => ZIO.accessM[Entity[Unit]](_.state.set(Some(())))
                case "get" => ZIO.accessM[Entity[Unit]](_.state.get.flatMap(s => p.succeed(s).unit))
                case "die" => ZIO.accessM[Entity[Unit]](_.stop)
              }
            sharding <- Sharding.start(shardName, onMessage)
            _        <- sharding.send(shardId, "set")
            _        <- sharding.send(shardId, "die")
            _ <- ZIO
                  .sleep(3 seconds)
                  .provideLayer(
                    Clock.live
                  ) // give time to the ShardCoordinator to notice the death of the actor and recreate one
            _   <- sharding.send(shardId, "get")
            res <- p.await
          } yield res
        )(isNone).provideLayer(actorSystem)
      },
      testM("passivate") {
        assertM(
          for {
            p <- Promise.make[Nothing, Option[Unit]]
            onMessage = (msg: String) =>
              msg match {
                case "set" => ZIO.accessM[Entity[Unit]](_.state.set(Some(())))
                case "get" => ZIO.accessM[Entity[Unit]](_.state.get.flatMap(s => p.succeed(s).unit))
              }
            sharding <- Sharding.start(shardName, onMessage)
            _        <- sharding.send(shardId, "set")
            _        <- sharding.passivate(shardId)
            _ <- ZIO
                  .sleep(3 seconds)
                  .provideLayer(
                    Clock.live
                  ) // give time to the ShardCoordinator to notice the death of the actor and recreate one
            _   <- sharding.send(shardId, "get")
            res <- p.await
          } yield res
        )(isNone).provideLayer(actorSystem)
      },
      testM("work with 2 actor systems") {
        assertM(
          actorSystem.build.use(a1 =>
            actorSystem2.build.use(a2 =>
              for {
                p1         <- Promise.make[Nothing, Unit]
                p2         <- Promise.make[Nothing, Unit]
                onMessage1 = (_: String) => p1.succeed(()).unit
                onMessage2 = (_: String) => p2.succeed(()).unit
                sharding1  <- Sharding.start(shardName, onMessage1).provideLayer(ZLayer.succeedMany(a1))
                _          <- Sharding.start(shardName, onMessage2).provideLayer(ZLayer.succeedMany(a2))
                _          <- sharding1.send("1", "hi")
                _          <- sharding1.send("2", "hi")
                _          <- p1.await
                _          <- p2.await
              } yield ()
            )
          )
        )(isUnit)
      }
    )

  override def aspects: List[TestAspect[Nothing, TestEnvironment, Nothing, Any]] =
    List(TestAspect.executionStrategy(ExecutionStrategy.Sequential))
}
