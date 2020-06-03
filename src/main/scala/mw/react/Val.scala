package mw.react

import scala.concurrent.{ExecutionContext, Future}

trait Val[+T] extends Def[T] {
  override def map[S](f: T => S): Val[S] = ???
  override def withFilter(p: T => Boolean): Val[T] = ???
  def merge[S >: T](that: Val[S]): Val[S] = ???
  def flatMap[S](f: T => Val[S]): Val[S] = ???
  def flatten[S](implicit id: T => Val[S]) = flatMap(id)
  override def head = this
  override def tail = Val.Silent
}
object Val {
  object Silent extends Val[Nothing] {
    def exec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global
    def subscribe(subscriber: Subscriber[Nothing]) = {}
  }
  implicit class FromFuture[T](future: Future[T]) extends Val[T] {
    def exec: ExecutionContext = ???
    def subscribe(subscriber: Subscriber[T]) = ???
  }
}
