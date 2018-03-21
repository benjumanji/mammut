package com.grandcloud.mammut

import io.circe.{ Json, Encoder, Decoder }
import io.circe.parser._
import io.circe.generic.semiauto._
import io.circe.syntax._

import okhttp3.OkHttpClient
import okhttp3.{ Request => OkHttpRequest }
import okhttp3.MediaType
import okhttp3.RequestBody

import io.taig.communicator.request.{ Parser => TParser, Request => TRequest }
import monix.eval.Task

import java.util.UUID

import scala.util.Try

object JsonRpc {

  val JsonMediaType = MediaType.parse("application/json; charset=utf-8");

  implicit val parser: TParser[Try[Response]] = TParser.instance[Try[Response]] { response =>
    parse(response.body.string).flatMap(_.as[Response]).toTry
  }

  def apply[A, B](method: String, params: A)(implicit client: OkHttpClient, ev1: Encoder[A], ev2: Decoder[B]): Task[B] = {
    val req = Request(method, params.asJson).asJson.noSpaces

    val builder = new OkHttpRequest.Builder()
      .url("http://localhost:46657")
      .post(RequestBody.create(JsonMediaType, req))

    TRequest(builder.build).parse[Try[Response]].flatMap { r =>
      Task.fromTry(r.body.flatMap(_.result.as[B].toTry))
    }
  }

  lazy implicit val encoder: Encoder[Request] = deriveEncoder
  lazy implicit val decoder: Decoder[Response] = deriveDecoder

  case class Request(method: String, params: Json, id: String = UUID.randomUUID.toString, jsonrpc: String = "2.0")
  case class Response(id: String, jsonrpc: String, result: Json)
}

