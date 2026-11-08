# Hauptwerk 4.2.1 gain, balance and positioning — investigation report

Date: 2026-10-31. Hauptwerk version on this machine: 4.2.1.3 (`InternalResources/FileLocations`).
Status: investigation only. No Organium audio code has been changed.

Evidence levels used below:
- **Confirmed**: directly stated by Hauptwerk's documentation, or read from Hauptwerk's own schema
  code *and* consistent with the data in both installed organ definitions.
- **Strong**: one independent line of evidence plus consistent data, not yet tested end to end.
- **Hypothesis**: plausible, not yet supported by evidence. Must not be implemented as fact.
- **Unresolved**: not known.

## 1. Sources and methods

| # | Source | Method |
|---|--------|--------|
| D1 | `Documentation/HauptwerkInstallationAndUserGuide.pdf` (4.2.1) | Text extracted from the PDF streams and searched |
| D2 | `Documentation/HauptwerkCustomOrganDesignModuleUserGuide.pdf` (4.2.1) | Same |
| D3 | St. Anne's Moseley and Polyphony Testing Organ definitions | Tag/value statistics; values against note number |
| D4 | `Hauptwerk.exe` (x64) | Read-only scan: every RIP-relative `LEA` that loads a string, giving the order in which each schema-registration routine names its fields, plus each field's storage offset and registration type. No code was copied or patched. |
| D5 | `C:\Hauptwerk\HauptwerkUserData` | Empty: Hauptwerk has never saved settings or voicing here, so there is nothing to compare yet. |

Tools: IDA is not installed (Downloads has only an IDA Free 9.6 licence file, no program). The
string-reference scan in D4 stood in for it. x64dbg is present in Downloads but was not used.

## 2. Findings

### 2.1 Organ-definition parameters vs. user voicing — Confirmed

- The organ definition's **pipe layer table** (`Pipe_SoundEngine01_Layer`) has its own amplitude
  fields. In D4 registration order they are: `AmpLvl_SoundMeanAmpLevelPreScaledForVirtOutPos`,
  `AmpLvl_LevelAdjustDecibels`, `AmpLvl_VelocitySensitivityMaxAttenuationDecibels`, …,
  `AmpLvl_WindModelModDepthAdjustDecibels`, `AmpLvl_TremulantModDepthAdjustDecibels`,
  `AmpLvl_EnclosureModDepthAdjustDecibels`, `AmpLvl_StereoBalanceAdjustPercent`, then the
  `PitchLvl_*`, `HarmonicShaping_*` and `VoicingEQ01_*` fields.
- **User voicing** is a separate record type, `Voicing_Pipe_SoundEngine01_Layer` (D4), keyed by
  `LayerID`, holding the same adjustments (`AmpLvl_LevelAdjustDecibels`, …,
  `AmpLvl_StereoBalanceAdjustPercent`, `PitchLvl_TuningAdjustPercentSemitones`, …).
- D1 defines the voicing values as **relative to the sample-set creator's values**: decibel
  sliders are offsets from the pipe's default; percentage sliders are relative, with 100 % meaning
  the creator's value. Adjustments exist "for each pipe and layer". The rank-wide `MSTR` and
  octave `QUICK` sliders just move the per-pipe sliders, so voicing is stored per pipe and layer.

Implication: the effective pipe level is the creator's level (organ definition) combined with the
user's offset (voicing file), applied per layer.

### 2.2 Rank level vs. pipe level — Confirmed

- The organ-definition **Rank** table has only `RankID`, `Name` and `SoundEngine01_Layer1Desc` to
  `…Layer8Desc` (D4). Both organs match: tags a, b, c, with c = "Main" (layer 1 description).
  **There is no rank-level gain or pan in an organ definition.**
- Rank-level amplitude exists only in the *custom organ* format:
  `Amp_PipeMIDINoteNum036_LevelAdjustDecibels` / `…096…`, "projected linearly across the compass"
  (D2). The custom-organ compiler turns these into per-pipe values in the compiled definition.

### 2.3 Stereo balance semantics — Confirmed endpoints, curve unresolved

D1: "A stereo balance of -100 percent means that the left channel will be heard at twice its
normal amplitude, and the right channel will not be heard at all. +100 percent is the opposite,
and the default value of 0 percent means that the two stereo channels will be heard at the default
amplitudes". It only has an effect on stereo outputs.

- Confirmed points: b = -100 → (L ×2, R ×0); b = 0 → (×1, ×1); b = +100 → (L ×0, R ×2).
- Hypothesis: linear in between, i.e. L = 1 − b/100, R = 1 + b/100. Untested.

### 2.4 Spatial position of pipes — X Strong, Y/Z Hypothesis, mixing law Unresolved

