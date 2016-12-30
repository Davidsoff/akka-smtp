package com.soff.akka.smtpserver

import java.net.InetSocketAddress

import akka.actor._
import akka.dispatch.Envelope
import akka.io.Tcp._
import akka.util.ByteString
import com.typesafe.config.ConfigFactory

class SmtpServer(connection: ActorRef, config: SmtpServer.Config) extends FSM[SmtpServer.State, SmtpServer.Data] {
  import com.soff.akka.smtpserver.SmtpProtocol._
  import com.soff.akka.smtpserver.SmtpServer._

  private val pipeline = new DelimitedTcpPipeline(ByteString("\r\n")).compose(new LoggingTcpPipeline("SERVER"))
  private val adapter = context.actorOf(Props(new TcpPipelineAdapter(connection, self, pipeline)))
  connection ! Register(adapter)
  self ! Register(self)

  when(Stopped)(PartialFunction.empty)

  when(Registring) {
    case Event(Register(_, _, _), _) ⇒
      reply(220, "localhost")
      goto(Idle)
  }

  when(Idle) {
    // http://tools.ietf.org/html/rfc5321#section-4.1.1.1
    case Event(Received(Command("HELO", remoteName)), _) ⇒
      replyOk()
      goto(ReceivingSender)

    // http://tools.ietf.org/html/rfc5321#section-4.1.1.1
    case Event(Received(Command("EHLO", remoteName)), _) ⇒
      replyOk()
      goto(ReceivingSender)
  }

  when(ReceivingSender) {
    // http://tools.ietf.org/html/rfc5321#section-4.1.1.2
    case Event(Received(Command("MAIL", from)), _) ⇒
      replyOk()
      goto(ReceivingRecipients) using Envelope(from = Some(from))
  }

  when(ReceivingRecipients) {
    // http://tools.ietf.org/html/rfc5321#section-4.1.1.3
    case Event(Received(Command("RCPT", to)), envelope: Envelope) ⇒
      replyOk()
      goto(ReceivingRecipients) using envelope.copy(to = envelope.to ++ List(to))

    // http://tools.ietf.org/html/rfc5321#section-4.1.1.4
    case Event(Received(Command("DATA", _)), envelope: Envelope) if envelope.to == Nil ⇒
      replyError("You must provide at least one recipient")
      goto(ReceivingRecipients)

    // http://tools.ietf.org/html/rfc5321#section-4.1.1.4
    case Event(Received(Command("DATA", _)), envelope: Envelope) ⇒
      reply(354, "Start mail input. end with '.\\r\\n'")
      goto(ReceivingData)
  }

  // http://tools.ietf.org/html/rfc5321#section-4.1.1.4
  when(ReceivingData) {
    case Event(Received(raw), envelope: Envelope) if raw == ByteString(".\r\n") ⇒
      val result = envelope
      processMail(result)
      replyOk()
      goto(ReceivingSender) using Empty
    case Event(Received(raw), envelope: Envelope) ⇒
      goto(ReceivingData) using envelope.copy(body = envelope.body ++ raw)
  }

  whenUnhandled {
    // http://tools.ietf.org/html/rfc5321#section-4.1.1.6
    case Event(Received(Command("VRFY", mailbox)), _) ⇒
      // TODO: check mailbox
      replyOk()
      stay()

    // http://tools.ietf.org/html/rfc5321#section-4.1.1.9
    case Event(Received(Command("NOOP", _)), _) ⇒
      replyOk()
      stay()

    // http://tools.ietf.org/html/rfc5321#section-4.1.1.5
    case Event(Received(Command("RSET", _)), _) ⇒
      replyOk()
      goto(ReceivingSender) using Empty

    // http://tools.ietf.org/html/rfc5321#section-4.1.1.10
    case Event(Received(Command("QUIT", _)), _) ⇒
      reply(221, "OK")
      adapter ! Close
      goto(Stopped)

    case Event(_: ConnectionClosed, _) ⇒
      log.debug("Connection closed")
      context.stop(self)
      goto(Stopped)

    case Event(e, s) ⇒
      log.warning("received unhandled request {} in state {}/{}", e, stateName, s)
      replyError()
      stay()
  }

  startWith(Registring, Empty)
  initialize()

  def reply(code: Int, message: String = "") = adapter ! Write(Reply(code, message))
  def replyOk(message: String = "OK") = reply(250, message)
  def replyError(message: String = "Error") = reply(500, message)

  def processMail(envelope: Envelope) = {
    logMail(envelope)

  }

  def logMail(envelope: Envelope) = {
    log.info("Got mail")
    log.info("From {}", envelope.from)
    log.info("To {}", envelope.to)
    log.info("Body\n{}", envelope.body.utf8String)
  }
}

object SmtpServer {
  sealed trait State
  case object Stopped extends State
  case object Registring extends State
  case object Idle extends State
  case object ReceivingSender extends State
  case object ReceivingRecipients extends State
  case object ReceivingData extends State

  sealed trait Data
  case object Empty extends Data
  case class Envelope(from: Option[String] = None, to: List[String] = Nil, body: ByteString = ByteString.empty) extends Data

  case class Config(bind: InetSocketAddress, banner: String)
  object Config {
    def load(): Config = {
      Config(
        bind = new InetSocketAddress("127.0.0.1", 12345),
        banner = "Awesome mailserver"
      )
    }
  }
}
