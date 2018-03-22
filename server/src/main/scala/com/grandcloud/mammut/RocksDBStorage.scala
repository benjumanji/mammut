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
  ReadOptions,
  RocksDB,
  Slice,
  WriteBatch,
  WriteOptions
}

object RocksDBStorage {
  val ValidatorsKey = Array(1.toByte)
  val HashKey = Array(2.toByte)
  val HeightKey = Array(3.toByte)

  val MaxHash = Array.fill[Byte](32)(-1)
}

class RocksDBStorage extends Storage with StrictLogging {

  RocksDB.loadLibrary()
  val options = new DBOptions().setCreateIfMissing(true)

  val default = new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY)
  val nonDefault =
    Seq("keys", "users", "follows", "posts", "chain", "counters", "followed", "timelines")
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
  val followed = handles(6)
  val timelines = handles(7)

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
      withBatch { batch =>
        logger.debug(s"storing user: ${user.name} as ${hex(key)}")
        batch.put(users, key, value)
        buf.rewind
        val counterKey = Array.ofDim[Byte](32)
        buf.get(counterKey)
        val counterValue = Array.ofDim[Byte](12)
        ByteBuffer.wrap(counterValue).asIntBuffer.put(-1).put(0).put(-1)
        batch.put(counters, counterKey, counterValue)
        logger.debug(s"storing counters for ${user.name} as ${hex(counterKey)}")
      }
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
    withBatch { batch =>
      val follower = Crypto.digest(follow.follower.`utf-8`)
      val followee = Crypto.digest(follow.followee.`utf-8`)
      val buf = allocateDoubleHash

      val one = Array(1:Byte)

      // store forward "x follows y"
      val forward = Array.ofDim[Byte](64)
      buf.put(follower).put(followee)
      buf.flip
      buf.get(forward)
      batch.put(follows, forward, one)

      // store reverse "y followed by x"
      buf.clear
      buf.put(followee).put(follower)
      batch.put(followed, buf.array, one)
    }
  }

  def isfollowing(follower: String, followee: String): Boolean = {
    val buf = allocateDoubleHash
    buf.put(Crypto.digest(follower.`utf-8`)).put(Crypto.digest(followee.`utf-8`))
    db.get(follows, buf.array) != null
  }

  def storePost(post: Post): Unit = {
    val name = Crypto.digest(post.name.`utf-8`)
    withBatch { batch =>
      withCounted(posts, 0, name, batch) { Iterator.single(post.toByteArray) }

      val minKey = Array.ofDim[Byte](64)
      ByteBuffer.wrap(minKey).put(name)

      val maxKey = Array.ofDim[Byte](64)
      ByteBuffer.wrap(maxKey).put(name).put(RocksDBStorage.MaxHash)

      val upperBound = new Slice(maxKey)
      val opts = new ReadOptions()
      opts.setIterateUpperBound(upperBound)
      val iterator = db.newIterator(followed, opts)
      val chained = ChainedCloseable(upperBound, opts, iterator)
      using(chained) { _ =>
        val follower = Array.ofDim[Byte](32)
        val buf = ByteBuffer.allocate(64)
        while (iterator.isValid) {
          buf.put(iterator.key)
          buf.position(32)
          buf.get(follower)
          buf.clear
          withCounted(timelines, 2, follower, batch) { Iterator.single(post.toByteArray) }
          iterator.next
        }
      }
    }
  }

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

  private def allocateHashCounted = ByteBuffer.allocate(36)
  private def allocateDoubleHash = ByteBuffer.allocate(64)

  private def withBatch[U](f: WriteBatch => U): U = {
    val opts = new WriteOptions
    val batch = new WriteBatch
    try {
      val ret = f(batch)
      db.write(opts, batch)
      ret
    } finally {
      opts.close
      batch.close
    }
  }

  private def using[A <: AutoCloseable, B](closable: => A)(f: A => B): B = {
    try { f(closable) } finally { closable.close }
  }

  private def withCounted(
      cf: ColumnFamilyHandle,
      counterIx: Int,
      prefix: Array[Byte],
      batch: WriteBatch)(values: => Iterator[Array[Byte]]): Unit = {

    val cfName = new String(cf.getName)

    val buf = allocateHashCounted
    buf.put(prefix)
    buf.flip
    val counterKey = Array.ofDim[Byte](32)
    buf.get(counterKey)
    buf.limit(36)

    logger.debug(s"reading counter value at ${counterIx} for ${hex(counterKey)}")
    val rawCounterBuf = ByteBuffer.wrap(db.get(counters, counterKey))
    val counterBuf = rawCounterBuf.asIntBuffer
    val base = counterBuf.get(counterIx) + 1

    logger.debug(s"read $base as next counted for ${hex(counterKey)} in $cfName")

    var offset = 0

    values.zipWithIndex.foreach { case (x, i) =>
      val key = Array.ofDim[Byte](36)
      offset = base + i
      buf.putInt(32, offset).rewind
      buf.get(key)
      batch.put(cf, key, x)
    }

    logger.debug(s"moved from ${base - 1} to $offset in $cfName")
    counterBuf.put(counterIx, offset)
    batch.put(counters, prefix, rawCounterBuf.array)
  }

  private case class ChainedCloseable(xs: AutoCloseable*) extends AutoCloseable {
    def close(): Unit = {
      lazy val ex = new Exception("Failed to close objects cleanly")
      var failed = false
      xs.foreach { x =>
        Try(x.close).failed.foreach { t => ex.addSuppressed(t); failed = true }
      }
      if (failed) throw ex
    }
  }

  def hex(bytes: Array[Byte]) = bytes.map(x => "%02x".format(x)).mkString

}