- The pipe table has `VirtualOutputPos_XPosMetres`, `…YPosMetres`, `…ZPosMetres` (D4).
- D2: positions are "used when playing mono recordings in stereo or mono/stereo recordings in
  surround/3D formats, synthesizing the remaining channels as required". They are generated from
  the rank's wind-chest layout code (1–6: C/C# divided bass outermost/innermost, C left/right;
  linear bass left/right) and distance from the listener.
- **Pipe tag `n` is the X position** (Strong). St Anne's Sub Bass 16 runs −3.0, +3.0, −2.9, +2.9 …,
  exactly C/C# divided with bass outermost. Its Open Diapason 16 runs linearly from 3.0 m. Every
  Polyphony rank alternates sides, shrinking from ±2.13 m.
- Tags `p` and `q` are constant per rank (4.0 and −1.0 for St Anne's pedal ranks, 3.0 for
  Polyphony) and follow `n` in the same way Y and Z follow X (the letter o is never used as a
  field, since `<o>` is the row element). Hypothesis: p = Y (distance), q = Z.
- The layer field `AmpLvl_SoundMeanAmpLevelPreScaledForVirtOutPos` suggests the stored level is
  normalised with the position applied. What exactly it means is unresolved.
- **The X/Y/Z → left/right gain law is unresolved** (pan law, distance attenuation, which axis).
- The installed Polyphony samples are **mono** (44.1 kHz, 16-bit). So Hauptwerk spreads that
  organ across the stereo field by pipe position, whereas Organium plays every mono pipe centred.

### 2.5 Compacted tag letters — partly resolved

For ImageSet, ImageSetInstance, Switch and TextStyle, the letters are the D4 registration order,
skipping registrations that have no storage (UI group headers such as `Tile`, `Transparent`,
`Displayed`). This was checked field by field against the data. TextInstance is a
counterexample: its letters follow a different, apparently version-stable order. So letters
cannot be read off the registration order in general, and **the layer amplitude tags are
unresolved**. They are not guessed here.

### 2.6 Corrections to Organium's existing parser (found during this work)

Confirmed by D4 order plus data (see `src/organium/io/OrganParser.java`):

| Table | Tag | Organium assumed | Actually |
|-------|-----|------------------|----------|
| TextStyle | n | horizontal alignment, 1 = centred | `VerticalAlignmentCode` (`m` = `HorizontalAlignmentCode`, always omitted) |
| ImageSetInstance | i, j | tile fill width/height | `RightXPosPixelsIfTiling`, `BottomYPosPixelsIfTiling` (edges) |
| ImageSet | g, i (and f, h) | width, height | `ClickableArea{Left,Right,Top,Bottom}RelativeXPosPixels`; width/height are d, e |
| Switch | i | clickable (inferred) | `Clickable`, now confirmed; also d `Latching`, g/h `AccessibleForInput/Output` |

Effects today: St Anne's tiled panels at y=40 extend 40 px too far. Switch hit boxes treat the
clickable right/bottom edges as width/height and ignore the left/top edges, so controls such as
St Anne's touch-screen pistons (clickable area 3–34 × 7–53) get a box that's too large. The
Polyphony labels render correctly, but for the wrong reason.

## 3. Remaining questions and how to settle them

| Question | Proposed method | Needs |
|----------|-----------------|-------|
| Q1 Which layer tag is `AmpLvl_LevelAdjustDecibels` (and wind/tremulant depth, tuning)? Is p = Y? | **Experiment 1**: Hauptwerk compiles `hauptwerk-probe/OrganiumProbe.CustomOrgan_Hauptwerk_xml`, whose values carry unique signatures, then `decode_probe.py` matches each compiled tag to its source parameter. Also confirms the layout-code → X mapping. | You load it once in Hauptwerk (Organ → Load custom organ). |
| Q2 How and where is user voicing stored? | **Experiment 2**: load Polyphony; set one pipe to amplitude +3.0 dB and balance +50 %; save; diff `HauptwerkUserData` before and after. | You make the voicing change in Hauptwerk. |
| Q3 Balance curve between −100 and +100 % | Record Hauptwerk's output for one pipe at −100, −50, 0, +50, +100 % and compare L/R RMS. | A way to capture output (Hauptwerk's recorder if your edition has it, or a loopback device). |
| Q4 Position → L/R gain law | Same capture with probe ranks of different layout and distance (mono samples). | Same. |
| Q5 `AmpLvl_SoundMeanAmpLevelPreScaledForVirtOutPos` | Values from Experiment 1 against distance/position; static analysis only if still unclear. | Experiment 1 first. |

Dynamic analysis (x64dbg) is held back until Experiments 1–2 are done. Hauptwerk is
licence-protected, and a debugger should only be attached, read-only, if the behavioural route
can't answer Q3–Q5.

## 3a. Experiment log

Snapshots are in `hauptwerk-probe/snapshots/` (`snapshot.py`: SHA-256 and size of every file
under `C:\Hauptwerk`, copies of the text/XML files only, so they detect changes but are not a
full backup).

