package com.grandcloud.mammut

import com.grandcloud.mammut.protobuf._
import com.typesafe.scalalogging.StrictLogging

import io.grpc.Status

/**
 * Delivers the _effects_ of a given event recorded by a transaction.
 *
 * @param following The collection of users following each other
 */
class DeliverTx(storage: Storage) extends StrictLogging {
  /**
   * Creates a user.
   * @param user The user to create.
   */
  def createUser(user: User): Unit = {
    if (!storage.storeUser(user))
      Status.ALREADY_EXISTS
        .augmentDescription(s"${user.name} already exists")
        .asException
  }

  /**
   * Creates a follow relationship.
   * @param follow The follow to create.
   */
  def createFollow(follow: Follow): Unit = {
    storage.storeFollow(follow)
  }

  /**
   * Create a post.
   * @param post The posr to create.
   */
  def createPost(post: Post): Unit = storage.storePost(post)

}
