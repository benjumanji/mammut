package com.grandcloud

import com.google.protobuf.empty.Empty
import java.nio.charset.StandardCharsets.UTF_8
import monix.eval.Task

package object mammut {

  implicit class MammutTaskStub(stub: MammutGrpc.MammutStub) {
    def registerNameTask(req: RegisterNameRequest): Task[Empty] = {
      Task.deferFutureAction { implicit s => stub.registerName(req) }
    }

    def sendMessageTask(req: SendMessageRequest): Task[Empty] = {
      Task.deferFutureAction { implicit s => stub.sendMessage(req) }
    }

    def getPublicKeyTask(req: PublicKeyRequest): Task[PublicKeyResponse] = {
      Task.deferFutureAction { implicit s => stub.getPublicKey(req) }
    }
  }

  implicit class Utf8Bytes(val x: String) extends AnyVal {
    def `utf-8` = x.getBytes(UTF_8)
  }
}