- **00-before-first-run** (2026-10-31 09:48): 757 files. User data empty; no Hauptwerk registry keys.
- **00b-after-first-launch**: Hauptwerk's first launch (09:50) created `HauptwerkUserData`
  (`Config0-GeneralSettings/Config.Config_Hauptwerk_xml`, logs, copies of the example custom
  organs). It also auto-installed St. Anne's (package 10) from `AutoInstall.CompPkg_Hauptwerk_rar`,
  then **deleted that 2.2 GB archive**. Facts from the log: licence = Free Edition; outputs
  available include a VB-Audio Virtual Cable, usable for digital capture in Phase 3.
- St. Anne's samples are `.hbw` files (4,501), Hauptwerk's own sample format, not WAV. Organium
  cannot read them, and they will not be decoded (no bypassing of sample encryption). Phase 3
  can still use St. Anne's through Hauptwerk's audio output.
- **Paused**: the log shows the running executable was
  `D:\Hauptwerk\Hauptwerk 4.2.1.003 Win32,64\No chime\64\Hauptwerk.exe`. That file is the
  official binary (same size) with 18,168 bytes changed, distributed as `No chime.rar`. It appears
  to remove the Free Edition chime, which is a licensing limitation. Experiments continue only
  with the unmodified `C:\Program Files\Hauptwerk Virtual Pipe Organ\Hauptwerk.exe` (SHA-256
  392a9c55e114147851bfcf857f8367153627760905cf0af9e89cf11500353318), which is also the binary
  every static finding above refers to.
- `decode_probe.py` was checked on a synthetic compiled organ with known answers: the true level
  tag is CONFIRMED, a decoy matching one rank is rejected, a tag matching 6 of 7 ranks is reported
  as PARTIAL rather than as a mapping, and the planted distance is CONFIRMED.

## 3b. Phase 1 result: custom organ probe (2026-10-31 10:02)

Run with the official executable (PID 9988, `C:\Program Files\...\Hauptwerk.exe`). The probe was
copied into `HauptwerkUserData\CustomOrganDefinitions` (the only folder Design tools → Load custom
organ lists) and loaded with Hauptwerk's default design options and rank routing. It compiled
with no errors or warnings. The only message was a generic low-disk-space prompt (9.5 GB free)
before loading samples, which was cancelled: only the compiled definition was needed, so no
sample cache was written. A copy is in `hauptwerk-probe/compiled/` (SHA-256 577AFF34…284A5),
with the full decoder output in `compiled/decode-report.txt`.

Files the probe load created (snapshot 01a → 01): the compiled
`OrganDefinitions\OrganiumProbe.Organ_Hauptwerk_xml`, `Config0-OrganCaches\OrganID800321.Cache_Hauptwerk_hbb`
(348 bytes), and `Config0-OrganSettings\OrganID800321Config.OrganConfig_Hauptwerk_xml` (42 KB,
the per-organ settings file and a likely home for voicing; see Phase 2).

### Mappings: Confirmed by experiment

Each row: one tag, and only that tag, holds the planted value on all 366 pipes of all 7 ranks
(rank r: value at pipe 36 and pipe 96, linear in between; tolerance 0.01).

| Custom-organ parameter | Planted (rank 0 → rank 6) | Compiled tag | Observed, rank 0 pipe 36 / pipe 91 |
|------------------------|---------------------------|--------------|-------------------------------------|
| `Amp_…_LevelAdjustDecibels` | −3.17…2.29 → −9.17…8.29 | Layer `h` | −3.17 / 1.835 |
| `Amp_…_WindModelModDepthAdjustDecibels` | 0.61…−0.73 → 6.61…−6.73 | Layer `n` | 0.61 / −0.6183 |
| `Amp_…_TremulantModDepthAdjustDecibels` | 0.83…−0.97 → 6.83…−6.97 | Layer `p` | 0.83 / −0.82 |
| `Pitch_…_TuningAdjustPercentSemitones` | 4.41…−4.53 → 10.41…−10.53 | Layer `t` | 4.41 / −3.785 |
| `Pitch_…_WindModelModDepthAdjustPercent` | 61…73 → 67…79 | Layer `x` | 61 / 72 |
| `Pitch_…_TremulantModDepthAdjustPercent` | 83…97 → 89…103 | Layer `y` | 83 / 95.83 |
| `HarmonicShaping_…_3rdAndUpperHarmLvlAdjDb` | −1.37…−1.59 → −7.37…−7.59 | Layer `c1` | −1.37 / −1.5717 |
| `Position_RankDistanceFromListenerMetres` | 1.25 + 0.5 r | Pipe `p` | 1.25 on every pipe |
| `Position_LayoutOfPipesOnWindchestCode` | r % 6 + 1 | Pipe `n` (X, metres) | pattern table below |

