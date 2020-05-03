package zio.akka.cluster.sharding

import scala.concurrent.duration.Duration
import zio.{Ref, Task, UIO}

trait Entity[State] {
  def replyToSender[R](msg: R): Task[Unit]
  def id: String
  def state: Ref[Option[State]]
  def stop: UIO[Unit]
  def passivate: UIO[Unit]
  def setTimeout(duration: Duration): UIO[Unit]
}
