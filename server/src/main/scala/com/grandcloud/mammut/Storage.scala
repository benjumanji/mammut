package com.grandcloud.mammut

import com.grandcloud.mammut.protobuf._

trait Storage {
  /**
   * Queries the current application hash.
   * @return The application hash.
   */
  def hash: Option[Array[Byte]]

  /**
   * Sets the application hash.
   * @param hash The application hash.
   */
  def hash(hash: Array[Byte]): Unit

  /**
   * Queries the current blockchain height.
   * @return The current blockchain height
   */
  def height: Option[Long]

  /**
   * Sets the blockchain height.
   * @param height The blockchain height.
   */
  def height(height: Long): Unit

  /**
   * Stores a user.
   * @param user The user to store
   * @return If the user was stored or not. Returns false in the case of
   *         duplicate users.
   */
  def storeUser(user: User): Boolean

  /**
   * Query user.
   * @param name The user name.
   */
  def queryUser(name: String): Option[User]

  /**
   * Retrieve the encoded public key of user if it exists.
   * @param name The key owner.
   * @return The encoded key.
   */
  def encodedPublicKey(name: String): Option[Array[Byte]]

  /**
   * Stores a follow
   * @param follow The follow to store.
   */
  def storeFollow(follow: Follow): Unit

  /**
   * Queries if one user follows another.
   * @param follower The following user
   * @param followee The followed user.
   * @return True if the follower follows the followed.
   */
  def isfollowing(follower: String, followee: String): Boolean

  /**
   * Stores a post.
   * @param post The post to store.
   */
  def storePost(post: Post): Unit
}
