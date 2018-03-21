package com.grandcloud.mammut

import com.typesafe.scalalogging.StrictLogging
import com.grandcloud.mammut.protobuf._

import com.google.protobuf.empty.Empty

import java.util.concurrent.atomic.AtomicReference
import io.grpc.stub.{ ServerCallStreamObserver, StreamObserver }
import io.grpc.Status

import monix.execution.{ Ack, Scheduler }
import monix.reactive.Observer
import monix.reactive.observers.Subscriber

import scala.collection.mutable
import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success, Try }

class ApiService(storage: Storage)(implicit c: okhttp3.OkHttpClient, s: Scheduler) extends MammutGrpc.Mammut with StrictLogging {

  val subs = mutable.Set.empty[(String, Subscriber.Sync[Post])]

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
      val builder = Seq.newBuilder[(String, Subscriber.Sync[Post])]
      subs.synchronized {
        subs.foreach { sub =>
          val (user, sync) = sub
          if (storage.isfollowing(user, post.name)){
            val next = Try(sync.onNext(post)).map { ack =>
              ack match {
                case Ack.Continue => // annoying.
                case Ack.Stop => builder += sub
              }
            }
            next.failed.foreach { ex =>
              logger.error(s"[createPost/${user}] failed to push message", ex)
              sync.onError(ex)
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
    val sub = new ApiService.ServerStreamSubscriber(obs)
    ApiService.syncSetObservable(subs, user.name).subscribe(sub)
  }
}

object ApiService {
  import monix.reactive.{ Observable, OverflowStrategy }
  import monix.execution.Cancelable
  def syncSetObservable[A](all: mutable.Set[(String, Subscriber.Sync[A])], name: String): Observable[A] = {
    Observable.create[A](OverflowStrategy.Fail(10)) { sync =>
      val pair = (name, sync)
      all.synchronized { all += pair }
      Cancelable(() => all.synchronized { all -= pair })
    }
  }

  class ServerStreamSubscriber[A](obs: ServerCallStreamObserver[A]) extends Observer[A] {

    val ack = new AtomicReference[Promise[Ack]]

    obs.setOnReadyHandler(new Runnable {
      def run = {
        val p = ack.getAndSet(null)
        if (p != null) p.success(Ack.Continue)
      }
    })

    obs.setOnCancelHandler(new Runnable {
      def run = {
        val p = ack.getAndSet(null)
        if (p != null) p.success(Ack.Stop)
      }
    })

    def onComplete(): Unit = obs.onCompleted
    def onError(ex: Throwable): Unit = obs.onError(ex)

    // This is where the magic happens
    // 1) we make an assumption that the _first_ time we get called the
    //    downstream is ready for us. This to avoid having to think about where
    //    to schedule the call to downstream onNext.
    // 2) If we are cancelled we drop the element on the floor. Too bad.
    // 3) We call downstream onNext, carefull trapping exceptions. If the
    //    consumer is still ready we ack immediately, if not we wait for the
    //    on ready handler to complete the ack.
    // 4) The onCancelHandler has a fun role here: If we get paused and our
    //    downstream cancels in the meantime we'd like to not ack continue, but
    //    stop. We accomplish this by allowing both handlers to complete the ack.
    def onNext(elem: A): Future[Ack] = {
      if (obs.isCancelled) {
        Ack.Stop
      } else {
        Try(obs.onNext(elem)).fold({ ex => obs.onError(ex); Ack.Stop }, { _ =>
          if (obs.isReady) {
            Ack.Continue
          } else {
            val p = Promise[Ack]()
            ack.set(p)
            p.future
          }
        })
      }
    }
  }
}
