package com.grandcloud.mammut

import com.grandcloud.mammut.protobuf._
import com.typesafe.scalalogging.StrictLogging

import java.nio.ByteBuffer

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.util.Try

import org.rocksdb.{
  ColumnFamilyDescriptor,
  ColumnFamilyHandle,
  DBOptions,
  RocksDB,
  WriteBatch,
  WriteOptions
}

object RocksDBStorage {
  val ValidatorsKey = Array(1.toByte)
  val HashKey = Array(2.toByte)
  val HeightKey = Array(3.toByte)
}

class RocksDBStorage extends Storage with StrictLogging {

  RocksDB.loadLibrary()
  val options = new DBOptions().setCreateIfMissing(true)

  val default = new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY)
  val nonDefault =
    Seq("keys", "users", "follows", "posts", "chain", "counters")
      .map(x => new ColumnFamilyDescriptor(x.`utf-8`))

  // must open _all_ handles every time once created. Nice.
  Try(RocksDB.open(options, ".appstate", Seq(default).asJava, ListBuffer.empty[ColumnFamilyHandle].asJava)).map { db =>
    nonDefault.foreach { descriptor => Try(db.createColumnFamily(descriptor)) }
    db.close()
  }

  // Must open default even if unused by application
  val descriptors = (nonDefault :+ default).asJava

  val handles = ListBuffer.empty[ColumnFamilyHandle]
  val jhandles = handles.asJava

  val db = RocksDB.open(options, ".appstate", descriptors, jhandles)
  val keys = handles(0)
  val users = handles(1)
  val follows = handles(2)
  val posts = handles(3)
  val chain = handles(4)
  val counters = handles(5)

  def encodedPublicKey(name: String): Option[Array[Byte]] = {
    queryUser(name).map(_.publicKey.toByteArray)
  }

  def storeUser(user: User): Boolean = {
    val buf = allocateHashCounted // eventually we will allow for multiple keys
    buf.put(Crypto.digest(user.name.`utf-8`)).putInt(0)
    val key = buf.array
    val value = user.toByteArray

    val notFound = db.get(users, key, value) == RocksDB.NOT_FOUND
    if (notFound) {
      logger.info(s"storing user: ${user.name}")
      val batch = new WriteBatch
      batch.put(users, key, value)
      buf.rewind
      val counterKey = Array.ofDim[Byte](32)
      buf.get(counterKey)
      val counterValue = Array.ofDim[Byte](8)
      ByteBuffer.wrap(counterValue).asIntBuffer.put(-1).put(-1)
      batch.put(counters, counterKey, counterValue)
      db.write(new WriteOptions, batch)
    }

    notFound
  }

  def queryUser(name: String): Option[User] = {
    val buf = allocateHashCounted
    buf.put(Crypto.digest(name.`utf-8`)).putInt(0)
    val key = buf.array
    Option(db.get(users, key)).map(User.parseFrom)
  }

  def storeFollow(follow: Follow): Unit = {
    val follower = follow.follower.`utf-8`
    val followee = follow.followee.`utf-8`
    val buf = allocateDoubleHash
    buf.put(Crypto.digest(follower)).put(Crypto.digest(followee))
    db.put(follows, buf.array, Array(1:Byte))
  }

  def isfollowing(follower: String, followee: String): Boolean = {
    val buf = allocateDoubleHash
    buf.put(Crypto.digest(follower.`utf-8`)).put(Crypto.digest(followee.`utf-8`))
    db.get(follows, buf.array) != null
  }

  def storePost(post: Post): Unit = {
    val name = Crypto.digest(post.name.`utf-8`)
    val buf = allocateHashCounted
    buf.put(name)
    buf.flip
    val counterKey = Array.ofDim[Byte](32)
    buf.get(counterKey)
    val counterValue = db.get(counters, counterKey)
    val counterBuf = ByteBuffer.wrap(counterValue).asIntBuffer
    val counter = counterBuf.get() + 1
    counterBuf.rewind
    counterBuf.put(counter)
    logger.info(s"storing post: ${post.name}/$counter/${post.msg}")
    buf.limit(36)
    buf.putInt(counter)
    val batch = new WriteBatch
    batch.put(counters, counterKey, counterValue)
    batch.put(posts, buf.array, post.toByteArray)
    db.write(new WriteOptions, batch)
  }

  private def allocateHashCounted = ByteBuffer.allocate(36)
  private def allocateDoubleHash = ByteBuffer.allocate(64)

  def height(height: Long): Unit = {
    val buf = ByteBuffer.allocate(8)
    buf.putLong(height)
    db.put(chain, RocksDBStorage.HeightKey, buf.array)
  }

  def height: Option[Long] =
    Option(db.get(chain, RocksDBStorage.HeightKey)).map { h => ByteBuffer.wrap(h).getLong() }

  def hash(bytes: Array[Byte]): Unit =
    db.put(chain, RocksDBStorage.HashKey, bytes)

  def hash: Option[Array[Byte]] = Option(db.get(chain, RocksDBStorage.HashKey))
}
