/*
*************************************************************************************
* Copyright 2019 Normation SAS
*************************************************************************************
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
*************************************************************************************
*/


/*
 * This class provides common usage for Zio
 */

package process.pure

import zio._
import zio.syntax._
import process.pure.errors._
import process.pure.zioruntime.ZioRuntime
import zio.blocking.Blocking
import zio.internal.{Platform, PlatformLive}

/**
 * This is our based error for Rudder. Any method that can
 * error should return that RudderError type to allow
 * seemless interaction between modules.
 * None the less, all module should have its own domain error
 * for meaningful semantic intra-module.
 */
object errors {

  /*
   * Two methods which helps to transform effect to `UIO[Unit]` (ie don't care
   * about the result type, and manage all errors in some way).
   * This is particularly needed in `Bracket` construction where the finalizer
   * must be of that type.
   */
  def effectUioUnit[A](effect: => A): UIO[Unit] = {
    def printError(t: Throwable): UIO[Unit] = {
      val print = (s:String) => IO.effect(System.err.println(s))
      //here, we must run.unit, because if it fails we can't do much more (and the app is certainly totally broken)
      (print(s"${t.getClass.getName}:${t.getMessage}") *> IO.foreach(t.getStackTrace)(st => print(st.toString))).run.unit
    }
    effectUioUnit(printError(_))(effect)
  }
  def effectUioUnit[A](error: Throwable => UIO[Unit])(effect: => A): UIO[Unit] = {
    Task(effect).unit.catchAll(error)
  }

  /*
   * Our result types are isomorphique to a disjoint RudderError | A
   * one. We have two case: one for which we are sure that all
   * operation are pure and don't modelize effects (that can be fully
   * reiffied at compule time), and one for effect encapsulation (that need to
   * be runned to know the result).
   */
  type PureResult[A] = Either[RudderError, A]
  type IOResult[A] = ZIO[ZEnv, RudderError, A]

  /*
   * An object that provides utility methods to import effectful
   * methods into rudder IOResult type.
   * By default, we consider that all imports have blocking
   * effects, and need to be run on an according threadpool.
   * If you want to import non blocking effects (but really, in
   * that case, you should just use `PureResult`), you can
   * use `IOResult.effectTotal`).
   */
  object IOResult {
    def effectNonBlocking[A](error: String)(effect: => A): IO[SystemError, A] = {
      IO.effect(effect).mapError(ex => SystemError(error, ex))
    }
    def effectNonBlocking[A](effect: => A): IO[SystemError, A] = {
      this.effectNonBlocking("An error occured")(effect)
    }
    def effect[A](error: String)(effect: => A): ZIO[Blocking, SystemError, A] = {
      ZioRuntime.effectBlocking(effect).mapError(ex => SystemError(error, ex))
    }
    def effect[A](effect: => A): ZIO[Blocking, SystemError, A] = {
      this.effect("An error occured")(effect)
    }
    def effectM[A](error: String)(ioeffect: IOResult[A]): IOResult[A] = {
      IO.effect(ioeffect).foldM(
        ex  => SystemError(error, ex).fail
      , res => res
      )
    }
    def effectM[A](ioeffect: IOResult[A]): IOResult[A] = {
      effectM("An error occured")(ioeffect)
    }
  }

  object RudderError {

    /*
     * Display information about an exception of interest for the developpers without being
     * too nasty for users.
     */
    def formatException(cause: Throwable): String = {
      // display at max 3 stack trace from 'com.normation'. That should give plenty information for
      // dev, which are relevant to understand where the problem is, and without destroying logs
      val stack = cause.getStackTrace.filter(_.getClassName.startsWith("com.normation")).take(3).map(_.toString).mkString("\n -> ", "\n -> ", "")
      s"${cause.getClass.getName}: ${cause.getMessage} ${stack}"
    }


  }

  trait RudderError {
    // All error have a message which explains what cause the error.
    def msg: String

