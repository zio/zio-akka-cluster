package zio.akka.cluster.pubsub

import akka.actor.ActorSystem
import com.typesafe.config.{ Config, ConfigFactory }
import zio.{ Managed, Task }
import zio.akka.cluster.pubsub.PubSubSpecUtil._
import zio.test.Assertion._
import zio.test._

object PubSubSpec
  extends DefaultRunnableSpec(
    suite("PubSubSpec")(
      testM("send and receive a single message") {
        assertM(
          pubSub.use(
            pubSub =>
              for {
                queue <- pubSub.listen(topic)
                _     <- pubSub.publish(topic, msg)
                item  <- queue.take
              } yield item
          ), equalTo(msg)
        )
      },
      testM("support multiple subscribers") {
        assertM(
          pubSub.use(
            pubSub =>
              for {
                queue1 <- pubSub.listen(topic)
                queue2 <- pubSub.listen(topic)
                _      <- pubSub.publish(topic, msg)
                item1  <- queue1.take
                item2  <- queue2.take
              } yield (item1, item2)
          ), equalTo((msg, msg))
        )
      },
      testM("support multiple publishers") {
        val msg2  = "what's up"
        assertM(
          pubSub.use(
            pubSub =>
              for {
                queue <- pubSub.listen(topic)
                _     <- pubSub.publish(topic, msg)
                _     <- pubSub.publish(topic, msg2)
                item1 <- queue.take
                item2 <- queue.take
              } yield (item1, item2)
          ), equalTo((msg, msg2))
        )
      },
      testM("send only one message to a single group") {
        val group = "group"
        assertM(
          pubSub.use(
            pubSub =>
              for {
                queue1 <- pubSub.listen(topic, Some(group))
                queue2 <- pubSub.listen(topic, Some(group))
                _      <- pubSub.publish(topic, msg, sendOneMessageToEachGroup = true)
                item   <- queue1.take race queue2.take
                sizes  <- queue1.size zip queue2.size
              } yield (item, sizes)
          ), equalTo((msg, (0, 0)))
        )
      },
      testM("send one message to each group") {
        val group1 = "group1"
        val group2 = "group2"
        assertM(
          pubSub.use(
            pubSub =>
              for {
                queue1 <- pubSub.listen(topic, Some(group1))
                queue2 <- pubSub.listen(topic, Some(group2))
                _      <- pubSub.publish(topic, msg, sendOneMessageToEachGroup = true)
                item1  <- queue1.take
                item2  <- queue2.take
              } yield List(item1, item2)
          ), equalTo(List(msg, msg))
        )
      }
    ),
    List(TestAspect.executionStrategy(ExecutionStrategy.Sequential))
  )

object PubSubSpecUtil {

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

  val pubSub: Managed[Throwable, PubSub[String]] = actorSystem.mapM(PubSub.createPubSub[String].provide)

  val topic = "topic"
  val msg   = "yo"
}
