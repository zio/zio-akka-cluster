package zio.akka.cluster.sharding

import zio.{ Ref, Task, UIO }

trait Entity[State] {
  def replyToSender[R](msg: R): Task[Unit]
  def id: String
  def state: Ref[Option[State]]
  def stop: UIO[Unit]
}