So **`AmpLvl_LevelAdjustDecibels` = layer tag `h`**: the creator's per-pipe level in dB, already
interpolated per pipe by the compiler. Rank-level values do not survive compilation.

Pipe X (`n`) by layout code. Pipe spacing is 0.035 m and the half-width is 0.035 × number of pipes
(56 pipes → 1.96 m; the 30-pipe pedal → 1.05 m). A value of 0 is omitted from the file.

| Code | Documented meaning | Observed X, pipes 36, 37, 38, … |
|------|--------------------|----------------------------------|
| 1 | C/C# divided, C left, bass outermost | +1.96, −1.925, +1.89, −1.855 … |
| 2 | C/C# divided, C right, bass outermost | −1.05, +1.015, −0.98 … |
| 3 | C/C# divided, C left, bass innermost | 0, −0.035, +0.07, −0.105 … |
| 4 | C/C# divided, C right, bass innermost | 0, +0.035, −0.07 … |
| 5 | linear, bass left | −1.96, −1.89, −1.82 … (0.07 per pipe) |
| 6 | linear, bass right | +1.96, +1.89 … |

**Ambiguity:** "left" means +X for the divided layouts (1, 3) but −X for the linear layout (5).
So the sign of X cannot be tied to a loudspeaker side from the definition alone; Phase 3 must
measure it.

### Letter rule: Strong (explains every confirmed layer tag)

Layer letters are the D4 registration order with no-storage header registrations dropped
(`Main_UseACtsControlAs…`, `AmpLvl_ScaleAccordingToAContinuousControl`,
`PitchLvl_ScaleAccordingTo…`, `PitchLvl_IncrementAccordingTo…`,
`HarmonicShaping_IncrementThirdAndUpperHarms…`) and the letter `o` skipped. It predicts all seven
confirmed layer tags exactly, and gives the rest:

