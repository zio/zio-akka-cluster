package zio.akka.cluster.sharding

import scala.concurrent.duration.Duration

case class SetTimeout(duration: Duration)
