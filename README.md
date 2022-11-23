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

## Documentation

[ZIO Akka Cluster Home Page](https://zio.dev/zio-akka-cluster/)