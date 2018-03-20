package com.grandcloud

import com.google.protobuf.empty.Empty
import com.google.protobuf.field_mask.FieldMask
import java.nio.charset.StandardCharsets.UTF_8
import java.security.PublicKey
import monix.eval.Task
import com.grandcloud.mammut.protobuf._

package object mammut {

  implicit class MammutStubExtensions(stub: MammutGrpc.MammutStub) {
    def createUserTask(req: CreateUserRequest): Task[Empty] = {
      Task.deferFutureAction { implicit s => stub.createUser(req) }
    }

    def createPostTask(req: CreatePostRequest): Task[Empty] = {
      Task.deferFutureAction { implicit s => stub.createPost(req) }
    }

    def createFollowTask(req: CreateFollowRequest): Task[Empty] = {
      Task.deferFutureAction { implicit s => stub.createFollow(req) }
    }

    def getPublicKey(name: String): Task[Option[PublicKey]] = {
      val user = User(name)
      val mask = FieldMask(Seq("public_key"))
      val req = GetUserRequest(Some(user), Some(mask))
      Task.deferFutureAction { implicit s =>
        stub.getUser(req).map { rep => 
          rep.user.map { user =>
            val key = user.publicKey
            Crypto.decodePublicKey(key.toByteArray)
          }
        }
      }
    }
  }

  implicit class Utf8Bytes(val x: String) extends AnyVal {
    def `utf-8` = x.getBytes(UTF_8)
  }
}