a LayerID · b PipeID · c PipeLayerNumber · d Main_Sustaining · e AttackSel CC ID · f ReleaseSel CC ID ·
g AmpLvl_SoundMeanAmpLevelPreScaledForVirtOutPos (Y/N; Y in every probe and St Anne's pipe) ·
**h LevelAdjustDecibels** · i VelocitySensitivityMaxAttenuationDecibels · j InvertVelocitySensitivity ·
k PctOfReferenceAirFlowRateAtWhichPipeBeginsToSound · l …AtMaxAmplitude · m AirFlowDirectionCode ·
**n WindModelModDepth dB** · **p TremulantModDepth dB** · q EnclosureModDepth dB ·
**r StereoBalanceAdjustPercent** · s Amp ScalingContinuousControlID · **t DetuningPercentSemitones** ·
u PercentageOfPipeReferenceAirMassFlowRate · v PitchDecrementPctSemitonesAtThisFlowRate ·
w PitchLockingModeCode · **x WindModelModDepth %** · **y TremulantModDepth %** ·
z Pitch ScalingContinuousControlID · a1 IncrementingContinuousControlID · b1 IncrementingCtsCtrlSensitivity ·
**c1 HarmonicShaping 3rd+ level dB** · d1 … · i1 VoicingEQ01 transition kHz · j1 width % · k1 HF boost dB ·
l1 EnclosureFilters dB · m1/n1 ReverbTailTruncation · o1 AudioOut channel format · p1 sample resolution.

Bold = confirmed by experiment. The rest, including `r` (stereo balance), are rule-derived. None
of the installed organs sets `r`, so every pipe has the default balance. TextInstance is still
an exception to the rule.

Pipe Y (distance) = `p` is confirmed and X = `n` is confirmed. Z = `q` follows by the same
pattern (X, Y, Z consecutive, `o` skipped) but is never set by the compiler: Strong, not
confirmed.

## 3c. Phase 2 status: DEFERRED (not cancelled), blocked by edition

Edition timeline (from the log; corrects an earlier statement that the 09:57 session was Free
Edition):

| Launch | Executable | Mode |
|--------|------------|------|
| 09:50 | patched "No chime" build (not used for any experiment) | Free Edition |
| 09:57 | official | Evaluation: Basic Edition |
| 10:19 onward | official | Free Edition (chosen at the startup prompt) |

Voicing is Advanced-only, so neither Basic nor Free has it. The Advanced Edition evaluation
remains a possible later route, subject to its licence conditions.

- The probe organ was loaded normally (Organ → Load organ; low-disk prompt answered Continue; small
  cache). Baseline snapshot **02-before-amplitude** was taken after loading.
- The Organ settings menu has no "Pipe and rank voicing" item. The user guide explains why: "Real-time
  voicing, multi-channel audio output and multi-monitor support are only available in the
  Advanced Edition of Hauptwerk", and the Free Edition has "all of the features of the Basic
  Edition" except its stated limits. **User voicing (amplitude and stereo balance) can't be set
  in the Free Edition**, so its storage can't be observed here.
- Documented and legitimate route: launching without a USB key offers to "evaluate one of the
  licensed editions", which gives the full Advanced Edition with a periodic chime. The licence
  allows evaluation versions only "for the purpose of evaluating Hauptwerk and only for a
  reasonable evaluation period". Whether to use it is the owner's decision.
- Also documented: voicing is stored "separately for each organ", and loading the probe created
  `Config0-OrganSettings\OrganID800321Config.OrganConfig_Hauptwerk_xml`. Hypothesis: voicing is
  stored there or alongside it. Unverified.
- Phase 3 notes: the Free Edition has an audio recorder (Recorder/Player → "Arm audio recording",
  observed). Position-based stereo placement of mono pipes can therefore be measured in the Free
  Edition with the probe organ, which uses all 6 layout codes. Measuring the stereo balance curve
  needs voicing, so it needs the Advanced Edition (evaluation) as well.

## 3d. Phase 3: spatial placement of mono pipes, measured (Free Edition, 2026-10-31 10:19–11:10)

### Setup and controls

- **Executable and edition**: official `Hauptwerk.exe`, Free Edition (log: "License: Free Edition";
  effective polyphony limit 256; wind supply model N).
- **Subject**: Polyphony Testing Organ (for 512 MB), a native Milan organ, stop "10 PIPES PER KEY"
  (10 ranks, engaged by default, the only stop on). Its definition gives every pipe of every rank
  the same values per note: X (`n`) = ±(2.135 − 0.035·(N−36)) m, alternating sign by semitone
  (C/C# divided); Y (`p`) = 3.0 m; Z (`q`) = 0; level `h` = 0 dB; balance `r` = 0 (omitted).
  Samples: the installed mono 44.1 kHz/16-bit `PolyphonyTestingOrganRank` files, one per note.
- **Triggering**: on-screen keys pressed with synthesized mouse input. Key positions were
  calibrated against Hauptwerk's own MIDI recording (`recordings/calib-poly*`): all 36 naturals
  36–96 on channel 2. Each take holds the 36 naturals in order for 2.5 s each, with 3.0 s gaps.
- **Fixed across takes**: master volume 100, trim −10.0 dB, speaker level 0.00 dB, stereo
  output, 44.1 kHz, same stop, same hold timings.
- **Recording**: Hauptwerk's built-in recorder (Recorder/Player, "Arm audio recording"). It writes
  a 2-channel WAV of the main output. **It preserves stereo** (2 channels, independent content).

Hauptwerk settings changed for this phase (both reversible; originals noted):
1. Main output device: ASIO4ALL v2 (channels 1/2, buffer 1024) → DirectSound "CABLE Input
   (VB-Audio Virtual Cable)" (buffer 2048), to rule out a stalled ASIO driver while diagnosing
   the probe's silence. The Polyphony organ was recorded only on the new device.
2. Recording format: 16-bit → 32-bit (float; WAV format tag 3). Take A was recorded at 16-bit,
   takes B and C at 32-bit.

### Measurement method

Per note: the block-median of 10 ms L/R power ratios over a 1.5 s sustain window (onset +0.5 s),
the window RMS per channel, peaks, and the same ratio over the release tail
(`analyze_audio.py`). Three recording artefacts were found and controlled for:
- **MIDI and WAV are not time-aligned** in Hauptwerk's recordings (~2.04 s offset), so notes are
  located from audio onsets, matched in order to the logged key holds.
- **The Free Edition plays a periodic chime**: 0.22 s, −63.1 dBFS peak region, bit-identical in
  both channels (unpanned), every **6.998 s** (least-squares fit over the takes). Chime blocks
  are excluded from every window, and a chime never counts as an onset. This chime was what
  produced an earlier false "L = R" reading, which is retracted.
- **16-bit recording biases quiet channels**: take A (16-bit) differs from B/C by up to 0.63 dB.
  Only the 32-bit takes are used for results.

### Results (`analysis/pan-measurements.csv`, `analysis/fit-report.txt`)

| Note | X (m) | L−R (dB), takes B = C | | Note | X (m) | L−R (dB) |
|------|-------|-----------------------|-|------|-------|----------|
| 36 | +2.135 | −6.2325 | | 41 | −1.960 | +6.0361 |
| 48 | +1.715 | −5.6705 | | 53 | −1.540 | +5.3387 |
| 60 | +1.295 | −4.7690 | | 65 | −1.120 | +4.2858 |
| 72 | +0.875 | −3.5055 | | 77 | −0.700 | +2.8802 |
| 84 | +0.455 | −1.9252 | | 89 | −0.280 | +1.2002 |
| 96 | +0.035 | −0.1512 | | 95 | −0.070 | +0.3023 |

(All 36 notes are in the CSV.)

**Confirmed (by measurement)**
- Hauptwerk places mono pipes in the stereo output according to the pipe's X position
  (`VirtualOutputPos_XPosMetres`, tag `n`), with no stereo balance or level set.
- **Sign: +X is right, −X is left** (listener's view of the output channels). This settles the
  sign ambiguity in the layout-code table.
- The left/right difference grows monotonically with |X|, from 0.15 dB at 0.035 m to 6.23 dB at
  2.135 m, and is antisymmetric (±X give mirrored values on one smooth curve).
- Per pipe, the gain ratio is **static**: the 10th, 50th and 90th percentiles of the per-block
  ratio are identical to 4 decimals for every note.
- **Deterministic**: two 32-bit takes give identical L−R to 0.0000 dB on all 36 notes.
- The release tail is placed with the same ratio as the sustain (e.g. note 36: −6.232 vs −6.2325).

**Inferred (fits, not proof)**
- No common one-parameter pan law fits: linear (p = X/W), sin/cos constant-power, angle-based
  (p = sin(atan(X/D))) and dB-linear all miss by 0.41–0.86 dB RMS.
- Several two-parameter families fit closely: distance^−a to two virtual points at ±S
  (S = 0.606 m, a = 3.83; 0.009 dB RMS, 0.023 dB max); scaled angle laws (0.027 dB); a scaled
  atan in dB (0.037 dB). None reaches the measurement repeatability (0.0000 dB), so **the exact
  law is not identified**. With Y fixed at 3 m, these families cannot be told apart.

**Unresolved**
- **Y (distance) and Z dependence**: every Polyphony pipe has Y = 3 m and Z = 0. The probe organ,
  built to vary Y (1.25–4.25 m) and layout, produced **no sound at all** in this edition; see
  below.
- **Absolute gains (how loudness splits between channels)**: recorded level relative to each
  sample's sustain-loop RMS scatters by up to ±3 dB between neighbouring notes (10 summed
  pipes, a different sample per note), so constant-power vs. constant-amplitude panning cannot
  be decided from these takes. The meaning of `AmpLvl_SoundMeanAmpLevelPreScaledForVirtOutPos`
  (Y on every pipe) is still open.
- **Stereo samples**: not tested (all installed WAV samples are mono; St Anne's uses Hauptwerk's
  `.hbw` format, which will not be decoded).
- **Stereo balance curve and per-pipe voicing storage**: need voicing (Advanced Edition); deferred.

### Probe organ silent in the Free Edition (limitation, cause not found)

Every probe take recorded exact digital silence, with stops visibly engaged and notes registered
in the MIDI recording. Ruled out by test: stop state (verified on screen before every take),
output device (also silent on DirectSound), the recorder (the Polyphony organ records sound on
the same settings), sample files and pitch metadata (same WAVs; smpl unity notes correct), and
wind supply. Probe v2 (`compiled/`; v1 kept in `compiled/v1-windmodel/`) sets
`WindModel_EnableWindModel=N`, so its pipes take the static 6" supply exactly like Polyphony's,
and it is still silent. The v2 compile reproduced all eight Phase 1 mappings. Remaining
differences from the Polyphony definition include the attack end-position code (`f` 7 vs 8) and
the omitted sample pitch-specification fields. Not pursued further.

## 3e. Controlled spatial experiments (2026-10-31 11:26–12:25)

### Settings restored first
Hauptwerk's main output is back to ASIO4ALL v2 (channels 001/002 "de HD Audio 1/2", buffer
1024, stereo, 0.00 dB) and the recording format is back to 16-bit (`Config.Config_Hauptwerk_xml`
rewritten 11:27; log: "ASIO audio device 'ASIO: ASIO4ALL v2' started at 44100 Hz … 1024"). A
check note through ASIO4ALL recorded normally (`recordings/check-07-asio*`). All 32-bit
recordings, snapshots and analysis files are kept. Every recording below is 16-bit.

### Why the probe organ was silent (Confirmed by experiment, mechanism Unresolved)

Each test changed one thing on a copy of the compiled probe (`variants/`; each copy has its own
organ ID 800322+ and file, and no original file was modified). Only the Pedal Bourdon or Swell
Open Diapason was tested, and every stop's state was screenshotted before recording.

| Variant | Change | Result |
|---------|--------|--------|
| v2 | static wind (`WindModel_EnableWindModel=N`) | silent |
| v3 | sample pitch stated (`Samples_RankBasePitch…=8` → Sample d/e/f like Polyphony) | silent |
| A | v3 + every pipe/layer/attack field set Polyphony-like | silent |
| B | A + Bourdon stop node latching, **engaged at load**, its linkage removed | **sounds** |
| C | A + stop nodes latching (linkages kept), stop clicked | silent |
| D | A + each stop driven directly by its tab, clicked | silent |
| E | D + Bourdon tab "engaged by default" | tab shows off after load; silent |
| F | D + all stop linkages removed, stop clicked | silent |
| G | F + Bourdon tab engaged at load | **sounds**; still sounds after clicking it off and on again |
| G | (same session) Open Diapason first engaged by click | silent |
| H | F + Open Diapason tab engaged at load | **sounds** (so the swell box is not the cause) |

~~Finding: a stop sounds only if it was engaged when the organ was loaded.~~ **Retracted; see 3f.**
The real cause: pressing the recorder's record button resets every switch to its default state.
Every test above changed stops *before* starting a recording, so the reset undid the change.

### Controlled spatial probe

`build_spatial_probe.py` writes test organs from variant F (organ IDs 800400–800405):
- One rank (Swell Open Diapason) engaged at load, out of the swell box, one pipe per key: no
  overlapping voices.
- Every pipe plays the same sample (`055-G.wav`, sustain-loop RMS −37.851 dBFS) at its original
  speed: one Sample row per pipe, stated pitch = the pipe's own note (method 3, harmonic 8).
- Neutral voicing on every pipe (no level, tuning, depth, harmonic or balance values). Organ
  level +10 dB, which together with the −10 dB trim gives a net 0 dB.
- 33 natural keys per organ carry the design; one variable changes at a time:

| Organ | Varied | Held fixed |
|-------|--------|------------|
| x-y1, x-y3, x-y6 | X −3.2…+3.2 m | Y = 1, 3, 6 m; Z = 0 |
| y-x1, y-x0 | Y 0.25…16.25 m | X = 1 m or 0; Z = 0 |
| z-x1 | Z −3.2…+3.2 m | X = 1 m, Y = 3 m |

Data: `analysis/spatial-dataset.csv` (197 pipes: X, Y, Z, distance, angle, L−R, pan parameter),
plus per-take CSVs `analysis/sp-*.csv` and recordings `recordings/sp-*`.

### Confirmed
1. **Constant-amplitude-sum (linear) pan law**: 20·log10(gL + gR) is the same for every pipe in
   every sweep, −31.866 dB ± 0.03 dB (X, Y and Z all varied). Power is not constant: it rises
   0.53 dB from centre to the sides at Y = 3 m.
2. **Unity centre gain**: at X = 0 each channel equals the sample's own level (−37.883 vs
   −37.851 dBFS, net organ gain 0 dB). So **gL = 1 − p, gR = 1 + p**, with p = 0 at the centre.
   This has the same form as the documented stereo-balance rule (−100 % → L ×2, R ×0).
