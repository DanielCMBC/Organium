# Live performance: Melcer Chamber Music Hall on the 8 GB machine

Milestone: a stable, playable Melcer organ through ASIO on this machine (Windows 10, 8 GB RAM,
ASIO4ALL v2, 48 kHz, 256-frame buffer = 5.33 ms). This report covers sustained playing with
several manuals, pedal, stop changes, rapid repeated notes and many simultaneous release tails, and
what was measured and changed to make it stable. No new instrument features were added.

## Test harness

`test/organium/LiveStress.java` runs the real stack: parser and selective sample loading, `Console`
with the measured pan table, `ConsoleWindow`, `VoiceEngine` (4,096 voices, -10 dB trim) and
`AsioOutput`. A performance thread plays a scripted, seeded (20261107) concert of about 174 s per
round and repeats it until the requested time has passed (a round always finishes):

| Section | What it exercises |
|---|---|
| 1-chords | chord progression on HW + BW + pedal with a mixed registration |
| 2-counterpoint | independent lines on both manuals and pedal |
| 3-rapid | fast repeated notes (retriggering while releases are still sounding) |
| 4-stop-changes | stops drawn and retired while notes are held |
| 5-tutti | all playable stops and couplers, full chords: the most voices and release tails |
| 6-random | random notes, durations and registrations |
| 7-silence | everything released at once, tails decaying |

`src/organium/audio/RenderMonitor.java` wraps the renderer and measures every callback without
allocating: render time, interval since the previous callback, overruns (render longer than the
period), gaps (interval over 1.5 periods), a render-time histogram, and when the worst gap happened.
The harness logs once a second (`timeline.csv`): voices, sounding pipes, worst render and gap,
audio-thread allocation (`ThreadMXBean.getThreadAllocatedBytes`), GC count and time, heap, free RAM,
dropped notes, lost events. The JVM writes GC and safepoint logs (`jvm.log`), and
`research/live/sample_process.ps1` samples the process working set and free RAM. An optional
background thread allocates garbage at a fixed rate to stand in for GUI, MIDI and future
features. `test/organium/AsioTimingCheck.java` checks the driver itself: callback rate, frame rate
and interval distribution, optionally while the screen is being captured.

Scripts: `research/live/gc_compare.ps1` (one run with given JVM options and garbage rate),
`research/live/summarize_timeline.py` (per-section table and GC pauses). Results of every run are
in `research/live/run-*/` (console screenshots are not committed: they show the sample set's
licensed artwork).

## Results

| Run | JVM | Garbage | Played | Worst render | Worst gap | Gaps | Peak working set | Min free RAM |
|---|---|---|---|---|---|---|---|---|
| 01 smoke | G1, 32 MB regions, 4 GB | 0 | 174 s | 0.89 ms | 6.86 ms | 0 | (not sampled) | 1.99 GB |
| 02 sustained | G1, 32 MB regions, 4 GB | 0 | 696 s | 1.22 ms | **45.2 ms** | **2** | (not sampled) | 1.71 GB |
| 03 | G1, 32 MB regions, 4 GB | 40 MB/s | 174 s | 1.44 ms | **44.8 ms** | **3** | (not sampled) | 1.11 GB |
| 04 | ZGC, 4 GB | 40 MB/s | 174 s | 1.42 ms | 6.82 ms | 0 | **5.29 GB** | **0.38 GB** |
| 05 | ZGC, 3 GB | 40 MB/s | 174 s | 0.97 ms | **186 ms** (startup) | **1** | **5.21 GB** | **0.37 GB** |
| 06 | ZGC, 3 GB, explicit unmap | 40 MB/s | 348 s | 2.10 ms | 6.82 ms | 0 | 2.83 GB | 2.34 GB |
| 07 | ZGC, 4 GB, explicit unmap | 40 MB/s | 348 s | 1.11 ms | 6.81 ms | 0 | 2.96 GB | 2.27 GB |
| **08 sustained** | **ZGC, 4 GB, explicit unmap** | 0 | **696 s** | **1.37 ms** | 45.0 ms (startup) | 1 | **2.60 GB** | **2.46 GB** |
| 09a | ZGC, 4 GB, explicit unmap | 0 | 175 s | 1.04 ms | 45.2 ms (startup) | 1 | 2.51 GB | 2.60 GB |

