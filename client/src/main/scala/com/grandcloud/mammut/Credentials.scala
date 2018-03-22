package com.grandcloud.mammut

import com.google.protobuf.ByteString
import java.nio.ByteBuffer
import java.nio.file.{ Files, Path, StandardOpenOption}
import java.security.{ Signature, KeyPair }
import monix.eval.Task
import scala.collection.JavaConverters._

case class Credentials(name: String, keys: KeyPair) {
  def sign(msg: Array[Byte]): ByteString = {
    val signature = Signature.getInstance("SHA256withECDSA")
    signature.initSign(keys.getPrivate)
    signature.update(msg)
    ByteString.copyFrom(signature.sign)
  }

  def store(directory: Path): Task[Unit] = {
    Task {
      Credentials.createIfNotExists(directory)
      val credspath = directory.resolve(name)

      if (credspath.toFile.exists)
        throw new Exception(s"Credentials already exist for ${name}. Please login")

      Files.createDirectory(credspath)
      val pub = Files.newByteChannel(credspath.resolve("pub"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
      pub.write(ByteBuffer.wrap(keys.getPublic.getEncoded))
      val priv = Files.newByteChannel(credspath.resolve("priv"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
      priv.write(ByteBuffer.wrap(keys.getPrivate.getEncoded))
    }
  }
}

object Credentials {
  def list(directory: Path): Task[Seq[String]] = {
    Task {
      createIfNotExists(directory)
      val builder = Seq.newBuilder[String]
      Files.newDirectoryStream(directory).asScala.foreach { path =>
        builder += path.toFile.getName 
      }
      builder.result
    }
  }

  def get(directory: Path, name: String): Task[Credentials] = {
    Task {
        val credspath = directory.resolve(name)
        val pubchan = Files.newByteChannel(credspath.resolve("pub"), StandardOpenOption.READ)
        val buf = ByteBuffer.allocate(4096)
        pubchan.read(buf)
        buf.flip
        val pubencoded = Array.ofDim[Byte](buf.limit)
        buf.get(pubencoded)
        val pub = Crypto.decodePublicKey(pubencoded)
        buf.clear
        val privchan = Files.newByteChannel(credspath.resolve("priv"), StandardOpenOption.READ)
        privchan.read(buf)
        buf.flip
        val privencoded = Array.ofDim[Byte](buf.limit)
        buf.get(privencoded)
        val priv = Crypto.decodePrivateKey(privencoded)
        Credentials(name, new KeyPair(pub, priv))
    }
  }

  private def createIfNotExists(path: Path) = {
    if (!path.toFile.exists) {
      Files.createDirectory(path)
    }
  }
}
