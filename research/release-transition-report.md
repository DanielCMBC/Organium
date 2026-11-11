# Release transition: the note-off click

Symptom (live session on Melcer Chamber Music Hall, Windows default audio): a pop at every note-off,
even for single notes. This report covers the evidence, the cause, Melcer's release metadata, the
fix, and before/after measurements.

## 1. Evidence and root cause

`test/organium/ReleaseClickProbe.java` renders single Melcer pipes offline through the real
`VoiceEngine`. It uses trim 0 dB and captures the float mix before any PCM conversion or clipping.
Note-on is at frame 0 and note-off exactly at a block boundary, after 100, 300, 700 and 3000 ms, so
each of Melcer's releases is selected (below 60, 140 and 280 ms, and the long one).

- **Where the step is.** In every stop and every note length, the largest sample-to-sample step falls
  exactly on the note-off frame (+0). It is 6-45x the largest step of the sustain for flue and pedal
  pipes (Subbass 16' with a short note is the worst, at 45x) and 1.3-4x for the reed and mixture.
- **An example.** Nachthorn 8', 3 s note, left channel:
  `-0.0204 -0.0213 -0.0223 | -0.0003 -0.0012`. That's a jump of about 2/3 of the sustain's peak in one frame.
- **The release files are clean.** They start mid-waveform at full level, as recordings of a pipe
  still sounding do (752, 749, 742, 741 ... against a sustain peak of 1168), and continue smoothly.
  The DC over their first 20 ms is about +-38/32768, against -0.4 in the sustain loop: negligible. There's no
  zero-filled gap.
- **The attack files start from silence.** At most 6/32768 in the first millisecond, building up over 8-48 ms.
- **The Windows backend is not involved.** The offline float mix already contains the step.
  `JavaSoundOutput` is a plain float-to-16-bit conversion with clamping at 48 kHz.

**Cause:** `VoiceEngine.release()` started the release sample at full gain, with no fade-in, while the
sustain faded out over 50 ms. The release's first frame lands at a phase unrelated to the sustain
loop's, so the output steps by up to the release's full level in one frame. For 50 ms the two also
overlapped at unrelated phases, giving level swings of -14 to +9 dB.

## 2. Melcer's release metadata (fields d, f, k, ...)

The question was whether the definition specifies a release crossfade length, a transition mode,
sample alignment or offset, or release envelope behaviour.

Sources:

- **D1, D2:** Hauptwerk User Guide and Custom Organ Design Module Guide (4.2.1), text extracted.
- **D3:** Melcer's tables.
- **D4:** a read-only scan of `Hauptwerk.exe` (`research/hauptwerk-probe/lea_scan.py`): the strings
  each schema-registration routine loads, in code order. Nothing was executed, patched or copied.

**Documentation (D1 p. 211, D2 p. 13):** "When a pipe ceases to speak, Hauptwerk performs a brief
phase-aligned cross-fade from the main sustaining part of the sample to the release sample." No
length is given. A separate "release truncation" feature applies "a shaped fade-out" to wet releases
(a user loading option, not part of the transition).

**Field names (D4).** The release-sample routine registers, in this order:
`ScaleAmplitudeAutomatically`, `DontBypassAmplitudeScalingIfUserDisablesMultipleReleases`,
`PhaseAlignAutomatically`, `ReleaseCrossfadeLengthMs`, `ReleaseSelCriteria_HighestVelocity`,
`ReleaseSelCriteria_LatestKeyReleaseTimeMs`, `ReleaseSelCriteria_HighestCtsCtrlValue`,
`ReleaseSelCriteria_PreferThisRelForAttackID`.
The attack routine registers `LoopCrossfadeLengthInSrcSampleMs`, then `UniqueID`, `LayerID`,
`SampleID`, `LoadSampleRange_StartPositionTypeCode`, `LoadSampleRange_StartPositionValue`,
`LoadSampleRange_EndPositionTypeCode`, `LoadSampleRange_EndPositionValue`,
`AttackSelCriteria_HighestVelocity`, `AttackSelCriteria_MinTimeSincePrevPipeCloseMs`, `AttackSelCriteria_HighestCtsCtrlValue`.

**Letters checked against the data (D3):**

| Table | Letter | Melcer data | Meaning | Confidence |
|---|---|---|---|---|
| both | a | unique per row | UniqueID | Strong (order + data) |
| both | b | every value is a layer ID | LayerID | Confirmed (data) |
| both | c | every value is a sample ID | SampleID | Confirmed (data) |
| release | q | 60/140/280 on exactly the rows whose file is in R00060/R00140/R00280; absent for R99999 | ReleaseSelCriteria_LatestKeyReleaseTimeMs | Confirmed (one row has q=30 in R00060: an inconsistency in the sample set) |
| attack | f | 7 on exactly the 1,148 attacks of sustaining layers | LoadSampleRange_EndPositionTypeCode (6th registered) | Strong; meaning of code 7 unresolved |
| both | d | 4 on every row (2 release rows omit it) | LoadSampleRange_StartPositionTypeCode by order | Plausible; meaning of code 4 unresolved |
| release | e | 1 on the 2 ambient-noise release rows | LoadSampleRange_StartPositionValue by order | Plausible |
| attack | k | 5, or 20 on the 2 ambient-noise samples | possibly LoopCrossfadeLengthInSrcSampleMs | Unresolved: the code order doesn't place it |
| release | k | N on every row | a Y/N field of the release block, likely ScaleAmplitudeAutomatically | Unresolved |

**Release crossfade and phase alignment.** Counting back from `q` through the release block, in
registration order, gives these letters:

- `ReleaseCrossfadeLengthMs` is `n` if `o` is skipped (as in the layer table), or `o` if it isn't.
- `PhaseAlignAutomatically` is `m` or `n` on the same two assumptions.

No release row in Melcer has any of `l`, `m`, `n`, `o` or `p`. Under either assumption, **Melcer
leaves the release crossfade length and phase alignment unset**, so Hauptwerk's built-in defaults
apply. Those values are not documented and not in the data. Nothing in Melcer's rows specifies a release
envelope. The `LoadSampleRange` start and end codes (`d` = 4, `f` = 7) may affect where playback
starts or ends, but their code meanings are unresolved. Organium plays each file from frame 0 and
each sustaining attack to its loop and release marker, as before.

Conclusion: no verified metadata specifies the transition, so the experimentally validated 10 ms
crossfade is Organium's default (section 3). Phase alignment, which Hauptwerk documents, is not
implemented. It would reduce the level swings that remain (section 4).

## 3. Correction

`organium.audio.ReleaseTransition` holds the transition. `VoiceEngine` applies it:

1. **Symmetric crossfade.** At note-off the release voice starts at envelope 0 and rises at the rate
   the sustain falls, so the two envelopes always sum to 1. On reaching 1, the release continues at
   full level on the fast mixing path.
2. **10 ms when a release sample exists.** In the diagnostic A/B (6 stops x 4 note lengths), 5, 10,
   20 and 50 ms all removed the click. Cancellation grows with the overlap: Gedackt 8' dips -8.9 dB at
   10 ms and -20 dB at 50 ms. 10 ms is the shortest length with no click in every case.
3. **50 ms fade-out without a release sample,** as before. The same 50 ms applies if no voice can be
   found for the release.
4. **Voice stealing protects releases that are fading in.** The stealing policy, when every voice is busy:
   - A new note or release takes a free voice if there is one.
   - Otherwise it steals the releasing voice with the lowest envelope among those not fading in (sustains
     fading out, settled tails).
   - Only if every releasing voice is fading in does it steal the quietest of those, which is the newest.
   - A sustaining voice is never stolen. A note is dropped, and counted, only when every voice is
     sustaining. That was the case before too, so the protection cannot starve a note-on.
   - Ties go to the lowest voice index, so the choice is deterministic.

   The release voice is now started while the old voice is still sustaining, so stealing can never take
   the voice being released.
5. **No allocation in the callback.** The existing per-voice arrays are used; nothing new is allocated.
6. **Baselines.** `regression/stage-0` is unchanged. With `ReleaseTransition.STAGE0`, the old
   behaviour, all 7 scenarios are still bit-identical, which shows nothing else in the engine changed.
   `regression/stage-1` was recorded with the new default. Each scenario matches stage-0 exactly up to
   its first release event, then differs:

| Scenario | first difference | first release event in the script |
|---|---|---|
| mono-sustain-release | block 200 | note-off at block 200 |
| embedded-release | block 120 | note-off at block 120 |
| multiple-releases-by-duration | block 5 | first (short) note-off |
| retrigger | block 30 | retrigger releases the sounding voice |
| stereo-pitched | block 150 | first note-off |
| polyphony-stealing | block 38 | first note-off batch |
| console-stop-changes | block 200 | stop change under held keys |

Baselines now carry a `TRANSITION` file. `RenderRegression compare` uses the transition a baseline
was recorded with (stage-0 has none, so `STAGE0`).

## 4. Tests and measurements

**`test/organium/audio/ReleaseTransitionCheck`** (synthetic pipes, 30 checks, all pass):

- **Discontinuities:** 192 note-offs (mono and stereo, 24 phases, 4 note lengths). The worst step is
  1.07x the sustain's own, with a limit of 1.5x. The old transition gives 35.7x, which shows the check
  detects the click.
- **Crossfade shape:** the release reaches full level after 480 frames (10 ms), and the two envelopes
  sum to 1 within 7e-6.
- **Fade-out without a release sample:** 2,400 frames (50 ms).
- **Release selection:** 8 of 8 note lengths around the 60/140/280 ms limits choose the expected release.
- **Repeated notes:** 60 retriggers and fast on/offs on one key. The worst step is 1.17x what a tone of
  that pitch and the local level allows. The key never has two sustaining voices, and nothing is left
  sounding afterwards.
- **Stealing:** a new note doesn't take a fading-in release; it falls back to one rather than being
  dropped; a note is dropped only when all voices sustain; the outcome is deterministic.
- **High-polyphony churn:** 20,000 random events on 32 voices. No note-on was dropped while a releasing
  voice was available, every envelope stays within [0, 1], `render()` allocates 0 bytes, and nothing
  keeps sounding at the end.

**Melcer, before (`STAGE0`) and after (default),** for 6 stops x 4 note lengths, middle C (`ReleaseClickProbe`):

| Stop | worst step at note-off, before | after | level in the 60 ms after note-off, before | after |
|---|---|---|---|---|
| I Nachthorn 8' | 18.4x | 0.9x | -5.4 / +6.3 dB | -4.1 / +2.7 dB |
| I Prinzipal 4' | 8.5x | 1.0x | -4.4 / +8.9 | -4.4 / +6.3 |
| II Gedackt 8' | 10.6x | 0.8x | -13.9 / +1.6 | -8.9 / +1.7 |
| P Subbass 16' | 45.3x | 0.9x | -6.9 / +6.3 | -5.4 / +11.6 |
| I Trompete 8' | 2.9x | 0.9x | -7.0 / +3.1 | -7.8 / -0.1 |
| I Mixtur 1 1/3' 4f | 4.2x | 1.0x | -7.0 / +5.5 | -7.1 / +1.3 |

The click is gone in every case. The remaining level swing is partly the release recordings' own level
and partly the unaligned phases, which phase alignment would address. It isn't a step.

**Render cost** (`RenderRegression bench`). The previous engine was built from commit 0a9a4f5 in a
separate worktree and run alternately with the new one, 3 runs each; medians are of rounds 1-6.

| | previous engine | new engine |
|---|---|---|
| steady, 150 voices | 152.4 us/block | 154.6 us/block (within noise) |
| churn, 256 voices, 8 offs + 8 ons per block, STAGE0 transition | 283.2 us/block | 282.8 us/block |
| churn, same, new default transition | | 288.8 us/block (+2%: releases spend 10 ms on the fading path) |

`render()` allocated 0 bytes in steady state in every run. A one-time allocation of 896 bytes in a
single render call was seen in round 0 of the STAGE0 churn and in 2 of 7 runs of the new check. It
was not seen in the last 5 runs. It belongs to the same class as the one-time 336-392 bytes seen in
the live runs before this change: it happens with the old transition too, and the JVM log shows
`unstable_if` deoptimizations of `mixVoice`/`mixVoiceFading` (code recompiled when a branch is first taken).

**Live trials** (Melcer through ASIO, 30 s, `research/live/run-13`, `run-14a`-`d`):

| Run | Transition | Late callbacks (> 8 ms) | Worst gap | Worst render |
|---|---|---|---|---|
| 14a | new default | 19 | 12.72 ms | 2.09 ms |
| 14b | old (STAGE0) | 7 | 10.50 ms | 0.26 ms |
| 14c | new default | 9 | 9.46 ms | 0.72 ms |
| 14d | old (STAGE0) | 13 | 12.32 ms | 0.23 ms |

Both transitions show late callbacks of 8-13 ms, so they are not caused by this change. On
2026-11-08 there were none in 17 trials. On this day the system started with 3.4 GB available
(against 5.1 GB then) and minimum free RAM during play was 0.65-0.99 GB (against 2.3-2.5 GB). This
is a separate, open issue: probably memory pressure from other programs.

## 5. Open

- The meanings of the type codes `d` = 4 and `f` = 7, attack `k` and release `k` are unresolved.
- Hauptwerk's default release crossfade length is unknown.
- Phase-aligned release crossfades are not implemented.
- The late callbacks in today's live trials (section 4) are not explained yet.
