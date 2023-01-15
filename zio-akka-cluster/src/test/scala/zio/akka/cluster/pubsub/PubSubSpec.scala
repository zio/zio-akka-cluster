package zio.akka.cluster.pubsub

import akka.actor.ActorSystem
import com.typesafe.config.{ Config, ConfigFactory }
import zio.test.Assertion._
import zio.test._
import zio.test.TestEnvironment
import zio.test.ZIOSpecDefault
import zio.{ ExecutionStrategy, ZIO, ZLayer }

object PubSubSpec extends ZIOSpecDefault {

  val config: Config = ConfigFactory.parseString(s"""
                                                    |akka {
                                                    |  actor {
                                                    |    provider = "cluster"
                                                    |  }
                                                    |  remote {
                                                    |    enabled-transports = ["akka.remote.artery.canonical"]
                                                    |    artery.canonical {
                                                    |      hostname = "127.0.0.1"
                                                    |      port = 2553
                                                    |    }
                                                    |  }
                                                    |  cluster {
                                                    |    seed-nodes = ["akka://Test@127.0.0.1:2553"]
                                                    |    downing-provider-class = "akka.cluster.sbr.SplitBrainResolverProvider"
                                                    |  }
                                                    |}
           """.stripMargin)

  val actorSystem: ZLayer[Any, Throwable, ActorSystem] =
    ZLayer
      .scoped(
        ZIO.acquireRelease(ZIO.attempt(ActorSystem("Test", config)))(sys => ZIO.fromFuture(_ => sys.terminate()).either)
      )

  val topic = "topic"
  val msg   = "yo"

  def spec: Spec[TestEnvironment, Any] =
    suite("PubSubSpec")(
      test("send and receive a single message") {
        assertZIO(
          for {
            pubSub <- PubSub.createPubSub[String]
            queue  <- pubSub.listen(topic)
            _      <- pubSub.publish(topic, msg)
            item   <- queue.take
          } yield item
        )(equalTo(msg)).provideLayer(actorSystem)
      },
      test("support multiple subscribers") {
        assertZIO(
          for {
            pubSub <- PubSub.createPubSub[String]
            queue1 <- pubSub.listen(topic)
            queue2 <- pubSub.listen(topic)
            _      <- pubSub.publish(topic, msg)
            item1  <- queue1.take
            item2  <- queue2.take
          } yield (item1, item2)
        )(equalTo((msg, msg))).provideLayer(actorSystem)
      },
      test("support multiple publishers") {
        val msg2 = "what's up"
        assertZIO(
          for {
            pubSub <- PubSub.createPubSub[String]
            queue  <- pubSub.listen(topic)
            _      <- pubSub.publish(topic, msg)
            _      <- pubSub.publish(topic, msg2)
            item1  <- queue.take
            item2  <- queue.take
          } yield (item1, item2)
        )(equalTo((msg, msg2))).provideLayer(actorSystem)
      },
      test("send only one message to a single group") {
        val group = "group"
        assertZIO(
          for {
            pubSub <- PubSub.createPubSub[String]
            queue1 <- pubSub.listen(topic, Some(group))
            queue2 <- pubSub.listen(topic, Some(group))
            _      <- pubSub.publish(topic, msg, sendOneMessageToEachGroup = true)
            item   <- queue1.take race queue2.take
            sizes  <- queue1.size zip queue2.size
          } yield (item, sizes)
        )(equalTo((msg, (0, 0)))).provideLayer(actorSystem)
      },
      test("send one message to each group") {
        val group1 = "group1"
        val group2 = "group2"
        assertZIO(
          for {
            pubSub <- PubSub.createPubSub[String]
            queue1 <- pubSub.listen(topic, Some(group1))
            queue2 <- pubSub.listen(topic, Some(group2))
            _      <- pubSub.publish(topic, msg, sendOneMessageToEachGroup = true)
            item1  <- queue1.take
            item2  <- queue2.take
          } yield List(item1, item2)
        )(equalTo(List(msg, msg))).provideLayer(actorSystem)
      }
    ) @@ TestAspect.executionStrategy(ExecutionStrategy.Sequential)
}
