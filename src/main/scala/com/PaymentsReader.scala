package com

import java.nio.file.{ Files, Path, Paths }

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, SupervisorStrategy }
import akka.stream.scaladsl.{ FileIO, Framing, Sink, Source }
import akka.stream.{ ActorAttributes, Supervision }
import akka.util.ByteString
import com.typesafe.config.Config

import scala.collection.JavaConverters.asScalaIterator
import scala.concurrent.duration.DurationInt

object PaymentsReader {
  sealed trait Command

  case object Run extends Command

  def apply(config: Config): Behavior[Command] = Behaviors.setup { context =>
    implicit val system: ActorSystem[Nothing] = context.system
    val paymentChecker = context.spawn(
      Behaviors
        .supervise(PaymentChecker(config))
        .onFailure(SupervisorStrategy.restartWithBackoff(3.seconds, 30.seconds, randomFactor = 0.2)),
      "paymentChecker"
    )

    val catalog  = config.getString("app.catalog")
    val maskFile = config.getString("app.maskFile")

    Behaviors.receiveMessage {
      case Run =>
        val files = Files
          .list(Paths.get(catalog))
          .filter(_.getFileName.toString.matches(maskFile))
          .iterator()

        tellPayments(asScalaIterator(files), paymentChecker)
        Behaviors.same
    }
  }

  def tellPayments(
    files: Iterator[Path],
    paymentChecker: ActorRef[PaymentChecker.Command]
  )(implicit actorSystem: ActorSystem[Nothing]
  ): Unit = {

    Source
      .fromIterator(() => files)
      .flatMapConcat {
        FileIO.fromPath(_).via(Framing.delimiter(ByteString("\r\n"), 128, allowTruncation = true))
      }
      .to(Sink.foreach[ByteString] { byteString =>
        paymentChecker ! PaymentChecker.CheckPayment(byteString.utf8String)
      })
      .withAttributes(ActorAttributes.supervisionStrategy(_ => Supervision.resume))
      .run()
  }
}
