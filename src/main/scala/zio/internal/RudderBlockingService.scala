package zio.internal

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinWorkerThread


import zio.UIO
import zio.ZIO
import zio.blocking.Blocking
import zio.blocking.Blocking.Service

/*
 * Code copied from zio.blocking.internal
 *
 * On writting test, just the two modif below earns 50% of thread churn (thread kill before being reused)
 * and 10% performance (likely because creating a thread is costly).
 * It may not transpose to real use case gain.
 * Interestingly, using scala work stealing thread pool doesn't yield anything (but can lead to deadlock,
 * so we should not used it).
 */
trait RudderBlockingService extends Blocking {
  val blocking: Service[Any] = new Service[Any] {

//    val zio = ZIO.succeed(Executor.fromThreadPoolExecutor(_ => Int.MaxValue) {
//      val corePoolSize  = Math.max(1, Runtime.getRuntime.availableProcessors()/2) // default is 0
//      val maxPoolSize   = Int.MaxValue
//      val keepAliveTime = 5*1000L // default is 1000
//      val timeUnit      = TimeUnit.MILLISECONDS
//      val workQueue     = new SynchronousQueue[Runnable]()
//      val threadFactory = new NamedThreadFactory("zio-default-blocking", true)
//
//      val threadPool = new ThreadPoolExecutor(
//        corePoolSize,
//        maxPoolSize,
//        keepAliveTime,
//        timeUnit,
//        workQueue,
//        threadFactory
//      )
//
//      threadPool
//    })

    // this doesn't yield better results
    // val scalaGobal: UIO[Executor] = ZIO.succeed(Executor.fromExecutionContext(Int.MaxValue)(scala.concurrent.ExecutionContext.global))

    val blockingExecutor: UIO[Executor] = ZIO.succeed(RudderExecutor.executor)
  }
}

object RudderExecutor {

  final def fromForkJoinPool(yieldOpCount0: ExecutionMetrics => Int)(
    fjp: ForkJoinPool
  ): Executor =
    new Executor {
      private[this] def metrics0 = new ExecutionMetrics {
        def concurrency: Int = fjp.getParallelism

        def capacity: Int = Int.MaxValue // don't know how to get it from pool

        def size: Int = fjp.getQueuedSubmissionCount

        def workersCount: Int = fjp.getPoolSize()

        def enqueuedCount: Long = -1 // can't know ?

        def dequeuedCount: Long = -1 // can't know ?
      }

      def metrics = Some(metrics0)

      def yieldOpCount = yieldOpCount0(metrics0)

      def submit(runnable: Runnable): Boolean =
        try {
          fjp.execute(runnable)

          true
        } catch {
          case _: RejectedExecutionException => false
        }

      def here = false
    }

  /**
   * A named fork-join pool
   */

  lazy val factory: ForkJoinPool.ForkJoinWorkerThreadFactory = new ForkJoinPool.ForkJoinWorkerThreadFactory() {
    override def newThread(pool: ForkJoinPool): ForkJoinWorkerThread = {
      val worker = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool)
      worker.setName("zio-rudder-mix-" + worker.getPoolIndex)
      worker
    }
  }

  /*
   * We must set a max capacity
   */
  lazy val forkJoinPool = {
    val parallelism = Runtime.getRuntime.availableProcessors
    new ForkJoinPool(
        parallelism
      , factory
      , null // default UncaughtExceptionHandler
      , false // asyncMode: set to true for event style workload, better locality
// these are jdk11 specific
//      , 2*parallelism  // corePoolSize, ie thread to keep in the pool. We a lot of blocking tasks
//      , Int.MaxValue // maximumPoolSize: no up limit to number of thread
//      , if(parallelism == 1)  1 else 2 //  minimumRunnable: 1 to ensure liveliness
//      , null // use default for Predicate<? super ForkJoinPool> saturate
//      , 60 //keepAliveTime,
//      , TimeUnit.SECONDS // unit for keepAliveTime
    )
  }

  lazy val executor = fromForkJoinPool(_ => Int.MaxValue)(forkJoinPool)
}
