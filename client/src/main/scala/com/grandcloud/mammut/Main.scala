package com.grandcloud.mammut

import com.google.protobuf.ByteString

import io.grpc.ManagedChannelBuilder
import io.grpc.stub.StreamObserver

import java.security.KeyPair
import java.security.PublicKey
import java.security.Security
import java.security.Signature

import org.bouncycastle.jce.provider.BouncyCastleProvider

import scala.collection.mutable
import scala.concurrent.{ Await, Future, Promise }
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global
import scala.io.{ AnsiColor => AC, StdIn }
import scala.util.Try

case class Credentials(name: String, pair: KeyPair) {
  def sign(msg: String): ByteString = {
    val signature = Signature.getInstance("SHA256withECDSA")
    signature.initSign(pair.getPrivate)
    signature.update(msg.`utf-8`)
    ByteString.copyFrom(signature.sign)
  }
}

object Main extends App {
  val channel = ManagedChannelBuilder.forAddress("localhost", 9990).usePlaintext(true).build
  val stub =  MammutGrpc.stub(channel)
  val map = mutable.Map.empty[String, PublicKey]

  val provider = new BouncyCastleProvider()
  Security.addProvider(provider)

  def register(name: String): Future[Credentials] = {
    val pair = Crypto.freshKeys
    val public = pair.getPublic
    val request = RegisterNameRequest(name, ByteString.copyFrom(public.getEncoded))
    stub.registerName(request).map(_ => Credentials(name, pair))
  }

  def send(creds: Credentials, msg: String): Future[Unit] = {
    Future.fromTry(Try(creds.sign(msg)))
      .flatMap { signed => stub.sendMessage(SendMessageRequest(creds.name, msg, signed)) }
      .map(_ => ())
  }

  def keyFor(name: String): Future[PublicKey] = {
    stub.getPublicKey(PublicKeyRequest(name)).map { rep =>
      Crypto.decodePublicKey(rep.publicKey.toByteArray)
    }
  }

  def follow(name: String): Future[Unit] = {
    val request = FollowRequest(name)
    keyFor(name).flatMap { key =>
      val promise = Promise[Unit]
      val observer = new StreamObserver[FollowResponse] {
        def onCompleted(): Unit = promise.success(())
        def onError(ex: Throwable): Unit = promise.failure(ex)
        def onNext(response: FollowResponse): Unit = {
          if (!Crypto.verify(key, response.msg.`utf-8`, response.signature.toByteArray))
            throw new Exception(s"$name did not sign ${response.msg}")
          println(response.msg)
        }
      }
      stub.follow(request, observer)
      promise.future
    }
  }

  def loop(creds: Option[Credentials]): Future[Unit] = {
    print(s"${AC.BOLD}mammut>${AC.RESET} ")
    val line = StdIn.readLine()
    line match {
      case _ if line.startsWith("register ") =>
        Future.successful(println("registering...."))
          .flatMap(_ => register(line.replaceFirst("^register\\s+", "")))
          .flatMap(c => loop(Some(c)))
      case _ if line.startsWith("post ") =>
        Future(println("post...."))
          .flatMap(_ => creds.map(c => send(c, line.replaceFirst("^post\\s+", ""))).getOrElse(Future.successful(())))
          .flatMap(_ => loop(creds))
      case _ if line.startsWith("follow ") =>
        Future(println("following...."))
          .flatMap(_ => follow(line.replaceFirst("^follow\\s+", "")))
          .flatMap(_ => loop(creds))
      case "quit" => Future.successful(println("quit"))
    }
  }

  Await.ready(loop(None), Duration.Inf)
  
}
