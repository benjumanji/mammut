package com.grandcloud.mammut

import io.circe.{ Encoder, Decoder }
import io.circe.generic.semiauto._
import monix.eval.Task


/**
 * Convenience for dealing with tendermint transactions over jsonrpc
 */
object Transaction {

  lazy implicit val requestEncoder: Encoder[Request] = deriveEncoder
  lazy implicit val responseDecoder: Decoder[Response] =
      Decoder.forProduct4("check_tx", "deliver_tx", "hash", "height")(Response.apply)

  lazy implicit val checkDecoder: Decoder[CheckTx] = deriveDecoder
  lazy implicit val deliverDecoder: Decoder[DeliverTx] = deriveDecoder
  lazy implicit val feeDecoder: Decoder[Fee] = deriveDecoder

  val encoder = java.util.Base64.getEncoder

  /** 
   * Request envelope.
   * @constructor Create a new request.
   * @param tx The base64 encoded transaction.
   */
  case class Request(tx: String)

  /**
   * Transaction submission response.
   *
   * CheckTx and DeliverTx hold the results of their respective block chain ops.
   * They are only populated if something goes wrong. Hash and height are the
   * hash and height of the block the transaction was included in.
   *
   * @constructor Create a new response.
   * @param checkTx Check tx result.
   * @param deliverTx Deliver tx result.
   */
  case class Response(checkTx: CheckTx, deliverTx: DeliverTx, hash: String, height: Long)

  trait TxResult {
    def code: Option[Int]
    def info: Option[String]
  }

  /**
   * Result of transaction checking.
   *
   * @param code Return code. Only populated on failure
   * @param info Informational message. Only populated on failure.
   * @param fee Not used.
   */
  case class CheckTx(code: Option[Int], info: Option[String], fee: Fee) extends TxResult

  /**
   * Result of transaction delivery.
   *
   * @param code Return code. Only populated on failure
   * @param info Informational message. Only populated on failure.
   */
  case class DeliverTx(code: Option[Int], info: Option[String]) extends TxResult

  /**
   * Fees charged. We don't have a notion of fees, so this is always empty.
   */
  case class Fee()

  /**
   * Broadcast a transaction into the mempool.
   *
   * The task returns once the the transaction has been both accepted and
   * delivered. This typically happens on the order of one second.
   *
   * @param tx The raw transaction
   * @return The decoded json rpc result.
   */
  def broadcast(tx: Array[Byte])(implicit client: okhttp3.OkHttpClient): Task[Response] = {
    JsonRpc[Request, Response]("broadcast_tx_commit", Request(encoder.encodeToString(tx)))
  }
}
