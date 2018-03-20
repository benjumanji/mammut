package com.grandcloud.mammut

import io.grpc.stub.{ ClientCallStreamObserver, ClientResponseObserver, StreamObserver }

import monix.execution.{ Ack, Cancelable }
import monix.execution.cancelables.SingleAssignCancelable
import monix.reactive.{ Observable, OverflowStrategy }
import monix.reactive.observers.{ BufferedSubscriber, Subscriber }

class GrpcServerStreamSubscriber[Req, Rep](cl: SingleAssignCancelable, sub: Subscriber[Rep], bufferSize: Int)
    extends ClientResponseObserver[Req, Rep] {

  var cso: ClientCallStreamObserver[Req] = null

  def beforeStart(_cso: ClientCallStreamObserver[Req]) = {
    cso = _cso
    cso.disableAutoInboundFlowControl
    cl := Cancelable(() => _cso.cancel("cancelled", null))
    cso.setOnReadyHandler(new Runnable {
      def run = { cso.request(bufferSize) }
    })
  }

  def onNext(rep: Rep) = {
    sub.onNext(rep).map {
      case Ack.Continue => cso.request(1)
      case Ack.Stop => { cl.cancel; sub.onComplete }
    }(sub.scheduler)
  }

  def onError(ex: Throwable) = {
    ex match {
      case StatusCancelled(_) =>
      case _ => sub.onError(ex)
    }
  }

  def onCompleted() = sub.onComplete
}

object GrpcServerStreamObservable {
  def apply[Rep](f: StreamObserver[Rep] => Unit, buffer: Int = 64): Observable[Rep] = {
    Observable.unsafeCreate[Rep] { sub =>
      val cancelable = SingleAssignCancelable()
      val buffered = BufferedSubscriber[Rep](sub, OverflowStrategy.BackPressure(buffer))
      val observer = new GrpcServerStreamSubscriber(cancelable, buffered, buffer)
      f(observer)
      cancelable
    }
  }
}
