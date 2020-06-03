package mw.react

trait Subscriber[-T] {
  def onCancel(action: => Any)
  def published(t: T)
  def closed()
  def failed(error: Throwable)
}