Runs 01-05 report "duration 170/180 s" in their summaries: that was the time asked for, not the time
played (fixed). Their process-memory sampling read the `java.exe` launcher stub instead of the JVM
(fixed); free RAM is from the harness. Free RAM before Organium starts is about 5.1 GB.

Render time is not a concern: the mean is 0.15 ms per 5.33 ms callback (under 3% of one core), the
worst 1.1-2.1 ms (under 40%), and no callback ever rendered longer than its period. Peak polyphony
was 495-543 voices (108 sounding pipes, the rest release tails). No notes were dropped and no events
were lost.

The driver delivers 187.5 callbacks per second and 48,003-48,005 frames per second, as asked, with
intervals between 2.7 and 7.3 ms (ASIO4ALL's normal jitter over WDM); the steady worst gap of
6.6-6.9 ms in every run is this jitter, not a fault. Grabbing and PNG-encoding the screen 296 times
in 30 s did not change it (`AsioTimingCheck ... capture`: worst interval 6.78 ms).

## Finding 1: G1 collection pauses stop the audio callback

The ASIO callback is a Java upcall, so it cannot run while the JVM is stopped. G1's young
collections are stop-the-world pauses of 20-45 ms here (in run 02 a 22.6 ms pause 108 s into the
performance, while round 0's tutti screenshot was being encoded, produced both gaps, the worst
45 ms; run 03's three gaps of 15-45 ms line up with young collections); any allocation elsewhere in the process (GUI, MIDI,
screenshots) eventually triggers one, so every G1 configuration dropped out. ZGC does its work
concurrently: over 39-50 cycles, total pause time was 1-2 ms with no pause over 1 ms, and
no callback was delayed by it, even with 40 MB/s of garbage.

## Finding 2: memory-mapped sample files stayed resident until garbage collection

`WavReader` memory-maps each file to decode it (so the file does not become a heap array). A
`MappedByteBuffer` is only unmapped when the garbage collector frees it. G1 collected often enough
to hide this; ZGC, with a mostly static heap, kept thousands of stale mappings, holding 4.8 GB of
sample files in the process's working set (5.3 GB peak, 0.38 GB free RAM, paging risk).

Fix: `WavReader` maps each file into a confined FFM `Arena` and closes it right after decoding,
which unmaps the file immediately, independent of the collector. Decoded output is unchanged
(regression suite bit-identical, every Melcer sample decodes). Peak working set fell from 5.3 GB to
2.6-3.0 GB, and free RAM stays above 2.2 GB even with 40 MB/s of garbage.

## Decision: ZGC with a 4 GB heap

`run.ps1` now starts Organium with `-Xmx4g -XX:+UseZGC`. 3 GB also worked (run 06) but peaked at
2.66 GB of heap in use under artificial garbage, too close to the limit; 4 GB cost about 130 MB of
working set more. The G1 region-size setting is no longer needed (ZGC has no humongous regions).

## Finding 3: one gap at the start of a run (not reproduced; cause not determined)

Three runs (05, 08, 09a) had one gap each at the very start: 186 ms, 45 ms and 45 ms. All three
fell in the first second, and none happened later in any run. Run 09a logged the harness's steps
(times since the performance began, which is just after `AsioOutput.start()` returns):

    [0.023 s] round 0 begins
    [0.036 s] screenshot chords-registration begins   (Robot screen capture of the console window + PNG)
    [0.105 s] worst gap ends: 45.23 ms; the callback before it rendered for 0.015 ms
    [0.415 s] screenshot ends

The driver's first callback comes about 45 ms after `start()` returns (below), so this gap fell
within the driver's first few callbacks, while the first screenshot was being taken, before any note.
It is not a JVM pause (every safepoint in the first seconds took under 1 ms; ZGC logged no pause
over 1 ms), and rendering did not run late (0.015 ms).

### Follow-up trials (run 10-12, `research/live/startup_trial.ps1`)

Each trial is a separate JVM with the stable settings (ZGC, 4 GB), run one at a time in the
foreground, playing 12-15 s. `LiveStress` now traces the first 8,000 callbacks from the very first
one (`startup-callbacks.csv`: time and render time of each) and logs what the harness does on the
same clock, zero being the call to `AsioOutput.start()` (`startup-events.csv`, with JVM uptime so the
GC log can be placed on it). `startup_gaps.py` lists every interval over 1.5 periods with the events
around it. Nothing is excluded: every interval from the first callback on is counted.

| Condition | Starts | Intervals over 8 ms | Worst interval in the first second |
|---|---|---|---|
| full stack, first screenshot at 0.33 s (as in run 09a) | 7 | 0 | 6.7 ms |
| full stack, `--no-screenshots` | 6 | 0 | 6.9 ms |
| full stack with `sample_process.ps1` polling WMI every second (as gc_compare.ps1 did) | 4 | 0 | 6.7 ms |
| ZGC's post-load proactive collection overlapping the first callbacks (shots-1, noshots-1, noshots-3 of run 12) | 3 | 0 | |
| driver alone, `AsioTimingCheck` (30 separate JVMs, `driver-starts.txt`) | 30 | 0 | 7.0 ms |
| `run.ps1 --asio --demo` (App, with the new dropout report) | 1 | 0 | 7.0 ms (whole run) |

The driver's first callback arrives 278-462 ms after `start()` is called (`start()` itself returns
after about 287 ms). The first two or three callbacks come in a burst (0.3-3.7 ms apart) while
ASIO4ALL primes its buffers, then the 5.33 ms rhythm settles at once; no start showed a stall.

