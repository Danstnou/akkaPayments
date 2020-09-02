package com

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, PostStop, PreRestart, SupervisorStrategy}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import com.typesafe.config.Config

import scala.concurrent.duration.DurationInt
import scala.util.matching.Regex

object PaymentChecker {

  final case class Transaction(from: String, to: String, value: Long) {
    override def toString: String = s"| $from -> $to : $value |"
  }

  sealed trait Command
  case class CheckPayment(payment: String)                                           extends Command
  case class ConfirmAdded(to: String, payment: PaymentParticipant.Payment)           extends Command
  case class FinishPayment(participant: String, payment: PaymentParticipant.Payment) extends Command

  sealed trait Event
  case class CheckedPayment(transaction: Transaction)                                  extends Event
  case class MoneyAdded(to: String, payment: PaymentParticipant.Payment)               extends Event
  case class FinishedPayment(participant: String, payment: PaymentParticipant.Payment) extends Event

  final case class State(
    logIncorrectPayment: ActorRef[LogIncorrectPayment.Command],
    maskPayment: Regex,
    balance: Long,
    activeParticipants: Map[String, ActorRef[PaymentParticipant.Command]] = Map()) {

    def addParticipants(
      name1: String,
      actor1: ActorRef[PaymentParticipant.Command],
      name2: String,
      actor2: ActorRef[PaymentParticipant.Command]
    ) = copy(activeParticipants = activeParticipants + (name1 -> actor1) + (name2 -> actor2))

    def finishedPayment(name1: String, name2: String) = copy(activeParticipants = activeParticipants - name1 - name2)

    def nowInTransaction(participant: String): Boolean = activeParticipants.contains(participant)
  }

  def apply(config: Config): Behavior[Command] = Behaviors.setup { context: ActorContext[Command] =>
    val logIncorrectPayment = context.spawn(LogIncorrectPayment(), "logIncorrectPayment")

    val maskPayment = config.getString("app.maskPayment").r
    val balance     = config.getLong("app.balance")

    EventSourcedBehavior(
      PersistenceId.ofUniqueId("paymentChecker"),
      State(logIncorrectPayment, maskPayment, balance),
      commandHandler,
      eventHandler(context)
    ).snapshotWhen((_, _, _) => true)
      .receiveSignal{
        case (_, PostStop) => context.log.info(s"PostStop: paymentChecker")
        case (_, PreRestart) => context.log.info(s"PreRestart: paymentChecker")
      }
      .onPersistFailure(SupervisorStrategy.restartWithBackoff(3.seconds, 30.seconds, 0.2))
  }

  def commandHandler: (State, Command) => Effect[Event, State] = { (state, command) =>
    command match {
      case CheckPayment(state.maskPayment(from, _, to, _, _))
          if state.nowInTransaction(from) || state.nowInTransaction(to) =>
        Effect.stash()

      case CheckPayment(state.maskPayment(from, _, to, _, value)) =>
        Effect.persist[Event, State](CheckedPayment(Transaction(from, to, value.toLong)))

      case ConfirmAdded(whomAdded, payment) => Effect.persist[Event, State](MoneyAdded(whomAdded, payment))

      case FinishPayment(participant, payment) =>
        Effect.persist[Event, State](FinishedPayment(participant, payment)).thenUnstashAll()

      case CheckPayment(payment) => incorrectPayment(payment)
    }
  }

  def eventHandler(context: ActorContext[Command]): (State, Event) => State = { (newState, event) =>
    event match {
      case CheckedPayment(transaction @ Transaction(from, to, value)) =>
        context.log.info(s"НАЧАТА ТРАНЗАКЦИЯ: $transaction")
        def spawn(name: String) = context.spawn(
          Behaviors
            .supervise(PaymentParticipant(name, newState.balance))
            .onFailure(SupervisorStrategy.restartWithBackoff(3.seconds, 30.seconds, randomFactor = 0.2)),
          name
        )
        val (fromActor, toActor) = (spawn(from), spawn(to))
        toActor ! PaymentParticipant.Payment(PaymentParticipant.PaymentSign("+"), value, fromActor, context.self)
        newState.addParticipants(from, fromActor, to, toActor)

      case MoneyAdded(whomAdded, payment) =>
        val from: ActorRef[PaymentParticipant.Command] = payment.participant
        val to: ActorRef[PaymentParticipant.Command]   = newState.activeParticipants(whomAdded)
        from ! PaymentParticipant.Payment(PaymentParticipant.PaymentSign("-"), payment.value, to, context.self)
        newState

      case FinishedPayment(whomAdded, payment) => newState.finishedPayment(whomAdded, payment.participant.path.name)
    }
  }

  def incorrectPayment(payment: String): Effect[Event, State] =
    Effect.unhandled.thenRun(state => state.logIncorrectPayment ! LogIncorrectPayment.IncorrectPayment(payment))
}
