package com.grandcloud.mammut

import com.typesafe.scalalogging.StrictLogging
import com.google.protobuf.ByteString

import java.security.PublicKey

import io.grpc.{ Status, StatusRuntimeException }
import io.grpc.stub.{ ClientCallStreamObserver, ClientResponseObserver }

import monix.eval.Task
import monix.execution.Cancelable

import scala.io.{ AnsiColor => AC }
import scala.util.Try
import scala.util.control.{ NonFatal, NoStackTrace }

sealed trait Resumable
case class Prompt(f: String => Task[Resumable]) extends Resumable
case class Password(f: String => Task[Resumable]) extends Resumable
case object Stop extends Resumable

case object NoFollowException extends Exception("user cancelled") with NoStackTrace

object StatusCancelled {
  def unapply(t: StatusRuntimeException) = {
    if (t.getStatus.getCode == Status.Code.CANCELLED) Some(t.getTrailers)
    else None
  }
}

object Prompt {
  def defer(f: String => Task[Resumable]) = Task(Prompt(f))
}

case class InitEnv(stub: MammutGrpc.MammutStub) {
  def upgrade(creds: Credentials): AuthEnv = AuthEnv(stub, creds)
}

case class AuthEnv(stub: MammutGrpc.MammutStub, creds: Credentials)

object Cli extends StrictLogging {
  def apply(env: InitEnv): Resumable  = {
    println("[r]egister")
    Prompt { line =>
      line.replaceAll("\\s+", "") match {
        case "" => { unrecognised; Task(apply(env)) }
        case _ if "register".startsWith(line) => registerUser(env)
      }
    }
  }

  def registerUser(env: InitEnv): Task[Resumable] = {
    println("Please supply a username (no spaces allowed / under 20 chars)")
    print(s"${AC.BOLD}[registering/username]>${AC.RESET} ")
    Prompt.defer { name =>
      name match {
        case _ if (name.size > 20) => { println("Username too long"); registerUser(env) }
        case _ if (name.exists(_.isSpaceChar)) => { println("User name contains spaces"); registerUser(env) }
        case _ if (name.exists(_ == 0.toChar)) => { println("User name contains null"); registerUser(env) }
        case _ => {
          val keys = Crypto.freshKeys
          val request = RegisterNameRequest(name, ByteString.copyFrom(keys.getPublic.getEncoded))
          for {
            _ <- env.stub.registerNameTask(request)
            step <- prompt(env.upgrade(Credentials(name, keys)))
          } yield step
        }
      }
    }
  }

  def prompt(env: AuthEnv): Task[Resumable] = {
    println("available commands: [q]uit / [p]ost / [f]ollow")
    print(s"${AC.BOLD}>${AC.RESET} ")
    Prompt.defer { line =>
      line match {
        case "" => { unrecognised; prompt(env) }
        case _ if "post".startsWith(line) => post(env)
        case _ if "follow".startsWith(line) => follow(env)
        case _ if "quit".startsWith(line) => Task.now(Stop)
        case _ => { unrecognised; prompt(env) }
      }
    }
  }

  def post(env: AuthEnv): Task[Resumable] = {
    println("Compose your missive.")
    print(s"${AC.BOLD}[posting]>${AC.RESET} ")
    Prompt.defer { msg =>
      Task.fromTry(Try(env.creds.sign(msg)))
        .flatMap { signed => env.stub.sendMessageTask(SendMessageRequest(env.creds.name, msg, signed)) }
        .flatMap{ _ =>
          println("Message successfully sent")
          println()
          prompt(env)
        }
    }
  }

  def follow(env: AuthEnv): Task[Resumable] = {
    println("Enter a user to follow")
    print(s"${AC.BOLD}[following]>${AC.RESET} ")
    Prompt.defer { name => stream(env, name) }
  }

  def stream(env: AuthEnv, name: String): Task[Resumable] = {
    println("Streaming messages, press enter to interrupt")
    println()
    val request = FollowRequest(name)
    keyFor(env, name).flatMap { key =>
      Task.create[ClientCallStreamObserver[FollowRequest]] { (s, cb) =>
        val observer = new ClientResponseObserver[FollowRequest, FollowResponse] {
          def beforeStart(cso: ClientCallStreamObserver[FollowRequest]) = cb.onSuccess(cso)
          def onCompleted(): Unit = ()
          def onError(ex: Throwable): Unit = {
            ex match {
              case StatusCancelled(_) => ()
              case NonFatal(t) => logger.error(s"Error while following $name", t)
            }
          }
          def onNext(response: FollowResponse): Unit = {
            if (!Crypto.verify(key, response.msg.`utf-8`, response.signature.toByteArray))
              throw new Exception(s"$name did not sign ${response.msg}")
            println(s"@${response.name}")
            println(response.msg)
            println()
          }
        }
        env.stub.follow(request, observer)
        Cancelable.empty
      }.flatMap { observer =>
        Prompt.defer { _ => observer.cancel("user cancelled", null); prompt(env) }
      }
    }
  }

  def keyFor(env: AuthEnv, name: String): Task[PublicKey] = {
    env.stub.getPublicKeyTask(PublicKeyRequest(name)).map { rep =>
      Crypto.decodePublicKey(rep.publicKey.toByteArray)
    }
  }

  def unrecognised() = println("Input not recognised.")
  def streamingNotice() = { println("Streaming posts. Press [enter] for prompt."); println() }
}
