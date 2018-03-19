package com.grandcloud.mammut

import com.google.protobuf.ByteString
import com.google.protobuf.empty.Empty


import io.grpc.stub.{ ServerCallStreamObserver, StreamObserver }
import io.grpc.{
  Server,
  ServerBuilder,
  Status
}

import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class Service extends MammutGrpc.Mammut {

  val keys = new ConcurrentHashMap[String, ByteString]
  val messages = new ConcurrentHashMap[String, (Seq[StreamObserver[FollowResponse]], Seq[(String, ByteString)])]


  def follow(request: FollowRequest, observer: StreamObserver[FollowResponse]): Unit = {
    messages.compute(request.name, (k, v) => {
      Option(v).map { case (observers, messages) => 
          messages.take(10).foreach { case (msg, sig) => observer.onNext(FollowResponse(request.name, msg, sig)) }
          ((observers :+ observer), messages) 
        }
        .getOrElse((Seq(observer), Seq.empty))
    })
  }

  def getPublicKey(request: PublicKeyRequest): Future[PublicKeyResponse] = {
    val key = Option(keys.get(request.name))
    key.map(k => Future.successful(PublicKeyResponse(request.name, k)))
      .getOrElse(Future.failed(new Exception("no key")))
  }

  def registerName(request: RegisterNameRequest): Future[Empty] = {
    val current = keys.putIfAbsent(request.name, request.publicKey)
    if (current == null)
      Future.successful(new Empty)
    else
      Future.failed {
        Status.ALREADY_EXISTS
          .augmentDescription(s"${request.name} already exists")
          .asException
      }
  }

  def sendMessage(request: SendMessageRequest): Future[Empty] = {
    messages.compute(request.name, (k, v) => {
      Option(v).map { case (observers, messages) =>
        val alive = observers.filterNot(_.asInstanceOf[ServerCallStreamObserver[_]].isCancelled)
        alive.foreach { observer =>
          observer.onNext(FollowResponse(request.name, request.msg, request.signature))
        }
        (alive, messages :+ ((request.msg, request.signature)))
      }
      .getOrElse((Seq.empty, Seq((request.msg, request.signature))))
    })
    Future.successful(new Empty)
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
