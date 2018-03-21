package com.grandcloud.mammut

import monix.reactive.Observable

import com.grandcloud.mammut.protobuf._

trait Storage {
  /**
   * Retrieve the encoded public key of user if it exists.
   * @param name The key owner.
   * @return The encoded key.
   */
  def encodedPublicKey(name: String): Option[Array[Byte]]

  /**
   * The current application hash.
   * @return The application hash.
   */
  def hash: Option[Array[Byte]]

  /**
   * Set the application hash.
   * @param hash The application hash.
   */
  def hash(hash: Array[Byte]): Unit

  /**
   * The current blockchain height.
   * @return The current blockchain height
   */
  def height: Option[Long]

  /**
   * Set the blockchain height.
   * @param height The blockchain height.
   */
  def height(height: Long): Unit

  def posts: Observable[Post]
  def follow(follow: Follow): Unit
  def isfollowing(follower: String, followee: String): Boolean
  def createUser(user: User): Boolean
  def getUser(name: String): Option[User]
  def storePost(post: Post): Unit
}
