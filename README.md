
# ZIO and Monix performance comparison for NuProcess

We use [nuprocess](https://github.com/brettwooldridge/NuProcess/) to execute shell hooks in [Rudder](https://rudder.io).

We used to do that with monix, but we are switching to ZIO. Unfortunatly, we hit a major 
performance difference. 

Hooks are used during a phase where they need to be called for a lot of node objects
(depending of managed infra, from tens to thousands) and the part of the code doing 
the call is not ported to ZIO (and porting that code is out of reach for now). 

So we ends up with something moraly equivalent to:

```
for each nodes do { node =>
  ZioRuntime.unsafeRun( runHooksSync(node) )
}
```

On my laptop (XPS 9560, i7-7700HQ with nvme), I get the following results:

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

So, there is up to 2x slowdown.
I `untraced` what I think I could, and I'm not sure how to optimize it more. The
remaining difference may be due to the ZIO runtime overhead (creating threads for
the blocking pool? Just interpreter overhead?)
Directory "images" contains a visualvm thread view of what happens.

## Test it

You need `sbt` and a `jvm` installed. I used `sbt 1.3.6` and `openjdk 11.0.4`.

``` 
git clone https://github.com/fanf/nuprocess-perf.git
cd nuprocess-perf
stb
sbt:nuprocess> compile
sbt:nuprocess> runMain Main
```