3. **No distance attenuation**: the overall level is independent of X, Y and Z (from Y = 0.25 m to
   16.25 m). Distance changes only how the sound is split between the channels.
4. **Z is ignored** for stereo: L−R = −3.9224 dB at every Z from −3.2 to +3.2 m.
5. **Sign and symmetry**: +X is right; p(−X) = −p(X) (e.g. ±0.2 m → ∓0.8614 / ±0.8616 dB);
   X = 0 is exactly centred at every Y.
6. **p depends on both X and Y, and not on their ratio alone**: (0.2, 1), (0.6, 3) and (1.2, 6) share
   one angle but give −2.98, −2.50 and −2.37 dB. Along an X sweep, |p| peaks at X ≈ Y (7.73 dB at
   X = Y = 1 m; 6.60 dB at X = Y = 3 m) and then falls again.
7. **Repeatable and consistent**: a repeated x-y3 take matches to 0.0001 dB (L−R) and 0.02 dB
   (level). Crossing points of different sweeps agree. The earlier Polyphony dataset (10 ranks,
   different samples, 32-bit) matches the single-pipe probe within 0.006 dB at all 36 notes, so
   it stands as a verified reference.

### Not identified (no model forced)
- The function p(X, Y). Tested and rejected: functions of angle alone; and a separable form
  k(d)·sin(2θ) (k is not a single function of distance: at d ≈ 3.2 m, k = 0.427 at θ = 72° but
  0.369 at θ = 22°). The measured values cover the plane along six lines, enough to interpolate
  near those lines but not a full map.

