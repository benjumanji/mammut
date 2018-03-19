package com.grandcloud

import java.nio.charset.StandardCharsets.UTF_8

package object mammut {

  implicit class Utf8Bytes(val x: String) extends AnyVal {
    def `utf-8` = x.getBytes(UTF_8)
  }
}
