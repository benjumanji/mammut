package com.grandcloud.mammut

import com.grandcloud.mammut.protobuf._
import com.google.protobuf.ByteString

import java.util.concurrent.ConcurrentHashMap
import io.grpc.stub.ServerCallStreamObserver

import scala.collection.mutable

class InMemoryStorage extends Storage {

  val keys =  new ConcurrentHashMap[String, ByteString]
  val following = mutable.Set.empty[(String, String)]

  def hash: Option[Array[Byte]] = None

  def hash(hash: Array[Byte]): Unit = ()

  def height: Option[Long] = None

  def height(height: Long): Unit = ()

  /**
   * Stores a user.
   * @param user The user to store
   * @return If the user was stored or not. Returns false in the case of
   *         duplicate users.
   */
  def storeUser(user: User): Boolean = {
    val current = keys.putIfAbsent(user.name, user.publicKey)
    current != null
  }

  /**
   * Query user.
   * @param name The user name.
   */
  def queryUser(name: String): Option[User] = {
    Option(keys.get(name)).map { key => User(name, key) }
  }

  /**
   * Retrieve the encoded public key of user if it exists.
   * @param name The key owner.
   * @return The encoded key.
   */
  def encodedPublicKey(name: String): Option[Array[Byte]] = {
    Option(keys.get(name)).map(_.toByteArray)
  }

  /**
   * Stores a follow
   * @param follow The follow to store.
   */
  def storeFollow(follow: Follow): Unit =
    following.synchronized { following.add((follow.follower, follow.followee)) }

  /**
   * Queries if one user follows another.
   * @param follower The following user
   * @param followee The followed user.
   * @return True if the follower follows the followed.
   */
  def isfollowing(follower: String, followee: String): Boolean =
    following.synchronized { following.contains((follower, followee)) }

  /**
   * Stores a post.
   * @param post The post to store.
   */
  def storePost(post: Post): Unit = ()
}
