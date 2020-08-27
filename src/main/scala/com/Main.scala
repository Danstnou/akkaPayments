package com

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorSystem, Behavior}

object Main extends App {

  case class Run() // сообщение отправляемое системе для запуска

  def apply(): Behavior[Run] = Behaviors.receive[Run] { (context, _) =>
    val paymentsReaderActor = context.spawn(PaymentsReader(), "paymentsReader")
    paymentsReaderActor ! PaymentsReader.Run() // начинаем чтение
    Behaviors.same
  }

  ActorSystem(Main(), "system") ! Run()
}