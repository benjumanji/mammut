package com.grandcloud.mammut

import com.typesafe.scalalogging.StrictLogging
import com.grandcloud.mammut.protobuf._

import io.grpc.{
  Server,
  ServerBuilder
}

import monix.execution.{ Scheduler, UncaughtExceptionReporter }


object Main extends App with StrictLogging {
  implicit val client = new okhttp3.OkHttpClient()
  val cores = Runtime.getRuntime().availableProcessors()
  val min = 2 * cores
  val max = 2 * (cores + 1)

  implicit val scheduler =
    Scheduler.forkJoin(min, max, reporter = UncaughtExceptionReporter(logger.error("Top level exception", _)))

  val storage = new RocksDBStorage

  val service = new ApiService(storage)
  val deliverTx = new DeliverTx(storage)

  val server: Server =
    ServerBuilder.forPort(9990)
      .addService(MammutGrpc.bindService(service, scheduler))
      .build()
  server.start

  val tendermint = new MammutAbci(deliverTx, storage)
  val tServer: Server = 
    ServerBuilder.forPort(46658)
      .addService(types.ABCIApplicationGrpc.bindService(tendermint, scheduler))
      .build()
  tServer.start

  server.awaitTermination
}
