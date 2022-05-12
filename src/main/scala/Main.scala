import java.nio.file.attribute.PosixFilePermission

import better.files._
import process.HookEnvPairs
import process.HookReturnCode
import process.Hooks
import process.HooksLogger
import zio.ZIO

/**
  * Run scripts from `src/main/resources/hooks.d` after having copied that directory into `/tmp/test-hooks`.
  * Scripts are run alternatively with branche 5.0 (Future) and 6.0 (ZiO) logic.
  */
object Main {

  System.setProperty("com.zaxxer.nuprocess.threads", "1")

  def main(args: Array[String]): Unit = {
    // copy hooks.d into /tmp/test-hooks/
    val testDir = File("/tmp/test-hooks")
    val hooksDir = testDir / "hooks.d"
    hooksDir.createDirectoryIfNotExists(true)
    if (hooksDir.list.isEmpty) { // copy from classpath hooks
      List("10-create-root-dir", "20-touch-file", "30-echo-hello").foreach {
        f =>
          (hooksDir / f)
            .writeBytes(Resource.getAsStream(s"hooks.d/$f").bytes)
            .addPermission(PosixFilePermission.OWNER_EXECUTE)
      }
    }

    // test with real hooks, so timing will contain real nuprocess exec but also OS related I/O from scripts
    val hooks = GetHooks.getHooks((testDir / "hooks.d").pathAsString, Nil)
    println(s"Found hooks: ${hooks}")

    compareRun(hooks, 10)

  }

  def compareRun(hooks: Hooks, iterations: Int): Unit = {
    def run(
        msg: String,
        params: Seq[HookEnvPairs],
        env: HookEnvPairs,
        syncRun: (Hooks, HookEnvPairs, HookEnvPairs) => HookReturnCode
    ): Unit = {
      val t0 = System.currentTimeMillis()
      params.foreach { p =>
        syncRun(hooks, p, env)
      }
      val t1 = System.currentTimeMillis()
      println(s"$msg: ${t1 - t0} ms")
    }

    val params = (0 until 500).map(i => HookEnvPairs.build(("UUID", s"$i")))
    val env = HookEnvPairs.build()

    println("Run alternativelly")
    (0 until iterations).foreach { i =>
      File("/tmp/test-hooks/files").delete(true)
      println(s"-- $i --")

      ///// effectful exec /////
      run("Future", params, env, process.effectful.RunHooks.syncRun(_, _, _))

      ///// pure exec /////
      run("ZIO   ", params, env, process.pure.RunHooks.syncRun(_, _, _))
    }

    println("Run sequentially")
    // sequentially
    (0 until iterations).foreach { i =>
      File("/tmp/test-hooks/files").delete(true)
      ///// effectful exec /////
      run("Future", params, env, process.effectful.RunHooks.syncRun(_, _, _))
    }
    (0 until iterations).foreach { i =>
      ///// pure exec /////
      run("ZIO   ", params, env, process.pure.RunHooks.syncRun(_, _, _))
    }
  }

  // only display errors
  def display(code: HookReturnCode): Unit = {
    code match {
      case _: HookReturnCode.Ok => ()
      case _                    => println(s"Error in hook exec: $code")
    }
  }

}

/**
  * This object is extracted from `RunHooks` as it is not relevant for the performance problem analysed here.
  * It was adapted for simplicity (ie: its errors are not managed).
  */
object GetHooks {

  /**
    * Get the hooks set for the given directory path.
    * Hooks must be executable and not ends with one of the
    * non-executable extensions.
    *
    * FOR TEST : THIS METHOD CAN THROW EXCEPTION
    */
  def getHooks(basePath: String, ignoreSuffixes: List[String]): Hooks = {
    try {
      val dir = File(basePath)
      // Check that dir exists before looking in it
      if (dir.exists) {
        HooksLogger.debug(
          s"Looking for hooks in directory '${basePath}', ignoring files with suffix: '${ignoreSuffixes
            .mkString("','")}'"
        )
        // only keep executable files
        val files = dir.list.toList.flatMap { file =>
          file match {
            case f if (f.isDirectory) => None
            case f =>
              if (f.isExecutable) {
                val name = f.name
                //compare ignore case (that's why it's a regienMatches) extension and name
                ignoreSuffixes.find(
                  suffix =>
                    name.regionMatches(
                      true,
                      name.length - suffix.length,
                      suffix,
                      0,
                      suffix.length
                    )
                ) match {
                  case Some(suffix) =>
                    HooksLogger.debug(
                      s"Ignoring hook '${f.pathAsString}' because suffix '${suffix}' is in the ignore list"
                    )
                    None
                  case None =>
                    Some(f.name)
                }
              } else {
                HooksLogger.debug(
                  s"Ignoring hook '${f.pathAsString}' because it is not executable. Check permission if not expected behavior."
                )
                None
              }
          }
        }.sorted // sort them alphanumericaly
        Hooks(basePath, files)
      } else {
        HooksLogger.debug(
          s"Ignoring hook directory '${dir.pathAsString}' because path does not exists"
        )
        // return an empty Hook
        Hooks(basePath, List[String]())
      }
    } catch {
      case ex: Exception =>
        println(
          s"Error occured when looking for hooks, abort test: ${ex.getMessage}"
        )
        throw ex
    }
  }

}
