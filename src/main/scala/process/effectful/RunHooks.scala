package process.effectful

import java.io.File

import monix.execution.ExecutionModel

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal
import process._

object RunHooks {
  // hook own threadpool
  implicit val executor = monix.execution.Scheduler.io("rudder-hooks-exec", executionModel = ExecutionModel.AlwaysAsyncExecution)

  /**
   * Runs a list of hooks. Each hook is run sequencially (so that
   * the user can expects one hook side effects to be used in the
   * next one), but the whole process is asynchronous.
   * If one hook fails, the whole list fails.
   *
   * The semantic of return codes is:
   * - < 0: success (we should never have a negative returned code, but java int are signed)
   * - 0: success
   * - 1-31: errors. These code stop the hooks pipeline, and the generation is on error
   * - 32-63: warnings. These code log a warning message, but DON'T STOP the next hook processing
   * - 64-255: reserved. For now, they will be treat as "error", but that behaviour can change any-time
   *            without notice.
   * - > 255: should not happen, but treated as reserved.
   *
   */
  def asyncRun(hooks: Hooks, hookParameters: HookEnvPairs, envVariables: HookEnvPairs): Future[HookReturnCode] = {

    /*
     * We can not use Future.fold, because it execute all scripts
     * in parallel and then combine their results. Our semantic
     * is execute script one after the other, combining at each
     * step.
     * But we still want the whole operation to be non-bloking.
     */
    import HookReturnCode._
    hooks.hooksFile.foldLeft(Future(Ok("",""):HookReturnCode)) { case (previousFuture, nextHookName) =>
      val path = hooks.basePath + File.separator + nextHookName
      previousFuture.flatMap {
        case x: Success =>
          HooksLogger.debug(s"Run hook: '${path}' with environment parameters: ${hookParameters.show}")
          HooksLogger.trace(s"System environment variables: ${envVariables.show}")
          val env = envVariables.add(hookParameters)
          RunNuCommand.run(Cmd(path, Nil, env.toMap)).map { result =>
            lazy val msg = s"Exit code=${result.code} for hook: '${path}'."

            HooksLogger.trace(s"  -> results: ${msg}")
            HooksLogger.trace(s"  -> stdout : ${result.stdout}")
            HooksLogger.trace(s"  -> stderr : ${result.stderr}")
            if(       result.code == 0 ) {
              Ok(result.stdout, result.stderr)
            } else if(result.code < 0 ) { // this should not happen, and/or is likely a system error (bad !#, etc)
              //using script error because we do have an error code and it can help in some case
              ScriptError(result.code, result.stdout, result.stderr, msg)
            } else if(result.code >= 1  && result.code <= 31 ) { // error
              ScriptError(result.code, result.stdout, result.stderr, msg)
            } else if(result.code >= 32 && result.code <= 64) { // warning
              HooksLogger.warn(msg)
              if (result.stdout.size > 0)
                HooksLogger.warn(s"  -> stdout : ${result.stdout}")
              if (result.stderr.size > 0)
                HooksLogger.warn(s"  -> stderr : ${result.stderr}")
              Warning(result.code, result.stdout, result.stderr, msg)
            } else if(result.code == Interrupt.code) {
              Interrupt(msg, result.stdout, result.stderr)
            } else { //reserved - like error for now
              ScriptError(result.code, result.stdout, result.stderr, msg)
            }
          } recover {
            case ex: Exception => HookReturnCode.SystemError(s"Exception when executing '${path}' with environment variables: ${env.show}: ${ex.getMessage}")
          }
        case x: Error => Future(x)
      }
    }
  }

  /*
   * Run hooks in given directory, synchronously.
   *
   * Only the files with prefix ".hook" are selected as hooks, all
   * other files will be ignored.
   *
   * The hooks will be run in lexigraphically order, so that the
   * "standard" ordering of unix hooks (or init.d) with numbers
   * works as expected:
   *
   * 01-first.hook
   * 20-second.hook
   * 30-third.hook
   * etc
   *
   * You can get a warning if the hook took more than a given duration (in millis)
   */
  def syncRun(hooks: Hooks, hookParameters: HookEnvPairs, envVariables: HookEnvPairs, warnAfterMillis: Long = Long.MaxValue, killAfter: Duration = Duration.Inf): HookReturnCode = {
    try {
      //cmdInfo is just for comments/log. We use "*" to synthetize
      val cmdInfo = s"'${hooks.basePath}' with environment parameters: ${hookParameters.show}"
      HooksLogger.debug(s"Run hooks: ${cmdInfo}")
      HooksLogger.trace(s"Hook environment variables: ${envVariables.show}")
      val time_0 = System.currentTimeMillis
      val res = Await.result(asyncRun(hooks, hookParameters, envVariables), killAfter)
      val duration = System.currentTimeMillis - time_0
      if(duration > warnAfterMillis) {
        HooksLogger.LongExecLogger.warn(s"Hook '${cmdInfo}' took more than configured expected max duration: ${duration} ms")
      }
      HooksLogger.debug(s"Done in ${duration} ms: ${cmdInfo}") // keep that one in all cases if people want to do stats
      res
    } catch {
      case NonFatal(ex) => HookReturnCode.SystemError(s"Error when executing hooks in directory '${hooks.basePath}'. Error message is: ${ex.getMessage}")
    }
  }
}
