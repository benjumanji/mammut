package com.grandcloud.mammut
package server

import com.grandcloud.mammut.protobuf.Post

object Implicits {
  implicit class ServerPostExtensions(val post: Post) extends AnyVal {

    /**
     * Validate whether a Post should be stored.
     * Throws if it shouldn't.
     * @param storage Application storage
     */
    def check(storage: Storage): Unit = {
      storage.encodedPublicKey(post.name).map { encoded =>
        val key = Crypto.decodePublicKey(encoded)
        if (!Crypto.verify(key, post.msg.`utf-8`, post.signature.toByteArray))
          throw new InvalidTransactionException(s"Bad message for ${post.name}")
      }.getOrElse(throw new InvalidTransactionException(s"User ${post.name} doesn't exist"))
    }
  }

  case class InvalidTransactionException(msg: String) extends Exception(msg)
}
