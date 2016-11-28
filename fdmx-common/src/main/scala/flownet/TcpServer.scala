package flownet

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.channels.spi.SelectorProvider

import monix.execution.Ack
import monix.execution.Ack.Continue
import monix.execution.Ack.Stop
import monix.execution.Cancelable
import monix.execution.Scheduler
import monix.execution.cancelables.BooleanCancelable
import monix.execution.cancelables.CompositeCancelable
import monix.execution.schedulers.ExecutionModel
import monix.reactive.MulticastStrategy
import monix.reactive.Observable
import monix.reactive.Pipe
import monix.reactive.observers.Subscriber
import scodec.bits.ByteVector

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import cats.implicits._

/**
  * Created by fabia on 28.11.2016.
  */
sealed class CloseReason()

case object Reset extends CloseReason
case object Close extends CloseReason

class ObservableSelector(log: Boolean = false) extends Observable[SelectionKey] {
  val socketSelector = SelectorProvider.provider().openSelector()

  override def unsafeSubscribeFn(subscriber: Subscriber[SelectionKey]): Cancelable = {
    val s = subscriber.scheduler
    val cancelable = BooleanCancelable()
    fastLoop(subscriber, cancelable, s.executionModel, 0)(s)
    cancelable
  }

  private[this] def reschedule(ack: Future[Ack], o: Subscriber[SelectionKey], c: BooleanCancelable, em: ExecutionModel)
                (implicit s: Scheduler): Unit =
    ack.onComplete {
      case Success(success) =>
        if (success == Continue) fastLoop(o, c, em, 0)
      case Failure(ex) =>
        s.reportFailure(ex)
      case _ =>
        () // this was a Stop, do nothing
    }

  @tailrec
  private[this] def fastLoop(o: Subscriber[SelectionKey], c: BooleanCancelable, em: ExecutionModel, syncIndex: Int)
              (implicit s: Scheduler): Unit = {


    socketSelector.selectNow()

    if(log) println("selected")

    val iter = socketSelector.selectedKeys().iterator()

    val ack = if(iter.hasNext) {
      val key = iter.next()

      println("handle "+ key)
      iter.remove()
      o.onNext(key)
    }
    else
    {
      Ack.Continue
    }

    val nextIndex =
      if (ack == Continue) em.nextFrameIndex(syncIndex)
      else if (ack == Stop) -1
      else 0

    if (nextIndex > 0)
      fastLoop(o, c, em, nextIndex)
    else if (nextIndex == 0 && !c.isCanceled)
      reschedule(ack, o, c, em)
  }
}

class TcpConnection(val incoming: Observable[Either[CloseReason, ByteVector]])

class TcpServer(address: String, port: Int) extends Observable[TcpConnection] {
  val serverChannel = ServerSocketChannel.open()

  serverChannel.configureBlocking(false)
  val isa = new InetSocketAddress(address, port)

  serverChannel.socket().bind(isa)

  val acceptSelector = new ObservableSelector
  val receiveSelector = new ObservableSelector

  serverChannel.register(acceptSelector.socketSelector, SelectionKey.OP_ACCEPT)

  def cancel(): Unit = {

  }


  override def unsafeSubscribeFn(subscriber: Subscriber[TcpConnection]): Cancelable = {
    implicit val s = subscriber.scheduler

    val multicastedReceiveSelector = receiveSelector.multicast(Pipe.publish[SelectionKey])
    multicastedReceiveSelector.connect()

    acceptSelector.filter(k => k.isValid && k.isAcceptable).foreach { key =>
      val serverSocketChannel = key.channel().asInstanceOf[ServerSocketChannel]

      val socketChannel = serverSocketChannel.accept()
      val socket = socketChannel.socket()

      socketChannel.configureBlocking(false)

      println("registered selector for read " + socketChannel)
      socketChannel.register(receiveSelector.socketSelector, SelectionKey.OP_READ)

      val receiver = multicastedReceiveSelector.filter { subKey =>
        subKey.channel() == socketChannel && subKey.isReadable
      }.map[Either[CloseReason, ByteVector]] { subKey =>
        val skChannel = subKey.channel().asInstanceOf[SocketChannel]

        val readBuffer = ByteBuffer.allocate(8192) //FIXME

        val numRead = try {
          skChannel.read(readBuffer)
        } catch {
          case _: IOException =>
            key.cancel()
            skChannel.close()
            -2
        }

        if(numRead == -1) {
          key.channel().close()
          key.cancel()
        }

        numRead match {
          case -1 => Either.left(Reset)
          case -2 => Either.left(Close)
          case _ => Either.right(ByteVector(readBuffer.array(), 0, readBuffer.position()))
        }
      }

      subscriber.onNext(new TcpConnection(receiver))
    }

    Cancelable(() => cancel())
  }
}