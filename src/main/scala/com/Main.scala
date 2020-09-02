package com

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorSystem, Behavior }
import com.typesafe.config.ConfigFactory

object Main extends App {

  def apply(): Behavior[Nothing] = Behaviors.setup[Nothing] { context =>
    val config              = ConfigFactory.load()
    val paymentsReaderActor = context.spawn(PaymentsReader(config), "paymentsReader")
    paymentsReaderActor ! PaymentsReader.Run

    Behaviors.ignore
  }

  ActorSystem[Nothing](Main(), "system")
}
