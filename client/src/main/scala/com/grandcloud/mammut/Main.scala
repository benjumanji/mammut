package com.grandcloud.mammut

import com.google.protobuf.ByteString

import io.grpc.ManagedChannelBuilder

import java.security.KeyPair
import java.security.Security
import java.security.Signature

import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.bouncycastle.jce.provider.BouncyCastleProvider

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.io.StdIn

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

  val provider = new BouncyCastleProvider()
  Security.addProvider(provider)

  def loop(r: Resumable): Task[Unit] = {
    r match {
      case Prompt(f) => {
        val line = StdIn.readLine()
        f(line).flatMap(loop)
      }
      case Stop => Task.now(println("Exiting.."))
    }
  }

  val cli = Cli(InitEnv(stub))
  Await.result(loop(cli).runAsync, Duration.Inf)
}
