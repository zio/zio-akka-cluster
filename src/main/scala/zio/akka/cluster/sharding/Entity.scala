package zio.akka.cluster.sharding

import zio.{ Ref, UIO }

trait Entity[State] {
  def state: Ref[Option[State]]
  def stop: UIO[Unit]
}
