/*
 * Copyright 2016 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.circuitbreaker

import java.lang.System._
import java.util.concurrent.atomic.AtomicInteger

import org.scalacheck.commands.Commands
import org.scalacheck.{Gen, Prop}
import org.scalatest.concurrent.ScalaFutures
import uk.gov.hmrc.circuitbreaker.{State => CbState}

import scala.concurrent.Future
import scala.util.Try

import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by dabd on 31/01/16.
  */

object CommandsCircuitBreaker extends org.scalacheck.Properties("CommandsCircuitBreaker") {

  property("circuitbreakerspecification") = CircuitBreakerSpecification.property()

}

object CircuitBreakerSpecification extends Commands with ScalaFutures {

  val fiveMinutes: Int = 5 * 60 * 1000
  val fourCalls: Int = 3
  val serviceName = "SomeServiceName"

  val defaultConfig = CircuitBreakerConfig(serviceName, Some(fourCalls), Some(fiveMinutes), Some(fiveMinutes))
  val defaultExceptions: Throwable => Boolean = (_ => true)

  val circuitBreaker = new CircuitBreaker(defaultConfig, defaultExceptions)

  override type Sut = CircuitBreaker

  sealed trait State

  sealed trait TimedState extends State {
    def duration: Int

    val periodStart = currentTimeMillis

    def periodElapsed: Boolean = currentTimeMillis - periodStart > duration
  }

  sealed trait CountingState extends State {
    def startCount: Int

    private val count = new AtomicInteger(startCount)

    def needsStateChangeAfterIncrement = count.incrementAndGet >= defaultConfig.numberOfCallsToTriggerStateChange

    def needsStateChange = count.get >= defaultConfig.numberOfCallsToTriggerStateChange
  }

  object Healthy extends State {
  }

  class Unstable extends State with TimedState with CountingState {
    lazy val startCount = 1
    lazy val duration = defaultConfig.unstablePeriodDuration
  }

  class Trial extends State with CountingState {
    lazy val startCount = 0
  }

  class Unavailable extends State with TimedState {
    lazy val duration = defaultConfig.unavailablePeriodDuration
  }

  override def destroySut(sut: Sut): Unit = ()

  override def initialPreCondition(state: State): Boolean = state == Healthy

  override def canCreateNewSut(newState: State, initSuts: Traversable[State], runningSuts: Traversable[Sut]): Boolean = true

  override def genInitialState: Gen[State] = Healthy

  override def newSut(state: State): Sut = new CircuitBreaker(defaultConfig, defaultExceptions)

  override def genCommand(state: State): Gen[Command] = Gen.oneOf(SuccessCall, FailureCall)

  case object SuccessCall extends Command {
    override type Result = CbState

    override def preCondition(state: State): Boolean = true

    override def postCondition(state: State, result: Try[Result]): Prop = state match {
      case Healthy => result.get.name == "HEALTHY"
      case s: Unstable => result.get.name == (if (s.periodElapsed) "HEALTHY" else "UNSTABLE")
      case s: Trial => result.get.name == (if (s.needsStateChange) "HEALTHY" else "TRIAL")
      case s: Unavailable => result.get.name == (if (s.periodElapsed) "TRIAL" else "UNAVAILABLE")
    }

    override def run(sut: CircuitBreaker): CbState = sut.synchronized { // Needs synchronized?
      whenReady(sut.invoke(Future.successful(true))) {
        _ => sut.currentState
      }
    }

    override def nextState(state: State): State = state match {
      case Healthy => Healthy
      case s: Unstable => if (s.periodElapsed) Healthy else s
      case s: Trial => if (s.needsStateChangeAfterIncrement) Healthy else s
      case s: Unavailable => if (s.periodElapsed) new Trial else s
    }
  }

  case object FailureCall extends Command {
    override type Result = CbState

    override def preCondition(state: State): Boolean = true

    override def postCondition(state: State, result: Try[Result]): Prop = state match {
      case Healthy => result.get.name == (if (defaultConfig.numberOfCallsToTriggerStateChange > 1) "UNSTABLE" else "UNAVAILABLE")
      case s: Unstable => result.get.name ==
        (if (s.periodElapsed) "UNSTABLE"
        else if (s.needsStateChange) "UNAVAILABLE" else "UNSTABLE")
      case s: Trial => result.get.name == "UNAVAILABLE"
      case s: Unavailable => result.get.name == "UNAVAILABLE"
    }

    override def run(sut: CircuitBreaker): CbState = sut.synchronized {  // Needs synchronized?
      sut.invoke(Future.failed(new RuntimeException("some exception"))).failed.futureValue
      sut.currentState
    }

    override def nextState(state: State): State = {
      state match {
        case Healthy =>
          if (defaultConfig.numberOfCallsToTriggerStateChange > 1) new Unstable
          else new Unavailable
        case s: Unstable =>
          if (s.periodElapsed) new Unstable
          else if (s.needsStateChangeAfterIncrement) new Unavailable
          else s
        case s: Trial => new Unavailable
        case s: Unavailable => s
      }
    }
  }

}