## 3f. Stop-activation anomaly resolved; empirical pan table (2026-10-31 13:50–15:40)

### The anomaly: a recorder side effect (Confirmed)
Three states were separated: shown engaged (screenshot), samples loaded (log: sample memory is the
same for every variant, so all ranks are loaded), and voices sounding (recorded level and stereo
position, which identify the rank: rank 2 sits at X = +1.12 m, rank 4 at X = −0.84 m for note 60).
- On variant S1, every stop change made by mouse after loading left the sound unchanged, including
  turning the load-time stop *off* and a general cancel. So it was not a loading problem. After each
  recording, the display went back to the load-time state by itself.
- Native Polyphony organ, all in **one** recording: 10 ranks (load default) → −81.78/−76.54 dB;
  switched to 20 ranks during the recording → −77.89/−72.80 dB (+3.9 dB); back to 10 → −81.90/−76.66.
  **Stops engaged after loading do sound.**
- Direct check: 20 PIPES lit, record button pressed, and immediately 10 PIPES lit again
  (`recordings/pz-d1`, `pz-d2`). **Starting a recording resets the registration to the switches'
  defaults.** This is a property of the recorder, not a rule about stops.
- Consequences: the earlier datasets stay valid (their stop was on by default). The original probe
  was most likely never broken. Experiments must change stops after pressing record.