    // All error can have their message printed with the class name for
    // for context.
    def fullMsg = this.getClass.getSimpleName + ": " + msg
  }

  // a common error for system error not specificaly bound to
  // a domain context.
  final case class SystemError(msg: String, cause: Throwable) extends RudderError {
    override def fullMsg: String = super.fullMsg + s"; cause was: ${RudderError.formatException(cause)}"
  }

  // a generic error to tell "I wasn't expecting that value"
  final case class Unexpected(msg: String) extends RudderError

  // a generic error to tell "there is some (business logic related) inconsistancy"
  final case class Inconsistancy(msg: String) extends RudderError

  trait BaseChainError[E <: RudderError] extends RudderError {
    def cause: E
    def hint: String
    def msg = s"${hint}; cause was: ${cause.fullMsg}"
  }

  final case class Chained[E <: RudderError](hint: String, cause: E) extends BaseChainError[E] {
    override def fullMsg: String = msg
  }

  /*
   * Chain multiple error. You will loose the specificity of the
   * error type doing so.
   */
  implicit class IOChainError[R, E <: RudderError, A](res: ZIO[R, E, A]) {
    def chainError(hint: String): ZIO[R, RudderError, A] = res.mapError(err => Chained(hint, err))
  }

  /*
   * A mapper from PureResult to IOResult
   */
  implicit class PureToIoResult[A](res: PureResult[A]) {
    def toIO: IOResult[A] = ZIO.fromEither(res)
  }

  // not optional - mandatory presence of an object
  implicit class OptionToIoResult[A](res: Option[A]) {
    def notOptional(error: String) = res match {
      case None    => Inconsistancy(error).fail
      case Some(x) => x.succeed
    }
  }

  // also with the flatmap included to avoid a combinator
  implicit class MandatoryOptionIO[R, E <: RudderError, A](res: ZIO[R, E, Option[A]]) {
    def notOptional(error: String) = res.flatMap( _.notOptional(error))
  }

}

object zioruntime {

  val currentTimeMillis = UIO.effectTotal(System.currentTimeMillis())

  /*
   * Default ZIO Runtime used everywhere.
   */
  object ZioRuntime {
    /*
     * Internal runtime. You should not access it within rudder.
     * If you need to use it for "unsafeRun", you should alway pin the
     * IO into an async thread pool to avoid deadlock in case of
     * a hierarchy of calls.
     */
    val internal = new DefaultRuntime() {
      override val Platform: Platform = PlatformLive.Benchmark
    }

    /*
     * use the blocking thread pool provided by that runtime.
     */
    def blocking[E,A](io: ZIO[Any,E,A]): ZIO[Blocking, E, A] = {
      _root_.zio.blocking.blocking(io)
    }

    def effectBlocking[A](effect: => A): ZIO[Blocking, Throwable, A] = {
      _root_.zio.blocking.effectBlocking(effect)
    }

    def runNow[A](io: IOResult[A]): A = {
      internal.unsafeRunSync(io).fold(cause => throw cause.squashWith(err => new RuntimeException(err.fullMsg)), a => a)
    }

    /*
     * Run now, discard result, log error if any
     */
    def runNowLogError[A](logger: RudderError => Unit)(io: IOResult[A]): Unit = {
      runNow(io.unit.either).swap.foreach(err =>
        logger(err)
      )
    }

    /*
     * An unsafe run that is always started on a growing threadpool and its
     * effect marked as blocking.
     */
    def unsafeRun[E, A](zio: ZIO[Any, E, A]): A = internal.unsafeRun(blocking(zio))

    def environment = internal.Environment
  }

  /*
   * When porting a class is too hard
   */
  implicit class UnsafeRun[A](io: IOResult[A]) {
    def runNow: A = ZioRuntime.runNow(io)
    def runNowLogError(logger: RudderError => Unit): Unit = ZioRuntime.runNowLogError(logger)(io)
  }

}
