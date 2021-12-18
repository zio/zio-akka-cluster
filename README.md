# ZIO Wrapper for Akka Cluster

[![Project stage: Production Ready][project-stage-badge: Production Ready]][project-stage-page]
![CI](https://github.com/zio/zio-akka-cluster/workflows/CI/badge.svg)

[project-stage-badge: Production Ready]: https://img.shields.io/badge/Project%20Stage-Production%20Ready-brightgreen.svg
[project-stage-page]: https://github.com/zio/zio/wiki/Project-Stages

This library is a [ZIO](https://github.com/zio/zio) wrapper for [Akka Cluster](https://doc.akka.io/docs/akka/current/index-cluster.html).
It exposes a purely functional API allowing you to leverage the distributed features of Akka without the need to use the actor model.

The following features are available:
- Akka Cluster (join, leave, cluster state, cluster events)
- Akka Distributed PubSub
- Akka Cluster Sharding

## Add the dependency

To use `zio-akka-cluster`, add the following line in your `build.sbt` file:

```
libraryDependencies += "dev.zio" %% "zio-akka-cluster" % "0.2.0"
```

## How to use

In order to use the library, you need to provide an `ActorSystem`. Refer to the [Akka Documentation](https://doc.akka.io/docs/akka/current/general/actor-systems.html) if you need help.

### Akka Cluster

The features described here require the following import:
```scala
import zio.akka.cluster.Cluster
```

When you create an ActorSystem, Akka will look at your configuration file and join a cluster if seed nodes are specified.
See [Akka Documentation](https://doc.akka.io/docs/akka/current/cluster-usage.html) to know more about cluster usage.
You can also manually join a cluster using `Cluster.join`.

```scala
def join(seedNodes: List[Address]): ZIO[ActorSystem, Throwable, Unit]
```

It's possible to get the status of the cluster by calling `Cluster.clusterState`

```scala
val clusterState: ZIO[ActorSystem, Throwable, CurrentClusterState]
```

To monitor the cluster and be informed of changes (e.g. new members, member unreachable, etc), use `Cluster.clusterEvents`.
This functions returns a ZIO `Queue` that will be populated with the cluster events as they happen.
The returned queue is unbounded, but if you want to supply your own bounded queue, use `Cluster.clusterEventsWith`.
To unsubscribe, simply `shutdown` the queue.
`initialStateAsEvents` indicates if you want to receive previous cluster events leading to the current state, or only future events.

```scala
def clusterEvents(initialStateAsEvents: Boolean = false): ZIO[ActorSystem, Throwable, Queue[ClusterDomainEvent]]
```

Finally, you can leave the current cluster using `Cluster.leave`.

```scala
val leave: ZIO[ActorSystem, Throwable, Unit]
```

### Akka PubSub

The features described here require the following import:
```scala
import zio.akka.cluster.pubsub.PubSub
```

Akka Distributed PubSub lets you publish and receive events from any node in the cluster.
See [Akka Documentation](https://doc.akka.io/docs/akka/current/distributed-pub-sub.html) to know more about PubSub usage.
To create a `PubSub` object which can both publish and subscribe, use `PubSub.createPubSub`.

```scala
def createPubSub[A]: ZIO[ActorSystem, Throwable, PubSub[A]]
```

There are also less powerful variants `PubSub.createPublisher` if you only need to publish and `PubSub.createSubscriber` if you only need to subscribe.

To publish a message, use `publish`. It requires the following:
- the `topic` you want to publish to
- `data` is the message to publish.
- `sendOneMessageToEachGroup` can be used in order to send the message not to all subscribers but to only one subscriber per group.

```scala
def publish(topic: String, data: A, sendOneMessageToEachGroup: Boolean = false): Task[Unit]
```

To subscribe to messages, use `listen`.  It requires the following:
- the `topic` you want to subscribe to.
- a `group` name if you want only one subscriber per group to receive each message, to be used with `sendOneMessageToEachGroup=true`

`listen` returns an unbounded ZIO `Queue` that will be populated with the messages. To use a bounded queue, use `listenWith` instead.
Note that `listen` waits for the subscription acknowledgment before completing, which means that once it completes, all messages published will be received.
To stop listening, simply `shutdown` the queue.

```scala
def listen(topic: String, group: Option[String] = None): Task[Queue[A]] =
    Queue.unbounded[A].tap(listenWith(topic, _, group))
```

**Note on Serialization**
Akka messages are serialized when they are sent across the network. By default, Java serialization is used but it is not recommended to use it in production.
See [Akka Documentation](https://doc.akka.io/docs/akka/current/serialization.html) to see how to provide your own serializer.
This library wraps messages inside of a `zio.akka.cluster.pubsub.MessageEnvelope` case class, so your serializer needs to cover it as well.

**Example:**

```scala
import akka.actor.ActorSystem
import zio.{ Managed, Task, ZLayer }
import zio.akka.cluster.pubsub.PubSub

val actorSystem: ZLayer[Any, Throwable, ActorSystem] =
  ZLayer.fromManaged(Managed.acquireReleaseWith(Task(ActorSystem("Test")))(sys => Task.fromFuture(_ => sys.terminate()).either))

(for {
  pubSub   <- PubSub.createPubSub[String]
  queue    <- pubSub.listen("my-topic")
  _        <- pubSub.publish("my-topic", "yo")
  firstMsg <- queue.take
} yield firstMsg).provideLayer(actorSystem)
```

### Akka Cluster Sharding

The features described here require the following import:
```scala
import zio.akka.cluster.sharding.Sharding
```

Akka Cluster Sharding lets you distribute entities across a cluster and communicate with them using a logical ID, without having to care about their physical location.
It is particularly useful when you have some business logic that needs to be processed by a single process across a cluster (e.g. some state that should be only in one place at a given time, a single writer to a database, etc).
See [Akka Documentation](https://doc.akka.io/docs/akka/current/cluster-sharding.html) to know more about Cluster Sharding usage.

To start sharding a given entity type on a node, use `Sharding.start`. It returns a `Sharding` object which can be used to send messages, stop or passivate sharded entities.

```scala
def start[R, Msg, State](
    name: String,
    onMessage: Msg => ZIO[Entity[State] with R, Nothing, Unit],
    numberOfShards: Int = 100
  ): ZIO[ActorSystem with R, Throwable, Sharding[Msg]]
```

It requires:
- the `name` of the entity type. Entities will be distributed on all the nodes of the cluster where `start` was called with this `name`.
- `onMessage` is the behavior of the sharded entity. For each received message, it will run an effect of type `ZIO[Entity[State], Nothing, Unit]`:
    - `Entity[State]` gives you access to a `Ref[Option[State]]` which you can use to read or modify the state of the entity. The state is set to `None` when the entity is started. This `Entity` object also allows you to get the entity ID and to stop the entity from within (e.g. after some time of inactivity).
    - `Nothing` means the effect should not fail, you must catch and handle potential errors
    - `Unit` means the effect should not return anything
- `numberOfShards` indicates how entities will be split across nodes. See [this page](https://doc.akka.io/docs/akka/current/cluster-sharding.html#an-example) for more information.

You can also use `Sharding.startProxy` if you need to send messages to entities located on `other` nodes.

To send a message to a sharded entity without expecting a response, use `send`. To send a message to a sharded entity expecting a response, use `ask`. To stop one, use `stop`.
The `entityId` identifies the entity to target. Messages sent to the same `entityId` from different nodes in the cluster will be handled by the same actor.

```scala
def send(entityId: String, data: M): Task[Unit]
def ask[R](entityId: String, data: M): Task[R]
def stop(entityId: String): Task[Unit]
def passivate(entityId: String): Task[Unit]
```

**Note on Serialization**
Akka messages are serialized when they are sent across the network. By default, Java serialization is used, but it is not recommended in production.
See [Akka Documentation](https://doc.akka.io/docs/akka/current/serialization.html) to see how to provide your own serializer.
This library wraps messages inside of a `zio.akka.cluster.sharding.MessageEnvelope` case class, so your serializer needs to cover it as well.

**Example:**

```scala
import akka.actor.ActorSystem
import zio.akka.cluster.sharding.{ Entity, Sharding }
import zio.{ Managed, Task, ZIO, ZLayer }

val actorSystem: ZLayer[Any, Throwable, ActorSystem] =
  ZLayer.fromManaged(Managed.acquireReleaseWith(Task(ActorSystem("Test")))(sys => Task.fromFuture(_ => sys.terminate()).either))

val behavior: String => ZIO[Entity[Int], Nothing, Unit] = {
  case "+" => ZIO.service[Entity[Int]].flatMap(_.state.update(x => Some(x.getOrElse(0) + 1)))
  case "-" => ZIO.service[Entity[Int]].flatMap(_.state.update(x => Some(x.getOrElse(0) - 1)))
  case _   => ZIO.unit
}

(for {
  sharding <- Sharding.start("session", behavior)
  entityId = "1"
  _        <- sharding.send(entityId, "+")
  _        <- sharding.send(entityId, "+")
  _        <- sharding.send(entityId, "-")
} yield ()).provideLayer(actorSystem)
```
