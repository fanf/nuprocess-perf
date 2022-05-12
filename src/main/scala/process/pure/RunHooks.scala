package process.pure

import java.io.File
import process.pure.zioruntime._
import process.pure.errors._
import process._

import java.util.concurrent.TimeUnit

import zio.{LogLevel => _, _}

trait MyPureLogger extends LogLevel {
  def trace(msg: => String) = ZIO.when(LOGLEVEL <= 1) {
    ZIO.console.flatMap(_.print(s"TRACE: $msg")).orDie
  }
  def debug(msg: => String) = ZIO.when(LOGLEVEL <= 2) {
    ZIO.console.flatMap(_.print(s"DEBUG: $msg")).orDie
  }
  def warn(msg: => String) = ZIO.when(LOGLEVEL <= 4) {
    ZIO.console.flatMap(_.print(s"WARN:  $msg")).orDie
  }
}

object PureHooksLogger extends MyPureLogger {
  object LongExecLogger extends MyPureLogger
}

object RunHooks {

  /**
    * Runs a list of hooks. Each hook is run sequencially (so that
    * the user can expects one hook side effects to be used in the
    * next one), but the whole process is asynchronous.
    * If one hook fails, the whole list fails.
    *
    * The semantic of return codes is:
    * - < 0: success (we should never have a negative returned code, but java int are signed)
    * - 0: success
    * - 1-31: errors. These codes stop the hooks pipeline, and the generation is on error
    * - 32-63: warnings. These code log a warning message, but DON'T STOP the next hook processing
    * - 64-255: reserved. For now, they will be treat as "error", but that behaviour can change any-time
    *            without notice.
    * - > 255: should not happen, but treated as reserved.
    *
    */
  def asyncRun(
      hooks: Hooks,
      hookParameters: HookEnvPairs,
      envVariables: HookEnvPairs,
      warnAfterMillis: Duration = 5.minutes,
      killAfter: Duration = 1.hour
  ): IOResult[HookReturnCode] = {

    import HookReturnCode._

    def logReturnCode(result: HookReturnCode): IOResult[Unit] = {
      for {
        _ <- PureHooksLogger.trace(s"  -> results: ${result.msg}")
        _ <- PureHooksLogger.trace(s"  -> stdout : ${result.stdout}")
        _ <- PureHooksLogger.trace(s"  -> stderr : ${result.stderr}")
        _ <- ZIO.when(result.code >= 32 && result.code <= 64) { // warning
          for {
            _ <- PureHooksLogger.warn(result.msg)
            _ <- ZIO.when(result.stdout.size > 0) {
              PureHooksLogger.warn(s"  -> stdout : ${result.stdout}")
            }
            _ <- ZIO.when(result.stderr.size > 0) {
              PureHooksLogger.warn(s"  -> stderr : ${result.stderr}")
            }
          } yield ()
        }
      } yield ()
    }

    def translateReturnCode(path: String, result: CmdResult): HookReturnCode = {
      lazy val msg = {
        val specialCode =
          if (result.code == Int.MinValue) { // this is most commonly file not found or bad rights
            " (check that file exists and is executable)"
          } else ""
        s"Exit code=${result.code}${specialCode} for hook: '${path}'."
      }
      if (result.code == 0) {
        Ok(result.stdout, result.stderr)
      } else if (result.code < 0) { // this should not happen, and/or is likely a system error (bad !#, etc)
        //using script error because we do have an error code and it can help in some case

        ScriptError(result.code, result.stdout, result.stderr, msg)
      } else if (result.code >= 1 && result.code <= 31) { // error
        ScriptError(result.code, result.stdout, result.stderr, msg)
      } else if (result.code >= 32 && result.code <= 64) { // warning
        Warning(result.code, result.stdout, result.stderr, msg)
      } else if (result.code == Interrupt.code) {
        Interrupt(msg, result.stdout, result.stderr)
      } else { //reserved - like error for now
        ScriptError(result.code, result.stdout, result.stderr, msg)
      }
    }

    /*
     * We can not use Future.fold, because it execute all scripts
     * in parallel and then combine their results. Our semantic
     * is execute script one after the other, combining at each
     * step.
     * But we still want the whole operation to be non-bloking.
     */
    val runAllSeq = ZIO.foldLeft(hooks.hooksFile)(Ok("", ""): HookReturnCode) {
      case (previousCode, nextHookName) =>
        previousCode match {
          case x: Error => ZIO.succeed(x)
          case x: Success => // run the next hook
            val path = hooks.basePath + File.separator + nextHookName
            val env = envVariables.add(hookParameters)
            for {
              _ <- PureHooksLogger.debug(
                s"Run hook: '${path}' with environment parameters: ${hookParameters.show}"
              )
              _ <- PureHooksLogger.trace(
                s"System environment variables: ${envVariables.show}"
              )
              p <- RunNuCommand.run(Cmd(path, Nil, env.toMap))
              r <- p.await
              c = translateReturnCode(path, r)
              _ <- logReturnCode(c)
            } yield {
              c
            }
        }
    }

    val cmdInfo =
      s"'${hooks.basePath}' with environment parameters: [${hookParameters.show}]"
    (for {
      //cmdInfo is just for comments/log. We use "*" to synthetize
      _ <- PureHooksLogger.debug(s"Run hooks: ${cmdInfo}")
      _ <- PureHooksLogger.trace(
        s"Hook environment variables: ${envVariables.show}"
      )
      time_0 <- ZIO.clock.flatMap(_.currentTime(TimeUnit.MILLISECONDS))
      res <- ZioRuntime
        .blocking(runAllSeq)
        .timeout(killAfter)
        .notOptional(
          s"Hook '${cmdInfo}' timed out after ${killAfter.asJava.toString}"
        )
      duration <- ZIO.clock.flatMap(_.currentTime(TimeUnit.MILLISECONDS)).map(_ - time_0)
      _ <- ZIO.when(duration > warnAfterMillis.toMillis) {
        PureHooksLogger.LongExecLogger.warn(
          s"Hooks in directory '${cmdInfo}' took more than configured expected max duration (${warnAfterMillis.toMillis}): ${duration} ms"
        )
      }
      _ <- PureHooksLogger.debug(s"Done in ${duration} ms: ${cmdInfo}") // keep that one in all cases if people want to do stats
    } yield {
      res
    }).chainError(
      s"Error when executing hooks in directory '${hooks.basePath}'."
    )
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
  def syncRun(
      hooks: Hooks,
      hookParameters: HookEnvPairs,
      envVariables: HookEnvPairs,
      warnAfterMillis: Duration = 5.minutes,
      killAfter: Duration = 1.hour
  ): HookReturnCode = {
    asyncRun(hooks, hookParameters, envVariables, warnAfterMillis, killAfter).either.runNow match {
      case Right(x) => x
      case Left(err) =>
        HookReturnCode.SystemError(
          s"Error when executing hooks in directory '${hooks.basePath}'. Error message is: ${err.fullMsg}"
        )
    }
  }
}
