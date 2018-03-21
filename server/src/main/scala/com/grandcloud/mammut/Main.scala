package com.grandcloud.mammut

import com.typesafe.scalalogging.StrictLogging
import com.grandcloud.mammut.protobuf._

import com.google.protobuf.empty.Empty

import io.grpc.stub.{ ServerCallStreamObserver, StreamObserver }
import io.grpc.{
  Server,
  ServerBuilder,
  Status
}

import monix.execution.Scheduler

import scala.collection.mutable
import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

class Service(storage: Storage)(implicit c: okhttp3.OkHttpClient, s: Scheduler) extends MammutGrpc.Mammut with StrictLogging {

  val subs = mutable.Set.empty[(String, ServerCallStreamObserver[Post])]

  private def missingField[A](field: String): A = {
    throw Status.INVALID_ARGUMENT.augmentDescription(s"$field is required").asRuntimeException
  }

  private def check(txResult: Transaction.TxResult): Try[Empty] = {
    txResult.code.map { code =>
      val reason = txResult.info.getOrElse("Unknown")
      Failure(Status.fromCodeValue(code).withDescription(reason).asException)
    }.getOrElse(Success(Empty()))
  }

  private def checkRep(rep: Transaction.Response): Try[Empty] = {
    for {
      checkTx <- check(rep.checkTx)
      deliverTx <- check(rep.deliverTx)
    } yield deliverTx
  }

  private def broadcast(event: Event.Event): Future[Empty] = 
    Transaction.broadcast(Event(event).toByteArray)
      .materialize
      .map(_.flatMap(checkRep))
      .dematerialize
      .runAsync

  def createUser(request: CreateUserRequest): Future[Empty] = {
    val user = request.user.getOrElse(missingField("user"))
    broadcast(Event.Event.User(user))
  }

  def getUser(request: GetUserRequest): Future[GetUserResponse] = {
    val user = request.user.getOrElse(missingField("user"))
    Future.successful(GetUserResponse(storage.queryUser(user.name)))
  }

  def createFollow(request: CreateFollowRequest): Future[Empty] = {
    val follow = request.follow.getOrElse(missingField("follow"))
    broadcast(Event.Event.Follow(follow))
  }

  def createPost(request: CreatePostRequest): Future[Empty] = {
    val post = request.post.getOrElse(missingField("post"))
    val ret = broadcast(Event.Event.Post(post))
    ret.foreach { _ =>
      val builder = Seq.newBuilder[(String, ServerCallStreamObserver[Post])]
      subs.synchronized {
        subs.foreach { sub =>
          val (user, obs) = sub
          if (storage.isfollowing(user, post.name)){
            Try(obs.onNext(post)).failed.foreach { ex =>
              logger.error(s"[createPost/${user}] failed to push message", ex)
              obs.onError(ex)
              builder += sub
            }
          }
        }
        subs --= builder.result
      }
    }
    ret
  }

  def streamPosts(request: StreamPostsRequest, observer: StreamObserver[Post]): Unit = {
    val user = request.user.getOrElse(missingField("user"))
    val obs = observer.asInstanceOf[ServerCallStreamObserver[Post]]
    val pair = (user.name, obs)
    obs.setOnCancelHandler(new Runnable { def run = subs.synchronized { subs -= pair } })
    subs += pair
  }
}

object Main extends App {
  implicit val client = new okhttp3.OkHttpClient()
  implicit val scheduler = Scheduler.global
  val storage = new InMemoryStorage

  val service = new Service(storage)
  val deliverTx = new DeliverTx(storage)

  val server: Server =
    ServerBuilder.forPort(9990)
      .addService(MammutGrpc.bindService(service, scheduler))
      .build()
  server.start

  val tendermint = new MammutAbci(deliverTx)
  val tServer: Server = 
    ServerBuilder.forPort(46658)
      .addService(types.ABCIApplicationGrpc.bindService(tendermint, scheduler))
      .build()
  tServer.start

  server.awaitTermination
}
