package com.grandcloud.mammut

import java.security.{
  KeyFactory,
  KeyPair,
  KeyPairGenerator,
  MessageDigest,
  PublicKey,
  Signature
}
import java.security.spec.X509EncodedKeySpec

import org.bouncycastle.jce.ECNamedCurveTable

object Crypto {

  def freshKeys: KeyPair = {
    val generator = KeyPairGenerator.getInstance("EC", "BC")
    val spec = ECNamedCurveTable.getParameterSpec("secp256k1")
    generator.initialize(spec)
    generator.generateKeyPair()
  }

  def decodePublicKey(bytes: Array[Byte]): PublicKey = {
    val factory = KeyFactory.getInstance("EC", "BC")
    val spec = new X509EncodedKeySpec(bytes)
    factory.generatePublic(spec)
  }

  def verify(key: PublicKey, bytes: Array[Byte], sig: Array[Byte]): Boolean = {
    val signature = Signature.getInstance("SHA256withECDSA")
    signature.initVerify(key)
    signature.update(bytes)
    signature.verify(sig)
  }

  def digest(bytes: Array[Byte]): Array[Byte] = {
    val sha = MessageDigest.getInstance("SHA-256")
    sha.digest(bytes)
  }
}

