package com

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

/**
 * Актор записывает в лог сообщение о некорректном переводе
 */

object LogIncorrectPayment {
  /**
   * Сообщения
   */
  case class IncorrectPayment(error: String)

  def apply(): Behavior[IncorrectPayment] = Behaviors.receive { (context, message) =>
    context.log.error(message.error)
    Behaviors.same
  }

}