package com

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}

object PaymentParticipant {

  case class PaymentSign(sign: String)

  // Протокол
  sealed trait Event

  case class Payment(sign: PaymentSign, value: Long, participant: ActorRef[PaymentParticipant.Event]) extends Event // платёж
  case class StopPayment(payment: Payment) extends Event // отмена платежа

  // Поведение
  def apply(balance: Long): Behavior[Event] = receiveEvent(balance)

  def receiveEvent(balance: Long): Behavior[Event] = Behaviors.receive { (context, event) =>
    event match {
      case payment @ Payment(PaymentSign(sign), value, participant) =>
        val newBalance = refreshBalance(sign, balance, value)

        if (newBalance > balance) {
          context.log.info(stringPayment(context.self.path.name, "Получено!", participant.path.name, value, balance, newBalance))
        }
        else if (balance == newBalance) {
          context.log.info(stringPayment(context.self.path.name, "Нехватка баланса!", participant.path.name, value, balance, newBalance))
          participant ! PaymentParticipant.StopPayment(payment.copy(participant = context.self)) // себя в участники
        }
        else { // if(новыйБаланс < старого) и >= 0
          context.log.info(stringPayment(context.self.path.name, "Переведено!", participant.path.name, value, balance, newBalance))
        }

        receiveEvent(newBalance) // обновляем поведение с новым балансом

      case StopPayment(payment) =>
        val value = payment.value
        val newBalance = balance - value // отменяем платёж
        context.log.info(stringPayment(context.self.path.name, "Платёж отменён!", payment.participant.path.name, value, balance, newBalance))

        receiveEvent(newBalance) // обновляем поведение с новым балансом
    }
  }

  def refreshBalance(sign: String, balance: Long, value: Long): Long = {
    // если происходит вычитание такого значения, что баланса не хватает - баланс остается прежним
    def decreasingBalance(balance: Long, value: Long): Long = {
      val newBalance = balance - value
      if (newBalance >= 0) newBalance else balance
    }

    if (sign == "-") decreasingBalance(balance, value) else balance + value
  }

  def stringPayment(self: String, message: String, participant: String, value: Long, oldBalance: Long, newBalance: Long): String =
    s"Баланс для: $self | Событие: $message Сколько: $value Участник: $participant. Старый баланс: $oldBalance. Новый баланс: $newBalance"
}