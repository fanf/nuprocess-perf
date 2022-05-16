
# `ZIO` and `Future` performance comparison for `NuProcess`

We use [nuprocess](https://github.com/brettwooldridge/NuProcess/) to execute shell hooks in [Rudder](https://rudder.io).

We used to wrap it with `Future` (and `Monix` `I/O` scheduler), but we are switching to `ZIO`. 
Unfortunately, we hit a major performance difference. 

Hooks are used during a phase where they need to be called for a lot of our business 
node objects (depending on managed infra, from tens to thousands) and the part of the code 
doing that call is not ported to `ZIO` yet (and porting that code is out of reach for now). 

So, we end up with something equivalent to:

```
nodes.foreach { node =>
  ZioRuntime.unsafeRun( runHooksSync(node) )
}
```

*The fact that `unsafeRun` is called each time cannot be changed for now.*

The code performance with `ZIO` is much worse than with `Future`. In the test
here we show up to 2x slowdown (see below), and with real workload in Rudder, 
when 5000 nodes are configured, the slowdown goes up to 10x.

I tried to optimize all what, I thought, could be optimized, including using`untraced`.
I'm not certain how to optimize it more. The main remaining difference may be due to 
the ZIO runtime overhead (creating threads for pools? Just starting interpreter overhead?).
However, I would love to have people more knowledgeable than me to take a look at it.

## Original code

The orginal code is from Rudder and is available in its repo:

- branche 5.0 uses Monix: https://github.com/Normation/rudder/tree/branches/rudder/5.0/webapp/sources/rudder/rudder-core/src/main/scala/com/normation/rudder/hooks

- branche 6.0 uses ZIO: https://github.com/Normation/rudder/tree/branches/rudder/6.0/webapp/sources/rudder/rudder-core/src/main/scala/com/normation/rudder/hooks

##  UPDATE 2: major performance and simplification boost on with ZIO 2.0.0-RC6

ZIO 2.0.0-RC6 is the first revision of ZIO using the auto-magic threadpool and blocking
effect manager (see https://github.com/zio/zio/issues/1275): it means that ZIO is able 
by itself to decide if an effect is blocking of not, to migrate it to the appropriate
threadpool, and to learn which code path leads to a blocking effect. 

From a user point of view, it means that we JUST DON'T CARE if a method might be blocking
(I'm looking at you InetAddress): never again will you face a deadlock. 

And it massively improves performances, with results comparable to `monix 1` without having
anything to do - but with an automatic management of complicated cases. 


##  UPDATE: performance boost on branch `only_on_pool_fjp` compared to master

In this branche, we have two major changes compared to master:

- 1/ we use the same thread pool for blocking and non-blocking effects: JDK 8 ForkJoinPool. 

By itselve, that change only yields minor performance benefits. 

- 2/ we shortcut all `effectBlocking` into `effect`, removing calls to pool switch (even if there is only one pool). 

This yield major performance boost, and we get similar order of magnitude results (<10%) than future.

## Code structure

Current code is an extraction of original code with some adaptation to make both version compile with
the minimum set of changes, but also with the minimum dependencies, as a will of simplification. 

The code modelize hooks are a script file with parameters that are sequentially executed through an OS
process. We use [NuProcess](https://github.com/brettwooldridge/NuProcess/) for native script execution:
it manages it's own thread to do/wait for script execution, and asynchronuously gives result (ie return
code, stdout and stderr) through a scala handler. 

### Directory organisation

`process` package contains the code from the two branches. Common data structures were extracted in a 
common `Data.scala` file, and code specific to each branch was put in a sub-package:

- `effectful` contains branche 5.0 code based on `Future`,
- `pure` contains branche 6.0 code based on `ZIO`.

`Main.scala` is a runner used to call example hooks. 

### `Data.scala`

`Data.scala` contains `Hooks` data definition: hooks are scripts from a file system directory.
`Hooks` get their parameters from scala through environment variables. In scala, we pass them
two set of environment variables, one for the script parameter, and one from system environement
variables to make shell happy. 

`Hooks` return codes form an ADT with success (real ok or ok with warning), and several cases of
errors (system error, script error, etc).
 
### Branche specific hook implementation

#### RunNuCommand.scala

This class implement the execution of one script through an OS process call. 
This is the part which interfaces with `NuProcess`. The main component are: 

- `SilentLogger`: we hook `NuProcess` logger because it doesn't output what we want and isn't
  easily configurable. 
- `class CmdProcessHandler(promise: Promise[Nothing, CmdResult]) extends NuAbstractCharsetHandler(StandardCharsets.UTF_8)` is `NuProcess` interface handler, we need to implement it to get results.
- `Cmd` and `CmdResult` are data class which eases translation to `NuProcess` input/output format.
- `def run(cmd: Cmd, limit: Duration)` contains the logic to set-up `NuProcess` and get script
  result. In branche 5.0 (Monix/Future), the result is boxed in a `Future`, and in branche 
  6.0 (ZIO) in a `Promise`


#### RunHooks.scala

That class implement our `Hooks` logic, ie "sequentially execute scripts from a directory, sorted by
name. Continue to the next only if the previous script was successful". 

- `def async(hooks: Hooks, param, env...)` doesn't wait for the result. In branche 5.0, it means it returns
  a `Future` and in 6.0, it's just a `ZIO[Any, RudderError, HookReturnCode]`
  
- `def sync(...)` is a call to `async` that actually runs the code and wait for result. In branche 5.0
  with `Future`, running the code is just calling it of course, in branche 6.0 
  
#### ZioCommons.scala

This class contains all the `Rudder` specific implementation and instanciation for `ZIO`.
The particularly important points are: 

##### IOResult[A] == IOResult[Any, RudderError, A] 

This is our type with our `RudderError`, nothing fancy

##### IOResult.effect === ZZIO.attemptBlocking

In Rudder, we are forced to do a lot of interaction with non-pure code (because Rudder is 10 years old 
150kloc which started with 'scala as java'). So, we import a lot of effects. 
And we don't really know what these effects do, BY DEFAULT, WE IMPORT THEM ON THE BLOCKING
THREADPOOL (https://github.com/zio/zio/issues/1275). 

#### ZioRuntime

It's a global instance of `DefaultRuntime`. 

### Main.scala

This class runs hooks from directory `src/main/resources/hooks.d`. The set-up copy that directory into
`/tmp/test-hooks` which is also used as the directory where scripts do things. 

In original code, method `GetHooks#def getHooks(basePath: String, ignoreSuffixes: List[String]): Hooks` is part 
of `RunHooks.scala` but it's not the point of the performance problem and so was extracted in the common
runner class. The logic is basically to look in a directory for executable files with some filtering
on file extension and sort them to create a `Hooks` instance. 

## Test it

You need `sbt` and a `jvm` installed. I used `sbt 1.3.6` and `openjdk 11.0.4`.

``` 
git clone https://github.com/fanf/nuprocess-perf.git
cd nuprocess-perf
stb
sbt:nuprocess> compile
sbt:nuprocess> runMain Main
```
# Test results


Directory "images" contains a visualvm thread view of what happens.

On my laptop (XPS 9560, i7-7700HQ with nvme), I get the following results:

## ZIO 2.0.0-RC6 results


```
Run alternativelly
-- 0 --
Future: 2742 ms
ZIO   : 3457 ms
-- 1 --
Future: 2519 ms
ZIO   : 2778 ms
-- 2 --
Future: 2542 ms
ZIO   : 2735 ms
-- 3 --
Future: 2474 ms
ZIO   : 2599 ms
-- 4 --
Future: 2504 ms
ZIO   : 2614 ms
-- 5 --
Future: 2481 ms
ZIO   : 3031 ms
-- 6 --
Future: 2504 ms
ZIO   : 2883 ms
-- 7 --
Future: 2475 ms
ZIO   : 2999 ms
-- 8 --
Future: 3243 ms
ZIO   : 2600 ms
-- 9 --
Future: 2441 ms
ZIO   : 2583 ms
Run sequentially
Future: 2677 ms
Future: 3184 ms
Future: 2463 ms
Future: 2461 ms
Future: 2889 ms
Future: 2454 ms
Future: 2831 ms
Future: 2782 ms
Future: 2954 ms
Future: 2909 ms
ZIO   : 2548 ms
ZIO   : 2567 ms
ZIO   : 2633 ms
ZIO   : 3498 ms
ZIO   : 2829 ms
ZIO   : 2575 ms
ZIO   : 2560 ms
ZIO   : 2590 ms
ZIO   : 2514 ms
ZIO   : 3067 ms
[success] Total time: 110 s, completed 12-May-2022 18:55:34

```


## ZIO 1.0.0-RC16 results

These are the old results with 2IO 1.0 that leaded to lots of adhoc improvements

```
Found hooks: Hooks(/tmp/test-hooks/hooks.d,List(10-create-root-dir, 20-touch-file, 30-echo-hello))
-- 0 --
Effectful: 3362 ms
Pure     : 5851 ms
-- 1 --
Effectful: 3131 ms
Pure     : 5834 ms
-- 2 --
Effectful: 3374 ms
Pure     : 5140 ms
-- 3 --
Effectful: 3245 ms
Pure     : 5488 ms
-- 4 --
Effectful: 3651 ms
Pure     : 5227 ms
-- 5 --
Effectful: 3373 ms
Pure     : 4529 ms
-- 6 --
Effectful: 4680 ms
Pure     : 6020 ms
-- 7 --
Effectful: 3672 ms
Pure     : 7106 ms
-- 8 --
Effectful: 3777 ms
Pure     : 7522 ms
-- 9 --
Effectful: 4634 ms
Pure     : 5222 ms
[success] Total time: 95 s, completed 10 Jan 2020, 10:22:54
```

I added a run which sequentially run each case to try to see if it changes
JVM behavior/warm-up (next step is certainly to use `jmh`)

```
=> not limiting threads
Found hooks: Hooks(/tmp/test-hooks/hooks.d,List(10-create-root-dir, 20-touch-file, 30-echo-hello))
Run alternativelly
-- 0 --
Future: 2731 ms
ZIO   : 4733 ms
-- 1 --
Future: 2135 ms
ZIO   : 3442 ms
-- 2 --
Future: 2127 ms
ZIO   : 3270 ms
-- 3 --
Future: 2154 ms
ZIO   : 3914 ms
-- 4 --
Future: 2058 ms
ZIO   : 3797 ms
-- 5 --
Future: 2188 ms
ZIO   : 3261 ms
-- 6 --
Future: 2025 ms
ZIO   : 3339 ms
-- 7 --
Future: 2186 ms
ZIO   : 3100 ms
-- 8 --
Future: 2062 ms
ZIO   : 3712 ms
-- 9 --
Future: 2203 ms
ZIO   : 3282 ms

Run sequentially
Future: 2114 ms
Future: 2006 ms
Future: 2038 ms
Future: 2182 ms
Future: 2097 ms
Future: 2649 ms
Future: 2110 ms
Future: 2074 ms
Future: 2221 ms
Future: 2309 ms

ZIO   : 3359 ms
ZIO   : 4540 ms
ZIO   : 3347 ms
ZIO   : 3441 ms
ZIO   : 3630 ms
ZIO   : 3478 ms
ZIO   : 3232 ms
ZIO   : 3514 ms
ZIO   : 3076 ms
ZIO   : 3277 ms

Process finished with exit code 0
```
