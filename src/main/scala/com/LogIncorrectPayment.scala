package com

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

object LogIncorrectPayment {

  sealed trait Command
  case class IncorrectPayment(error: String) extends Command

  def apply(): Behavior[Command] = Behaviors.receive { (context, message) =>
    message match {
      case IncorrectPayment(error) => context.log.info(s"Некорректный платёж: $error")
    }
    Behaviors.same
  }
}
