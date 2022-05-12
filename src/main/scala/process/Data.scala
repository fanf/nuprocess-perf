package process

/*
 * The goal of that file is to give a simple abstraction to run hooks in
 * rudder.
 *
 * Hooks are stored in a directory. All hooks from a directory are
 * run sequentially, so that side effects from one hook can be used
 * in the following if the user want so.
 * A hook which fails stop the process and error from stderr are used
 * for the reason of the failing.
 * A failed hook is decided by the return code: 0 mean success, anything
 * else is a failure.
 *
 * Hooks are asynchronously executed by default, in a Future.
 */

/*
 * Hooks are group in "set". We run all the hooks
 * from the same set with the same set of envVariables.
 * The hooks are executed in the order of the list.
 */

final case class Hooks(basePath: String, hooksFile: List[String])

/**
  * Hook env are pairs of environment variable name <=> value
  */
final case class HookEnvPair(name: String, value: String) {
  def show = s"[${name}:${value}]"
}

final case class HookEnvPairs(values: List[HookEnvPair]) extends AnyVal {
  //shortcut to view envVariables as a Map[String, String]
  def toMap = values.map(p => (p.name, p.value)).toMap

  def add(other: HookEnvPairs) = HookEnvPairs(this.values ::: other.values)

  /**
    * Formatted string
    * [key1:val1][key2:val2]...
    */
  def show: String = values.map(_.show).mkString(" ")
}

object HookEnvPairs {
  def toListPairs(values: (String, String)*) =
    values.map(p => HookEnvPair(p._1, p._2)).toList

  def build(values: (String, String)*) = {
    HookEnvPairs(toListPairs(values: _*))
  }
}

trait LogLevel {
  final val LOGLEVEL = 3 //1 = trace, 2 = debug, 3 = info, 4 = warn
}

/**
  * Loggger for hooks
  */
trait MyLogger extends LogLevel {
  def trace(msg: => String): Unit = if (LOGLEVEL <= 1) {
    println(s"TRACE: $msg")
  }
  def debug(msg: => String): Unit = if (LOGLEVEL <= 2) {
    println(s"DEBUG: $msg")
  }
  def warn(msg: => String): Unit = if (LOGLEVEL <= 4) {
    println(s"WARN:  $msg")
  }
}
object HooksLogger extends MyLogger {
  object LongExecLogger extends MyLogger
}

sealed trait HookReturnCode {
  def code: Int
  def stdout: String
  def stderr: String
  def msg: String
}

object HookReturnCode {
  sealed trait Success extends HookReturnCode
  sealed trait Error extends HookReturnCode

  //special return code
  final case class Ok(stdout: String, stderr: String) extends Success {
    val code = 0
    val msg = ""
  }
  final case class Warning(
      code: Int,
      stdout: String,
      stderr: String,
      msg: String
  ) extends Success
  final case class ScriptError(
      code: Int,
      stdout: String,
      stderr: String,
      msg: String
  ) extends Error
  final case class SystemError(msg: String) extends Error {
    val stderr = ""
    val stdout = ""
    val code = Int.MaxValue // special value out of bound 0-255, far in the "reserved" way
  }

  //special return code 100: it is a stop state, but in some case can lead to
  //an user message that is tailored to explain that it is not a hook error)
  final case class Interrupt(msg: String, stdout: String, stderr: String)
      extends Error {
    val code = Interrupt.code
  }
  object Interrupt {
    val code = 100
  }
}
