package mw.react

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

trait Def[+T] {
  outer =>
  implicit def exec: ExecutionContext
  def subscribe(subscriber: Subscriber[T])
  def foreach(action: T => Any) = outer.subscribe(new Subscriber[T] {
    private var cancelled = false
    private var cancel: () => Any = { () => }
    def onCancel(action: => Any) = cancel = { () => action }
    def published(t: T) = if (!cancelled && Try(action(t)).isFailure) {
      cancelled = true
      cancel()
      cancel = { () => }
    }
    def closed() = cancel = { () => }
    def failed(error: Throwable) = cancel = { () => }
    override def toString = s"Subscriber($outer)"
  })
  def map[S](f: T => S): Def[S] = new Def.Impl[S] {
    result =>
    private var cancel: () => Any = { () => }
    outer.subscribe(new Subscriber[T] {
      def onCancel(action: => Any) = cancel = { () => action }
      def published(t: T) = Try(f(t)) match {
        case Success(s) =>
          result.publish(s)
        case Failure(e) =>
          cancel()
          result.fail(e)
      }
      def closed() = result.close()
      def failed(error: Throwable) = result.fail(error)
      override def toString = s"Subscriber($outer->$result)"
    })
    override def close() = {
      super.close()
      cancel = { () => }
    }
    override def fail(error: Throwable) = {
      super.fail(error)
      cancel = { () => }
    }
    override def toString = s"Mapped${super.toString}"
  }
  def withFilter(p: T => Boolean): Def[T] = new Def.Impl[T] {
    result =>
    private var cancel: () => Any = { () => }
    outer.subscribe(new Subscriber[T] {
      def onCancel(action: => Any) = cancel = { () => action }
      def published(t: T) = Try(p(t)) match {
        case Success(true) =>
          result.publish(t)
        case Success(false) =>
        case Failure(error) =>
          cancel()
          result.fail(error)
      }
      def closed() = result.close()
      def failed(error: Throwable) = result.fail(error)
      override def toString = s"Subscriber($outer->$result)"
    })
    override def close() = {
      super.close()
      cancel = { () => }
    }
    override def fail(error: Throwable) = {
      super.fail(error)
      cancel = { () => }
    }
    override def toString = s"Filtered${super.toString}"
  }
  def merge[S >: T](that: Def[S]): Def[S] = new Def.Impl[S] {
    result =>
    private var outerDone = false
    private var thatDone = false
    private var cancelOuter: () => Any = { () => }
    private var cancelThat: () => Any = { () => }
    outer.subscribe(new Subscriber[T] {
      def onCancel(action: => Any) = cancelOuter = { () => action }
      def published(t: T) = result.publish(t)
      def closed() = {
        outerDone = true
        if (thatDone)
          result.close()
      }
      def failed(error: Throwable) = {
        cancelThat()
        result.fail(error)
      }
      override def toString = s"Subscriber($outer->$result)"
    })
    that.subscribe(new Subscriber[S] {
      def onCancel(action: => Any) = cancelThat = { () => action }
      def published(s: S) = result.publish(s)
      def closed() = {
        thatDone = true
        if (outerDone)
          result.close()
      }
      def failed(error: Throwable) = {
        cancelOuter()
        result.fail(error)
      }
      override def toString = s"Subscriber($that->$result)"
    })
    override def close() = {
      super.close()
      cancelOuter = { () => }
      cancelThat = { () => }
    }
    override def fail(error: Throwable) = {
      super.fail(error)
      cancelOuter = { () => }
      cancelThat = { () => }
    }
    override def toString = s"Merged${super.toString}"
  }
  def flatMap[S](f: T => Def[S]): Def[S] = new Def.Impl[S] {
    result =>
    private var cancelMain: () => Any = { () => }
    private var cancelSecondary: () => Any = { () => }
    private var autoClosing = false
    private var secondary = Option.empty[Def[S]]
    outer.subscribe(new Subscriber[T] {
      def onCancel(action: => Any) = cancelMain = { () => action }
      def published(t: T) = Try(f(t)) match {
        case Success(publisher) =>
          cancelSecondary()
          cancelSecondary = { () => }
          secondary = Some(publisher)
          publisher.subscribe(new Subscriber[S] {
            def onCancel(action: => Any) =
              if (secondary.contains(publisher)) cancelSecondary = { () => action }
              else action
            def published(s: S) = if (secondary.contains(publisher)) result.publish(s)
            def closed() = if (secondary.contains(publisher)) {
              secondary = None
              if (autoClosing)
                result.close()
            }
            def failed(error: Throwable) = if (secondary.contains(publisher)) {
              cancelMain()
              result.fail(error)
            }
            override def toString = s"Subscriber($this->$result)"
          })
        case Failure(error) =>
          cancelMain()
          cancelSecondary()
          result.fail(error)
      }
      def closed() = {
        autoClosing = true
        if (secondary.isEmpty)
          result.close()
      }
      def failed(error: Throwable) = {
        cancelSecondary()
        result.fail(error)
      }
      override def toString = s"Subscriber($outer->$result)"
    })
    override def close() = {
      super.close()
      cancelMain = { () => }
      cancelSecondary = { () => }
    }
    override def fail(error: Throwable) = {
      super.fail(error)
      cancelMain = { () => }
      cancelSecondary = { () => }
    }
    override def toString = s"FlatMapped${super.toString}"
  }
  def flatten[S](implicit id: T => Def[S]) = flatMap(id)
  def head: Val[T] = ???
  def tail: Def[T] = ???
}
object Def {
  class Impl[T](implicit ctx: ExecutionContext) extends Def[T] {
    private var current = Promise[AList]
    def exec = ctx
    protected def update(expr: => T) = enqueue(current, Try(ACons(expr)))
    protected def publish(t: T) = enqueue(current, Success(ACons(t)))
    protected def close() = enqueue(current, Success(ANil))
    protected def fail(error: Throwable) = enqueue(current, Failure(error))
    @tailrec
    private def enqueue(promise: Promise[AList], elem: Try[AList]): Unit =
      if (promise.tryComplete(elem)) current = promise
      else promise.future.value match {
        case Some(Success(ACons(_, tail))) => enqueue(tail, elem)
        case _ => current = promise
      }
    def subscribe(subscriber: Subscriber[T]) = {
      var cancelled = false
      subscriber.onCancel {
        cancelled = true
      }
      monitor(current.future)
      def monitor(future: Future[AList]): Unit = future.onComplete {
        case Success(ACons(head, tail)) =>
          subscriber.published(head)
          monitor(tail.future)
        case Success(ANil) => subscriber.closed()
        case Failure(error) => subscriber.failed(error)
      }
    }
    override def toString = current.future.value match {
      case Some(Success(aList)) => s"Def($aList)"
      case Some(Failure(error)) => s"Def(<$error>)"
      case None => "Def(<?>)"
    }
    sealed trait AList
    case object ANil extends AList
    case class ACons(head: T, tail: Promise[AList] = Promise[AList]) extends AList {
      override def toString = tail.future.value match {
        case Some(Success(tail)) => s"$head::$tail"
        case Some(Failure(error)) => s"$head::<$error>"
        case None => s"$head::<?>"
      }
    }
  }
  object Silent extends Def[Nothing] {
    implicit def exec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
    def subscribe(subscriber: Subscriber[Nothing]) = {}
  }
}
