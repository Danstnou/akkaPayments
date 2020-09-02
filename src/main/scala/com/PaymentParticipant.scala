package com

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, Behavior, PostStop, PreRestart, SupervisorStrategy }
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior }

import scala.concurrent.duration.DurationInt

object PaymentParticipant {

  case class PaymentSign(sign: String)

  sealed trait Command

  case class Payment(
    sign: PaymentSign,
    value: Long,
    participant: ActorRef[PaymentParticipant.Command],
    replyTo: ActorRef[PaymentChecker.Command])
      extends Command
  case object StopPayment    extends Command
  case object ConfirmPayment extends Command

  sealed trait Event
  case class Withdrawn(value: Long)         extends Event
  case class Added(currentPayment: Payment) extends Event
  case object StoppedPayment                extends Event
  case object ConfirmedPayment              extends Event

  final case class State(name: String, balance: Long, unconfirmedPayment: Option[Payment] = None) {
    def eventHandler(event: Event): State = event match {
      case Withdrawn(value) => copy(balance = balance - value)
      case Added(payment)   => copy(balance = balance + payment.value, unconfirmedPayment = Option(payment))
      case ConfirmedPayment => copy(unconfirmedPayment = None)
      case StoppedPayment   => copy(balance = balance - unconfirmedPayment.get.value, unconfirmedPayment = None)
    }
    def enoughMoney(value: Long) = balance - value >= 0
  }

  def apply(participantName: String, balance: Long): Behavior[Command] = Behaviors.setup { context =>
    EventSourcedBehavior(
      PersistenceId.ofUniqueId(participantName),
      State(participantName, balance),
      commandHandler(context),
      (state: State, event: Event) => state.eventHandler(event)
    ).snapshotWhen((_, _, _) => true)
      .receiveSignal {
        case (state, PostStop)   => context.log.info(s"PostStop: ${state.name}")
        case (state, PreRestart) => context.log.info(s"PreRestart: ${state.name}")
      }
      .onPersistFailure(SupervisorStrategy.restartWithBackoff(3.seconds, 30.seconds, 0.2))
  }

  def commandHandler(context: ActorContext[Command]): (State, Command) => Effect[Event, State] = { (state, command) =>
    command match {
      case payment @ Payment(PaymentSign("+"), _, _, _) => add(payment)
      case payment @ Payment(PaymentSign("-"), _, _, _) => withdraw(context, state, payment)
      case ConfirmPayment                               => finishPayment(context, state, ConfirmedPayment, "Зачислено!")
      case StopPayment                                  => finishPayment(context, state, StoppedPayment, "Платёж остановлен!")
    }
  }

  def add(payment: Payment): Effect[Event, State] =
    Effect
      .persist(Added(payment))
      .thenReply(payment.replyTo)(state => PaymentChecker.ConfirmAdded(state.name, payment))

  def withdraw(context: ActorContext[Command], state: State, payment: Payment): Effect[Event, State] = {
    def sideEffect(message: String, response: Command, newState: State) = {
      payment.participant ! response
      writeLog(context, message, payment, newState)
    }

    if (state.enoughMoney(payment.value))
      Effect
        .persist[Event, State](Withdrawn(payment.value))
        .thenRun(newState => sideEffect("Переведено!", PaymentParticipant.ConfirmPayment, newState))
        .thenStop
    else
      Effect
        .unhandled[Event, State]
        .thenRun(currentState => sideEffect("Нехватка средств!", PaymentParticipant.StopPayment, currentState))
        .thenStop
  }

  def finishPayment(context: ActorContext[Command], state: State, event: Event, msg: String): Effect[Event, State] = {
    val unconfirmedPayment = state.unconfirmedPayment.get
    Effect
      .persist[Event, State](event)
      .thenRun(newState => {
        writeLog(context, msg, unconfirmedPayment, newState)
        unconfirmedPayment.replyTo ! PaymentChecker.FinishPayment(newState.name, unconfirmedPayment)
      })
      .thenStop
  }

  def writeLog(context: ActorContext[Command], event: String, payment: Payment, state: State) =
    context.log.info(
      s"Актор: ${state.name} | $event ${state.name} -> ${payment.participant.path.name} : ${payment.value} | Баланс: ${state.balance} "
    )
}
