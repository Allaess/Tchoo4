package mw.react

import scala.concurrent.ExecutionContext

trait Var[T] extends Def[T] {
  def update(expr: => T)
  def close()
  def publish(t: T) = update(t)
  def fail(error: Throwable) = update(throw error)
}

object Var {
  def apply[T](implicit exec: ExecutionContext): Var[T] = new Def.Impl[T] with Var[T] {
    override def update(expr: => T) = super.update(expr)
    override def close() = super.close()
    override def publish(t:T) = super.publish(t)
    override def fail(error: Throwable) = super.fail(error)
  }
}
