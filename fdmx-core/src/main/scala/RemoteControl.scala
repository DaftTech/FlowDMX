import flownet.TcpServer
import monix.execution.Ack
import monix.execution.Scheduler.Implicits.global

object Server {
  def main(args: Array[String]): Unit = {
    val tcpServer = new TcpServer("0.0.0.0", 505)

    tcpServer.foreach{ con =>
      println("connection")

      con.incoming.foreach { packet =>
        println("received " + packet.fold(fa => "fail", fb => fb.toString()))
      }
    }

    while(true) {

    }
  }
}