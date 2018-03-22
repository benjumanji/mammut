package com.grandcloud.mammut

import com.grandcloud.mammut.protobuf.MammutGrpc

import io.grpc.ManagedChannelBuilder

import java.security.Security

import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.bouncycastle.jce.provider.BouncyCastleProvider

import scala.concurrent.{ Await, Future, blocking }
import scala.concurrent.duration.Duration
import scala.io.StdIn

object Main extends App {
  val channel = ManagedChannelBuilder.forAddress("localhost", 9990).usePlaintext(true).build
  val stub =  MammutGrpc.stub(channel)

  val provider = new BouncyCastleProvider()
  Security.addProvider(provider)

  val console = System.console
  val stdin = Task.deferFutureAction { s => Future(blocking(StdIn.readLine()))(s) }
  val readpw = Task.deferFutureAction { s => Future(blocking(console.readPassword()))(s) }

  def loop(r: Resumable): Task[Unit] = {
    r match {
      case Prompt(f) => stdin.flatMap(f).flatMap(loop)
      case Password(f) =>
        for {
          chars <- readpw
          next <- f(chars)
          _ = zero(chars)
          rest <- loop(next)
        } yield rest
      case Stop => Task.now(println("Exiting.."))
    }
  }

  def zero(pw: Array[Char]): Unit = {
    val buf = java.nio.CharBuffer.allocate(pw.size)
    buf.limit(buf.capacity)
    buf.get(pw)
  }

  val cli = Cli(InitEnv(stub))
  Await.result(loop(cli).runAsync, Duration.Inf)
}
