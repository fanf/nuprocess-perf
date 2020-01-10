
import java.nio.file.attribute.PosixFilePermission

import better.files._
import process.HookEnvPairs
import process.HookReturnCode
import process.Hooks
import process.HooksLogger
import zio.ZIO

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
         HooksLogger.debug(s"Looking for hooks in directory '${basePath}', ignoring files with suffix: '${ignoreSuffixes.mkString("','")}'")
         // only keep executable files
         val files = dir.list.toList.flatMap { file =>
           file match {
             case f if (f.isDirectory) => None
             case f =>
               if(f.isExecutable) {
                 val name = f.name
                 //compare ignore case (that's why it's a regienMatches) extension and name
                 ignoreSuffixes.find(suffix => name.regionMatches(true, name.length - suffix.length, suffix, 0, suffix.length)) match {
                   case Some(suffix) =>
                     HooksLogger.debug(s"Ignoring hook '${f.pathAsString}' because suffix '${suffix}' is in the ignore list")
                     None
                   case None      =>
                     Some(f.name)
                 }
               } else {
                 HooksLogger.debug(s"Ignoring hook '${f.pathAsString}' because it is not executable. Check permission if not expected behavior.")
                 None
               }
           }
         }.sorted // sort them alphanumericaly
         Hooks(basePath, files)
       } else {
         HooksLogger.debug(s"Ignoring hook directory '${dir.pathAsString}' because path does not exists")
         // return an empty Hook
         Hooks(basePath, List[String]())
       }
     } catch {
       case ex: Exception =>
         println(s"Error occured when looking for hooks, abort test: ${ex.getMessage}")
         throw ex
     }
   }

}


object Main {

  def main(args: Array[String]): Unit = {
    // copy hooks.d into /tmp/test-hooks/
    val testDir = File("/tmp/test-hooks")
    val hooksDir = testDir / "hooks.d"
    hooksDir.createDirectoryIfNotExists(true)
    if(hooksDir.list.isEmpty) { // copy from classpath hooks
      List("10-create-root-dir", "20-touch-file", "30-echo-hello").foreach { f =>
        (hooksDir / f).writeBytes(Resource.getAsStream(s"hooks.d/$f").bytes).addPermission(PosixFilePermission.OWNER_EXECUTE)
      }
    }

    val hooks = GetHooks.getHooks((testDir / "hooks.d").pathAsString, Nil)
    println(s"Found hooks: ${hooks}")

    compareRun(hooks, 10)
  }



  def compareRun(hooks: Hooks, iterations: Int): Unit = {
    val params = (0 until 500).map(i => HookEnvPairs.build(("UUID", s"$i")) )
    val env = HookEnvPairs.build()

    (0 until iterations).foreach { i =>

      File("/tmp/test-hooks/files").delete(true)

      println(s"-- $i --")

      ///// effectful exec /////
      {
        val t0 = System.currentTimeMillis()
        params.foreach { p => process.effectful.RunHooks.syncRun(hooks, p, env) }
        val t1 = System.currentTimeMillis()
        println(s"Effectful: ${t1-t0} ms")
      }

      ///// pure exec /////
      {
        val t0 = System.currentTimeMillis()
        params.foreach { p => process.pure.RunHooks.syncRun(hooks, p, env) }
        val t1 = System.currentTimeMillis()
        println(s"Pure     : ${t1-t0} ms")
      }
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