So in 48 starts since then, none reproduced the gap, and none of the candidates (the screenshot,
driver initialisation, the WMI sampler, a ZGC cycle at start) produces it on demand. The cause of the
three original gaps is therefore not determined. What the data do show:

- Every dropout seen with ASIO4ALL, whatever its cause, lasted almost exactly 45 ms: the three
  start-up gaps (45.0, 45.2 and 45.2 ms, and 186 ms under memory pressure) and the gaps after G1
  pauses of about 22 ms (45.2, 44.8 ms). A late callback seems to make ASIO4ALL drop and resync a
  fixed block, so a short stall becomes a 45 ms dropout. That is a property of ASIO4ALL over WDM; a
  native ASIO driver may behave differently.
- The remaining explanation is something outside Organium briefly holding up the driver's thread
  at those moments (other processes, the system's own activity). Run 05's 186 ms gap happened with
  0.37 GB of free RAM, before the unmap fix.

`App` now reports dropouts itself (`--asio`): a line whenever callbacks fall behind or a render
overruns, and a summary on exit (callbacks, mean and worst render, worst gap, late callbacks,
overruns), counted from the first callback with nothing excluded. A real session will show whether
start-up dropouts happen in normal use. The ZGC configuration is unchanged: no test showed a reason
to change it.

## Audio-thread allocation

After warm-up the audio thread allocates 336 bytes (G1) or 392 bytes (ZGC) once, early in the run,
and nothing afterwards in up to 696 s: no steady allocation in the callback path. The one-time
allocation is most likely JIT-related (deoptimization or a first-time class initialisation reached
from the render path); it is not yet identified.

## Memory budget (run 08)

| | |
|---|---|
| decoded samples (playable ranks, 16-bit) | 2,175 MiB |
| heap in use, peak | 2,458 MiB |
| process working set, steady / peak | 2.55 / 2.60 GB |
| free RAM before start / during play | 5.1 / 2.46 GB |

## Console

The Melcer console renders correctly in Organium's `ConsoleWindow`, with stop knobs drawn and lit
from the sample set's own images (screenshots are not committed). Its 1692 x 1200 design is taller
than this screen's 1040-pixel working area. The window now scales the console proportionally to
fit: see `research/console-scaling.md`.

## Not changed

No streaming, tremulants, swell or other features. The ASIO, MIDI and voice engines are unchanged.
`RenderMonitor` wraps the renderer in the test harness and, for the dropout report, in `App`.
