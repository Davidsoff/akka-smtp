package com.soff.akka.smtpserver

import akka.actor._
import akka.io.Tcp._

class EchoTcpHandler(connection: ActorRef) extends Actor with ActorLogging{
  connection ! Register
  log.info("echo handler started with connection: {}", connection)
  def receive = {
    case Received(data) ⇒
      log.debug("message received: {}", data)
      connection ! Write(data)
  }
}
