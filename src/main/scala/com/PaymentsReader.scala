package com

import java.nio.file.{Files, Path, Paths}

import akka.actor.typed
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Behavior}
import akka.stream.scaladsl.{FileIO, Framing, Sink, Source}
import akka.util.ByteString
import com.typesafe.config.ConfigFactory

import scala.jdk.CollectionConverters.IteratorHasAsScala

object PaymentsReader {

  case class Run()

  def apply(): Behavior[Run] = Behaviors.setup { context =>

    val config = ConfigFactory.load()
    val catalog = config.getString("app.catalog")
    val maskFile = config.getString("app.maskFile")

    val files = Files
      .list(Paths.get(catalog))
      .filter(_.getFileName.toString.matches(maskFile)) // по маске имени
      .iterator().asScala
    val paymentChecker = context.spawn(PaymentChecker(), "paymentChecker")

    implicit val system: ActorSystem[Nothing] = context.system

    Behaviors.receiveMessage(_ => {
      tellPaymentsForPaymentChecker(files, paymentChecker)
      Behaviors.same
    })
  }

  def tellPaymentsForPaymentChecker(files: Iterator[Path], paymentChecker: typed.ActorRef[PaymentChecker.CheckPayment])
                                   (implicit actorSystem: ActorSystem[Nothing]): Unit = {

    val source = Source.fromIterator(() => files) // источник из файлов

    source.flatMapConcat({
      FileIO
        .fromPath(_) // превращаем каждый файл в источник
        .via(Framing.delimiter(ByteString("\r\n"), 256, allowTruncation = true)) // берём каждую строчку
    }).runWith(Sink.foreach[ByteString] { byteString =>
      paymentChecker ! PaymentChecker.CheckPayment(byteString.utf8String)
    })
  }

}