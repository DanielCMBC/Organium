# Stereo positioning: performance and accuracy report

Date: 2026-10-31. Covers stages 1–2 of the audio work. Evidence: `research/hauptwerk-gain-pan-report.md`
(sections 3e–3f), raw data in `research/hauptwerk-probe/`.

## What was built

| Stage | Change | Rollback point |
|-------|--------|----------------|
| 0 | baseline (before any audio change) | `rollback/stage-0-baseline` |
| 1 | per-voice left/right gains in `VoiceEngine` and `CommandQueue`; old one-gain `noteOn` kept | `rollback/stage-1-voice-gains` |
| 2 | pipe positions parsed (`Organ.Pipe.x/y/z`); `organium.organ.spatial` (`PanLaw`, `MeasuredPanTable`, generated `HauptwerkPanData`); per-pipe gains computed in `Console` at load; `App --pan=hauptwerk\|centred` | `rollback/stage-2-pan-table` |

Each rollback point is a copy of `src/` (and `test/`) with a SHA-256 manifest. The project has no git
repository. The GUI (`src/organium/ui`) is unchanged since stage 0 (verified with `diff`).

Design: `VoiceEngine` knows only two gains per voice. The model lives in `organium.organ.spatial`
behind `PanLaw` and runs once per pipe when `Console` is constructed, never on the audio thread.
Replacing the model means another `PanLaw` implementation; the mixer is untouched.

Behaviour:
- **Mono pipes:** gL = g·(1 − p), gR = g·(1 + p), where g is the organ gain and p = `PanLaw.pan(x, y, z)`.
- **Stereo samples:** play as recorded (p = 0). Hauptwerk documents positions as applying to mono
  recordings, and its handling of stereo samples hasn't been measured.
- **Release samples:** inherit their voice's gains.

## Equivalence with the previous engine (`test/organium/RenderRegression.java`)
Seven deterministic scenarios, recorded on stage 0, compared sample for sample:
mono sustain + release; stereo samples at several pitches; embedded release; three releases chosen
by note length; retrigger; 360 notes into 256 voices (stealing, all-notes-off); and the Polyphony
Testing Organ through `Console` with a held chord while its exclusive stops switch 10 → 20 → 10 ranks.

| Build | Result |
|-------|--------|
| stage 1 (equal gains) | **all 7 bit-identical** |
| stage 2, `PanLaw.CENTRED` | **all 7 bit-identical** |

`test/organium/GainCheck.java`: with unequal gains, each channel equals the unity rendering times
its gain, to 1.2e-7 (float rounding), for mono and stereo, through attack, loop, release crossfade
and release tail.

## Real-time performance (`RenderRegression bench`: 150 voices, mono and stereo, crossfades)

| Build | ns per voice-frame | µs per 256-frame block | bytes allocated in 3000 blocks |
|-------|--------------------|------------------------|-------------------------------|
| stage 0 | 3.80 | 146 | 0 |
| stage 1 | 3.88 | 149 | 0 |
| stage 2 | 3.87 | 149 | 0 |

Cost +2% (one extra multiply per mono frame), allocation still zero. A 256-frame block at 48 kHz
lasts 5333 µs. Load-time cost of the table: 30 ns per pipe (`PanTableCheck`), about 0.05 ms for
St Anne's 1594 pipes.

## Accuracy against Hauptwerk
Per-channel gain error = Organium's level of each channel relative to the pipe's centre, minus
Hauptwerk's, in dB. Organium renders were made offline from the same organ definitions through its
own parser, `Console` and engine (`test/organium/HauptwerkComparison.java`). None of the held-out
points were used to build the table.

| Test set | Points | RMS | Max |
|----------|--------|-----|-----|
| random held-out points (grid-09/10) | 66 | 0.019 dB | 0.061 dB |
| X sweeps at Y = 1 / 3 / 6 m | 99 | 0.0045–0.013 dB | 0.040 dB |
| front/back and Y = 0 organ | 33 | 0.022 dB | 0.122 dB (at (0.05, 0)) |
| Y sweep, inside the domain (Y ≤ 8 m) | 16 | | 0.039 dB |
| Polyphony Testing Organ (native Milan, 10 ranks, different samples) | 33 | 0.011 dB | 0.037 dB |
| Y sweep beyond the domain (8 < Y ≤ 16.25 m, clamped) | 17 | 0.19 dB | 0.41 dB |

Table properties, checked exactly (`PanTableCheck`): X = 0 centred, p(−X) = −p(X), Z ignored,
continuous along X, Y = 0 row separate, edges clamped. These are level errors of a few hundredths
of a dB, far below audibility (around 0.5–1 dB).

## Headroom
- **Largest pan in the table is |p| = 0.438**, so one channel can reach 1.44× (+3.15 dB) of the
  centred level; the constant-sum law never exceeds 2× (+6 dB).
- Both outputs clamp to full scale before conversion (no wrap-around); the mix itself is float.
- **Organium plays 10.05 dB louder than Hauptwerk** for the same organ, because Hauptwerk applies its
  default −10 dB output trim and Organium has no equivalent. Loud registrations therefore reach
  clipping about 10 dB sooner than in Hauptwerk. Recommended next: a master trim setting
  (default 0 dB to keep today's output, or −10 dB to match Hauptwerk). Not done here.

## Known limitations (documented, not hidden)
- The table is an empirical model of Hauptwerk 4.2.1 Free Edition with one sample at 44.1 kHz;
  it is not Hauptwerk's formula.
- Stereo samples are not positioned (unmeasured in Hauptwerk).
- Per-pipe level (`AmpLvl_LevelAdjustDecibels`, layer `h`), user voicing and stereo balance are not
  applied yet: level is confirmed but outside this stage; voicing and balance remain deferred
  (Advanced Edition).
- Outside X ±4 m / Y −4…8 m, positions are clamped (error up to 0.41 dB at Y = 16 m).

## Addendum 2026-11-07: master trim
`VoiceEngine.setOutputTrimDecibels` scales the finished mix before the outputs clip to full scale.
`App --trim=dB` defaults to −10 dB, Hauptwerk's default output level adjustment (`--trim=0` gives the
previous level). Engine default 0 dB: all engine regressions remain bit-identical; −10 dB equals the
output × 0.316228 to 1.7e-8 (`GainCheck`). Render cost unchanged (3.8 ns per voice-frame, 0 bytes).

Absolute level against Hauptwerk with −10 dB trim, single-rank test organs (grid-09, grid-10, x-y3):
rms 0.027 dB, max 0.051 dB.

Open item (hypothesis, not verified): on the Polyphony Testing Organ, where ten ranks play the same
sample per note, Organium is 3.2–10.3 dB louder (mean 7.9). Organium starts identical pipes in phase,
so they add coherently (up to +20 dB for ten); if Hauptwerk decorrelates them (it has random tuning
parameters), they add by power (+10 dB), giving a difference of at most 10 dB, as observed. Pan
positions are unaffected (0.011 dB). Little impact on organs whose ranks have their own samples.
