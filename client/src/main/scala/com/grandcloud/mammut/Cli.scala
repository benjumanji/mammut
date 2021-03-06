package com.grandcloud.mammut


import com.grandcloud.mammut.protobuf._
import com.typesafe.scalalogging.StrictLogging
import com.google.protobuf.ByteString

import java.nio.file.{ Path, Paths }
import io.grpc.{ Status, StatusRuntimeException }

import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

import scala.io.{ AnsiColor => AC }
import scala.util.Try

sealed trait Resumable
case class Prompt(f: String => Task[Resumable]) extends Resumable
case class Password(f: Array[Char] => Task[Resumable]) extends Resumable
case object Stop extends Resumable

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
  lazy val credsDir: Path = {
    sys.env.get("MAMMUT_HOME").map(x => Paths.get(x)).getOrElse {
      val ret = Paths.get(sys.props("user.home"), ".mammut")
      println(s"using $ret for credentials. Override with ${AC.BOLD}MAMMUT_HOME${AC.RESET}")
      ret
    }
  }
  
  def upgrade(creds: Credentials): AuthEnv = AuthEnv(stub, creds)
}

case class AuthEnv(stub: MammutGrpc.MammutStub, creds: Credentials)

object Cli extends StrictLogging {
  def apply(env: InitEnv): Resumable = {
    println("available commands: [r]egister / use [e]xisting account")
    Prompt { line =>
      line.replaceAll("\\s+", "") match {
        case "" => { unrecognised; Task(apply(env)) }
        case _ if "register".startsWith(line) => registerUser(env)
        case _ if "existing".startsWith(line) => loadUser(env)
      }
    }
  }

  def loadUser(env: InitEnv): Task[Resumable] = {
    val credspath = env.credsDir
    Credentials.list(credspath).map { accounts =>
      accounts.size match {
        case 0 =>
          println("No accounts found. Please register instead.")
          registerUserNow(env)
        case _ =>
          println("Available accounts:")
          accounts.zipWithIndex.foreach { case (account, i) => println(s"[${i + 1}] $account") }
          println("please select an account.")

          Prompt { line =>
            Try(line.replaceAll("\\s+$", "").toInt).map { selected =>
              val index = selected - 1
              if (accounts.isDefinedAt(index)) {
                val account = accounts(index)
                Credentials.get(credspath, account)
                  .map(env.upgrade)
                  .flatMap(prompt)
              } else {
                println(s"Account not available at index $selected")
                loadUser(env)
              }
            }.getOrElse { println(s"Input not recognised as a number"); loadUser(env) }
          }
      }
    }
  }

  def registerUser(env: InitEnv): Task[Resumable] = Task(registerUserNow(env))

  def registerUserNow(env: InitEnv): Resumable = {
    println("Please supply a username (no spaces allowed / under 20 chars)")
    print(s"${AC.BOLD}[registering/username]>${AC.RESET} ")
    Prompt { name =>
      name match {
        case _ if (name.size > 20) => { println("Username too long"); registerUser(env) }
        case _ if (name.exists(_.isSpaceChar)) => { println("User name contains spaces"); registerUser(env) }
        case _ if (name.exists(_ == 0.toChar)) => { println("User name contains null"); registerUser(env) }
        case _ => {
          val keys = Crypto.freshKeys
          val request = CreateUserRequest().withUser(User(name, ByteString.copyFrom(keys.getPublic.getEncoded)))
          for {
            _ <- env.stub.createUserTask(request)
            creds = Credentials(name, keys)
            path = env.credsDir
            _ <- creds.store(path)
            step <- prompt(env.upgrade(creds))
          } yield step
        }
      }
    }
  }

  def prompt(env: AuthEnv): Task[Resumable] = {
    println("available commands: [q]uit / [p]ost / [f]ollow / [s]stream")
    print(s"${AC.BOLD}>${AC.RESET} ")
    Prompt.defer { line =>
      line match {
        case "" => { unrecognised; prompt(env) }
        case _ if "post".startsWith(line) => post(env)
        case _ if "follow".startsWith(line) => follow(env)
        case _ if "stream".startsWith(line) => stream(env)
        case _ if "quit".startsWith(line) => Task.now(Stop)
        case _ => { unrecognised; prompt(env) }
      }
    }
  }

  def post(env: AuthEnv): Task[Resumable] = {
    println("Compose your missive.")
    print(s"${AC.BOLD}[posting]>${AC.RESET} ")
    Prompt.defer { msg =>
      Task.fromTry(Try(env.creds.sign(msg.`utf-8`)).map(signed => Post(env.creds.name, msg, signed)))
        .flatMap { post => env.stub.createPostTask(CreatePostRequest(Some(post))) }
        .flatMap{ _ =>
          println("Message successfully sent")
          println()
          prompt(env)
        }
    }
  }

  def follow(env: AuthEnv): Task[Resumable] = {
    println("Enter a user to follow.")
    print(s"${AC.BOLD}[follow]>${AC.RESET} ")
    Prompt.defer { name => 
      val follower = env.creds.name
      val sig = {
        val frb = follower.`utf-8`
        val feb = name.`utf-8`
        val buf = java.nio.ByteBuffer.allocate(frb.size + feb.size + 1)
        buf.put(frb).put(0: Byte).put(feb)
        env.creds.sign(buf.array)
      }
      val req = CreateFollowRequest().withFollow(Follow("", follower, name, sig))
      env.stub.createFollowTask(req).flatMap {_ => println(s"$name followed."); prompt(env) }
    }
  }

  def stream(env: AuthEnv): Task[Resumable] = {
    println("Streaming messages, press enter to interrupt")
    println()
    val request = StreamPostsRequest(Some(User(env.creds.name)))
    val posts = GrpcServerStreamObservable.apply[Post](o => env.stub.streamPosts(request, o))
    val printing = posts.mapTask { post =>
      val handle = s"@${post.name}"
      val msg = env.stub.getPublicKey(post.name).map { optKey =>
        optKey.map { key =>
          if (!Crypto.verify(key, post.msg.`utf-8`, post.signature.toByteArray))
            s"Post from $handle with bad signature"
          else
            s"$handle\n${post.msg}\n"
        }.getOrElse(s"Post from $handle but no key found")
      }
      msg.map(println)
    }
    val cancelable = printing.subscribe()
    Prompt.defer { _ => cancelable.cancel; prompt(env) }
  }

  def unrecognised() = println("Input not recognised.")
  def streamingNotice() = { println("Streaming posts. Press [enter] for prompt."); println() }

}
