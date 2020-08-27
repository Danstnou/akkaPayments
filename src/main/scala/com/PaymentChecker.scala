package com

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import com.typesafe.config.{Config, ConfigFactory}

import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

object PaymentChecker {

  // Протокол
  case class CheckPayment(payment: String)

  // Поведение
  def apply(): Behavior[CheckPayment] = Behaviors.setup { context =>
    val logIncorrectPayment = context.spawn(LogIncorrectPayment(), "logIncorrectPayment")
    val children: Map[String, ActorRef[PaymentParticipant.Event]] = Map() // хранение акторов-участников для удаления после платежа

    val config: Config = ConfigFactory.load()
    val maskPayment = config.getString("app.maskPayment").r
    val balance = config.getString("app.balance").toLong // стд баланс для всех акторов

    receiveCheckPayment(logIncorrectPayment, children, maskPayment, balance) // возвращаем поведение приёма платежей
  }

  // Принятие сообщений CheckPayment
  def receiveCheckPayment(logIncorrectPayment: ActorRef[LogIncorrectPayment.IncorrectPayment],
                          children: Map[String, ActorRef[PaymentParticipant.Event]],
                          maskPayment: Regex,
                          balance: Long): Behavior[CheckPayment] = Behaviors.receive { (context, checkPayment) =>

    Try {
      val maskPayment(participant1name, _, participant2name, _, count) = checkPayment.payment // разбираем строку платежа по образцам
      (participant1name, participant2name, count.toLong)
    }
    match {
      case Success((participant1name, participant2name, count)) =>
        val (participant1Actor, participant2Actor) = getOrCreateActorsParticipants(context, children, (participant1name, participant2name), balance)
        tellPayments(participant1Actor, participant2Actor, count) // отправляем акторам сообщение Payment
        val newChildren = refreshChildren(children, (participant1Actor, participant2Actor)) // обновляем Map с акторами
        receiveCheckPayment(logIncorrectPayment, newChildren, maskPayment, balance) // изменяем поведение с обновлённым newChildren

      case Failure(exception) => // неудовлетворение маске платежа
        logIncorrectPayment ! LogIncorrectPayment.IncorrectPayment(exception.getMessage) // логируем некорректность
        Behaviors.same // и оставляем пред. поведение с намотанными акторами
    }
  }

  def getOrCreateActorsParticipants(context: ActorContext[CheckPayment],
                                    children: Map[String, ActorRef[PaymentParticipant.Event]],
                                    participantsNames: (String, String),
                                    balance: Long
                              ): (ActorRef[PaymentParticipant.Event], ActorRef[PaymentParticipant.Event]) = {
    // получаем актор если есть, иначе создаём его. Возвращаем пару акторов
    val participant1 = children.getOrElse(participantsNames._1, context.spawn(PaymentParticipant(balance), participantsNames._1))
    val participant2 = children.getOrElse(participantsNames._2, context.spawn(PaymentParticipant(balance), participantsNames._2))

    (participant1, participant2)
  }

  def refreshChildren(children: Map[String, ActorRef[PaymentParticipant.Event]],
                      participants: (ActorRef[PaymentParticipant.Event], ActorRef[PaymentParticipant.Event])
                     ): Map[String, ActorRef[PaymentParticipant.Event]] = {

    children + (participants._1.path.name -> participants._1) + (participants._2.path.name -> participants._2)
  }

  def tellPayments(participant1Actor: ActorRef[PaymentParticipant.Event],
                   participant2Actor: ActorRef[PaymentParticipant.Event],
                   count: Long
                  ): Unit = {

    participant1Actor ! PaymentParticipant.Payment(PaymentParticipant.PaymentSign("-"), count, participant2Actor)
    participant2Actor ! PaymentParticipant.Payment(PaymentParticipant.PaymentSign("+"), count, participant1Actor)

    // КАК ТОЛЬКО ОБА ВЕРНУТ ОТВЕТ (значит платёж с обоих сторон обработан) - УДАЛИТЬ ИХ
    // И стратегию супервизора при падении акторов
  }
}