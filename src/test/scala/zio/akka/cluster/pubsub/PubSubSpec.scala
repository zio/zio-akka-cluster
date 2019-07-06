package zio.akka.cluster.pubsub

import akka.actor.ActorSystem
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.{ FlatSpec, Matchers }
import zio.{ DefaultRuntime, Managed, Task }

class PubSubSpec extends FlatSpec with Matchers with DefaultRuntime {

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

  "PubSubSpec" should "send and receive a single message" in {
    val topic = "topic"
    val msg   = "yo"
    unsafeRun(
      pubSub.use(
        pubSub =>
          for {
            queue <- pubSub.listen(topic)
            _     <- pubSub.publish(topic, msg)
            item  <- queue.take
          } yield item
      )
    ) shouldBe msg
  }

  it should "support multiple subscribers" in {
    val topic = "topic"
    val msg   = "yo"
    unsafeRun(
      pubSub.use(
        pubSub =>
          for {
            queue1 <- pubSub.listen(topic)
            queue2 <- pubSub.listen(topic)
            _      <- pubSub.publish(topic, msg)
            item1  <- queue1.take
            item2  <- queue2.take
          } yield (item1, item2)
      )
    ) shouldBe ((msg, msg))
  }

  it should "support multiple publishers" in {
    val topic = "topic"
    val msg1  = "yo"
    val msg2  = "what's up"
    unsafeRun(
      pubSub.use(
        pubSub =>
          for {
            queue <- pubSub.listen(topic)
            _     <- pubSub.publish(topic, msg1)
            _     <- pubSub.publish(topic, msg2)
            item1 <- queue.take
            item2 <- queue.take
          } yield (item1, item2)
      )
    ) shouldBe ((msg1, msg2))
  }

  it should "send only one message to a single group" in {
    val topic = "topic"
    val msg   = "yo"
    val group = "group"
    unsafeRun(
      pubSub.use(
        pubSub =>
          for {
            queue1 <- pubSub.listen(topic, Some(group))
            queue2 <- pubSub.listen(topic, Some(group))
            _      <- pubSub.publish(topic, msg, sendOneMessageToEachGroup = true)
            item   <- queue1.take race queue2.take
            sizes  <- queue1.size zip queue2.size
          } yield (item, sizes)
      )
    ) shouldBe ((msg, (0, 0)))
  }

  it should "send one message to each group" in {
    val topic  = "topic"
    val msg    = "yo"
    val group1 = "group1"
    val group2 = "group2"
    unsafeRun(
      pubSub.use(
        pubSub =>
          for {
            queue1 <- pubSub.listen(topic, Some(group1))
            queue2 <- pubSub.listen(topic, Some(group2))
            _      <- pubSub.publish(topic, msg, sendOneMessageToEachGroup = true)
            item1  <- queue1.take
            item2  <- queue2.take
          } yield List(item1, item2)
      )
    ) shouldBe List(msg, msg)
  }
}
