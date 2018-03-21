package com.grandcloud.mammut

import com.grandcloud.mammut.protobuf._

import scala.concurrent.Future
import scala.util.Try

object CodeType {
  val Ok = 0;
  val Bad = 1;
  val BadNonce = 2;
}

class MammutAbci(service: Service) extends types.ABCIApplicationGrpc.ABCIApplication {

  private def foldException(ex: Throwable): types.ResponseDeliverTx = {
    val message = Option(ex.getMessage).getOrElse("")
    val code: io.grpc.Status.Code = ex match {
      case t: io.grpc.StatusException        => t.getStatus.getCode
      case t: io.grpc.StatusRuntimeException => t.getStatus.getCode
      case _                                 => io.grpc.Status.Code.UNKNOWN
    }
    types.ResponseDeliverTx(code.value, info=message)
  }

  def beginBlock(request: types.RequestBeginBlock): Future[types.ResponseBeginBlock] = {
    Future.successful(types.ResponseBeginBlock())
  }

  def checkTx(request: types.RequestCheckTx): Future[types.ResponseCheckTx] = {
    Future.successful(types.ResponseCheckTx())
  }

  def commit(request: types.RequestCommit): Future[types.ResponseCommit] = {
    // TODO(ben): Calculate app hash
    Future.successful(types.ResponseCommit())
  }

  def deliverTx(request: types.RequestDeliverTx): Future[types.ResponseDeliverTx] = {
    val response = Try(Event.parseFrom(request.tx.toByteArray)).map { ev =>
      ev.event match {
        case Event.Event.Post(post) => service.createPostInternal(post)
        case Event.Event.User(user) => service.createUserInternal(user)
        case Event.Event.Follow(follow) => service.createFollowInternal(follow)
        case Event.Event.Empty =>
      }
      types.ResponseDeliverTx(CodeType.Ok)
    }
    Future.successful(response.fold(foldException, identity))
  }

  def echo(request: types.RequestEcho): Future[types.ResponseEcho] = {
    Future.successful(types.ResponseEcho(request.message))
  }

  def endBlock(request: types.RequestEndBlock): Future[types.ResponseEndBlock] = {
    Future.successful(types.ResponseEndBlock())
  }

  def flush(request: types.RequestFlush): Future[types.ResponseFlush] = {
    Future.successful(types.ResponseFlush())
  }

  def info(request: types.RequestInfo): Future[types.ResponseInfo] = {
    Future.successful(types.ResponseInfo())
  }

  def initChain(request: types.RequestInitChain): Future[types.ResponseInitChain] = {
    Future.successful(types.ResponseInitChain())
  }

  def query(request: types.RequestQuery): Future[types.ResponseQuery] = ???
  def setOption(request: types.RequestSetOption): Future[types.ResponseSetOption] = ???
}