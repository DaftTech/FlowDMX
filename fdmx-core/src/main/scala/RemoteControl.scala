import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.PriorityBlockingQueue

import akka.NotUsed
import akka.actor.Actor
import akka.actor.Actor.Receive
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Tcp
import akka.stream.scaladsl.Tcp.IncomingConnection
import akka.util.ByteString
import fdmx.actors.AuthenticateMessage
import monix.eval.Task
import monix.reactive.Observable
import monix.execution.Scheduler.Implicits.global

import scala.collection.mutable
import scala.concurrent.Future

import concurrent.duration._

/**
  * Created by fabia on 26.11.2016.
  */
object Server {
  implicit val actorSystem = ActorSystem()
  implicit val actorMaterializer = ActorMaterializer()

  class HandleClient(connection: IncomingConnection) {
    println("Connection")

    val outputBuffer = new BoundedEventBuffer[ByteString](512)

    Observable.interval(1 second).foreach(_ => outputBuffer += ByteString.fromString("Hi!\n"))

    private val outputObs = Observable.fromIterable(outputBuffer)
    private val inputObs = Observable.fromReactivePublisher(Source.fromPublisher(outputObs.toReactivePublisher).via(connection.flow).runWith(Sink.asPublisher(true)))

    def scanPacket(bs: ByteString): ByteString = {
      bs
    }

    inputObs.map(x => { println(x); x }).scan(ByteString.empty)((x, n) => x ++ n).map(scanPacket).foreach { x =>
      println("lol rofl")
      outputBuffer += x
    }
  }

  def main(args: Array[String]): Unit = {

    Tcp().bind("0.0.0.0", 505).runForeach(new HandleClient(_))
  }
}