- No synthesized-input effect was found: keys and stop tabs both respond to the mouse input
  (`s1-*`, `pz-*` recordings and screenshots).

### Front/back and Y = 0 (Confirmed, `sp-sym-A`)
- Negative Y is **not** a mirror of positive Y: the side flips, with a different magnitude
  ((+0.5, +1) → L−R −6.21 dB; (+0.5, −1) → +3.13 dB). St Anne's places five Swell ranks at Y −2.3…−2.7.
- Y = 0 does not follow its neighbours: at X = 1, Y = −0.05 / 0 / +0.05 give −1.09 / −2.88 / −2.26 dB.
- Antisymmetry in X holds behind the listener too ((−2, −2.5) = −(+2, −2.5) exactly).
- A two-microphone directivity model was tried and fails (1.9 dB RMS); no formula is adopted.

### Grid, table and validation (`build_pan_table.py`, `analysis/pan-table*.csv`)
- Grid: X ∈ {0.0625 … 4.0} (17 columns, plus X = 0 ≡ 0) × Y ∈ {−4 … 8} (25 rows, dense near 0, Y = 0 its
  own row): 425 measured nodes in 15 organs (`spatial/designs/grid-*.csv`, `recordings/sp-grid-*`).
  A second pass added 5 small-X columns after the first validation showed the error concentrated
  there.
- Held out: 63 random points (seeded, `make_grid_designs.py`) plus all earlier sweeps, 227 points
  inside the domain. Repeats: grid-07/08 retaken, maximum difference 0.0002 dB.
- **Bilinear** (chosen): per-channel gain error rms **0.018 dB**, max **0.12 dB** (at (0.05, 0), the
  Y = 0 row); max 0.061 dB where |Y| ≥ 0.5 m. Cubic: rms 0.015, max 0.19 dB (overshoot at the ridge).
- Outside the domain (clamped): up to 0.41 dB at Y = 16.25 m (y-x1). No installed organ is outside.
- Centre exact (46 points), antisymmetry to 1.2e-5 (52 pairs).
- Process errors caught and corrected: two recording batches overlapped once (takes 04–06 redone,
  07/08 repeated and verified); one stuck key (grid-03 redone; the analyzer had rejected the point);
  the chime detector mistook centred pipes for chimes (fixed by a 0.35 s length limit; all takes
  re-analyzed).

## 4. Recommendation

Safe to implement now (no unconfirmed data involved):
1. **Engine**: per-voice left and right gains in `VoiceEngine`. For mono samples, one interpolated
   value is scaled by gL and gR; for stereo samples, the left channel by gL and the right by gR.
   The existing split into mono/stereo/fading loops keeps the frame loops free of branches and
   allocation. `CommandQueue` carries two gains instead of one. Test: with gL = gR the output must
   be bit-identical to the current engine.
2. **Model**: `Organ` gets `float[] pipeLeftGain`, `pipeRightGain`, computed at load time. For now
   these are the organ master gain on both sides, which reproduces today's sound exactly.
3. **Parser corrections** from 2.6.

Now supported by evidence:
4. **Per-pipe creator level**: layer tag `h` (`AmpLvl_LevelAdjustDecibels`, Confirmed in Phase 1)
   as a dB → linear factor on both channels. Its interaction with master gain and other factors
   in Hauptwerk's mix has not been measured; treat it as a relative per-pipe offset.

Still open, so not to be implemented as Hauptwerk behaviour yet:
5. **Mono positioning (Q4)**: the structure is now confirmed (3e): gL = 1 − p, gR = 1 + p, no
   distance attenuation, Z ignored, p antisymmetric in X and zero at X = 0. Only p(X, Y) itself is
   not identified. Until it is decided how to implement p(X, Y), keep mono pipes centred
   (p = 0), which is exactly Hauptwerk's behaviour at X = 0. Options for later:
   (a) measure p on a full X/Y grid (about 4 more probe organs) and interpolate from that table;
   (b) an independent function with the confirmed properties, documented as an approximation.
   This decision is the owner's.
6. **User voicing offsets and balance (Q2, Q3)**: deferred; need voicing (Advanced Edition).
7. **Stereo samples**: untested; keep "play as recorded".

Next experiments, in order of value:
- Y dependence: a native organ (not CODM-compiled) with mono samples and varied Y, or find why
  the probe is silent (e.g. give its Sample rows the same pitch-specification fields as the
  Polyphony definition).
- Absolute channel split: a stop with a single rank (all Polyphony stops sum 10 or 20 identical
  pipes) and a sample with a known, steady level.
