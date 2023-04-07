---
id: index
title: "Introduction to ZIO Akka Cluster"
sidebar_label: "ZIO Akka Cluster"
---

The [ZIO Akka Cluster](https://github.com/zio/zio-akka-cluster) library is a ZIO wrapper on [Akka Cluster](https://doc.akka.io/docs/akka/current/index-cluster.html). We can use clustering features of the Akka toolkit without the need to use the actor model.

@PROJECT_BADGES@

## Introduction

This library provides us following features:

- **Akka Cluster** — This feature contains two Akka Cluster Membership operations called `join` and `leave` and also it has some methods to retrieve _Cluster State_ and _Cluster Events_.

- **Akka Distributed PubSub** — Akka has a _Distributed Publish Subscribe_ facility in the cluster. It helps us to send a message to all actors in the cluster that have registered and subscribed for a specific topic name without knowing their physical address or without knowing which node they are running on.

- **Akka Cluster Sharding** — Cluster sharding is useful when we need to _distribute actors across several nodes in the cluster_ and want to be able to interact with them using their logical identifier without having to care about their physical location in the cluster, which might also change over time. When we have many stateful entities in our application that together they consume more resources (e.g. memory) than fit on one machine, it is useful to use _Akka Cluster Sharding_ to distribute our entities to multiple nodes.

## Installation

In order to use this library, we need to add the following line in our `build.sbt` file:

```scala
libraryDependencies += "dev.zio" %% "zio-akka-cluster" % "@VERSION@"
```

## Example

In the following example, we are using all these three features. We have a distributed counter application that lives in the Akka Cluster using _Akka Cluster Sharding_ feature. So the location of `LiveUsers` and `TotalRequests` entities in the cluster is transparent for us. We send the result of each entity to the _Distributed PubSub_. So every node in the cluster can subscribe and listen to those results. Also, we have created a fiber that is subscribed to the cluster events. All the new events will be logged to the console:

```scala mdoc:compile-only
import akka.actor.ActorSystem
import com.typesafe.config.{ Config, ConfigFactory }
import zio._
import zio.akka.cluster.Cluster
import zio.akka.cluster.sharding.{ Entity, Sharding }

sealed trait Counter extends Product with Serializable
case object Inc      extends Counter
case object Dec      extends Counter

case class CounterApp(port: String) {
  val config: Config =
    ConfigFactory.parseString(s"""
                                 |akka {
                                 |  actor {
                                 |    provider = "cluster"
                                 |  }
                                 |  remote {
                                 |    netty.tcp {
                                 |      hostname = "127.0.0.1"
                                 |      port = $port
                                 |    }
                                 |  }
                                 |  cluster {
                                 |    seed-nodes = ["akka.tcp://CounterApp@127.0.0.1:2551"]
                                 |  }
                                 |}
                                 |""".stripMargin)

  val actorSystem: ZLayer[Any, Nothing, ActorSystem] =
    ZLayer.scoped(
      ZIO.acquireRelease(ZIO.succeed(ActorSystem("CounterApp", config)))(sys =>
        ZIO.fromFuture(_ => sys.terminate()).either
      )
    )

  val counterApp: ZIO[Scope, Throwable, Unit] =
    (for {
      queue              <- Cluster.clusterEvents(true)
      pubsub             <- zio.akka.cluster.pubsub.PubSub.createPubSub[Int]
      liveUsersLogger    <- pubsub
        .listen("LiveUsers")
        .flatMap(
          _.take.tap(u => Console.printLine(s"Number of live users: $u")).forever
        )
        .fork
      totalRequestLogger <- pubsub
        .listen("TotalRequests")
        .flatMap(
          _.take.tap(r => Console.printLine(s"Total request until now: $r")).forever
        )
        .fork

      clusterEvents      <- queue.take
        .tap(x => Console.printLine("New event in cluster: " + x.toString))
        .forever
        .fork

      counterEntityLogic  = (c: Counter) =>
        for {
          entity   <- ZIO.environment[Entity[Int]]
          newState <- c match {
            case Inc =>
              entity.get.state.updateAndGet(s => Some(s.getOrElse(0) + 1))
            case Dec =>
              entity.get.state.updateAndGet(s => Some(s.getOrElse(0) - 1))
          }
          _        <- pubsub.publish(entity.get.id, newState.getOrElse(0)).orDie
        } yield ()
      cluster            <- Sharding.start("CounterEntity", counterEntityLogic)

      _ <- cluster.send("LiveUsers", Inc)
      _ <- cluster.send("TotalRequests", Inc)
      _ <- cluster.send("LiveUsers", Dec)
      _ <- cluster.send("LiveUsers", Inc)
      _ <- cluster.send("LiveUsers", Inc)
      _ <- cluster.send("TotalRequests", Inc)
      _ <- cluster.send("TotalRequests", Inc)

      _ <- clusterEvents.join zipPar liveUsersLogger.join zipPar totalRequestLogger.join
    } yield ()).provide(actorSystem)
}
```

Now, let's create a cluster comprising two nodes:

```scala
object CounterApp1 extends ZIOAppDefault {
  override def run = CounterApp("2551").counterApp
}

object CounterApp2 extends ZIOAppDefault {
  override def run = CounterApp("2552").counterApp
}
```

