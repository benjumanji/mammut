package com.grandcloud.mammut

import com.typesafe.scalalogging.StrictLogging
import com.grandcloud.mammut.protobuf._

import com.google.protobuf.ByteString
import com.google.protobuf.empty.Empty

import io.grpc.stub.{ ServerCallStreamObserver, StreamObserver }
import io.grpc.{
  Server,
  ServerBuilder,
  Status
}

import java.util.concurrent.ConcurrentHashMap

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

class Service extends MammutGrpc.Mammut with StrictLogging {

  val keys = new ConcurrentHashMap[String, ByteString]
  val following = mutable.Set.empty[(String, String)]
  val subs = mutable.Set.empty[(String, ServerCallStreamObserver[Post])]

  private def missingField[A](field: String): A = {
    throw Status.INVALID_ARGUMENT.augmentDescription("follow is required").asRuntimeException
  }

  def createUser(request: CreateUserRequest): Future[Empty] = {
    val user = request.user.getOrElse(missingField("user"))
    val current = keys.putIfAbsent(user.name, user.publicKey)
    if (current == null)
      Future.successful(new Empty)
    else
      Future.failed {
        Status.ALREADY_EXISTS
          .augmentDescription(s"${user.name} already exists")
          .asException
      }
  }

  def getUser(request: GetUserRequest): Future[GetUserResponse] = {
    val user = request.user.getOrElse(missingField("user"))
    val name = user.name
    val ret = Option(keys.get(name)).map { key =>
      User(name, key)
    }
    Future.successful(GetUserResponse(ret))
  }

  def createFollow(request: CreateFollowRequest): Future[Empty] = {
    val follow = request.follow.getOrElse(missingField("follow"))
    following.synchronized { following.add((follow.follower, follow.followee)) }
    Future.successful(Empty())
  }

  def createPost(request: CreatePostRequest): Future[Empty] = {
    val post = request.post.getOrElse(missingField("post"))
    val builder = Seq.newBuilder[(String, ServerCallStreamObserver[Post])]
    subs.synchronized {
      subs.foreach { sub =>
        val (user, obs) = sub
        if (following.synchronized { following.contains((user, post.name))}){
          Try(obs.onNext(post)).failed.foreach { ex =>
            logger.error(s"[createPost/${user}] failed to push message", ex)
            obs.onError(ex)
            builder += sub
          }
        }
      }
      subs --= builder.result
    }
    Future.successful(Empty())
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
  val service = new Service
  val server: Server =
    ServerBuilder.forPort(9990)
      .addService(MammutGrpc.bindService(service, global))
      .build()
  server.start
  server.awaitTermination
}
