# Melcer Chamber Music Hall: compatibility preflight

Date: 2026-11-07. Package: `D:\MelcerChamberMusicHall.CompPkg.Hauptwerk.rar` (2.93 GB, Hauptwerk component
package by Piotr Grabowski). Extracted, unchanged, to `D:\OrganiumSampleSets` (outside the repository;
UnRAR verified every file's CRC). Tools: `test/organium/OrganPreflight.java`, `SampleDecodeCheck.java`.
No parser or engine code was changed for this preflight.

## Package contents
- `OrganDefinitions/`: `Melcer Chamber Music Hall.Organ_Hauptwerk_xml` (standard; the target) and a
  `(requires wind model)` variant (wind model: Hauptwerk Advanced Edition feature).
- `OrganInstallationPackages/000696/`: 5,979 WAV files (4.9 GB), 72 PNG console images, an info PDF.
  15 pipe ranks, 7 "Tremmed" ranks, key/stop action noise ranks; releases in `R00060`, `R00140`,
  `R00280`, `R99999` folders (by note length).

## Sample format: supported (verified by decoding every used file)
- All files are uncompressed **PCM WAV, 24-bit, stereo, 48 kHz**, with `smpl` loops and `cue` release
  markers; some releases carry an extra `_PMX` (XMP metadata) chunk, which is skipped.
  No WavPack, no encryption, no `.hbw`.
- All **5,689** files the organ references decode through `WavReader` (8 s, 0 failures). 1,148 of 1,480
  attacks are looped (the rest are action noises).
- Organium stores samples as 16-bit, so the 24-bit sources lose resolution (to be revisited).
- Stereo samples play as recorded; the measured positioning applies to mono samples only.

## Organ structure: parsed without warnings
3 keyboards (Pedal 36–65, Hauptwerk 36–91, Brustwerk 36–91), 18 stops, 3 couplers (HW/PED, BW/PED,
BW/HW), 31 ranks, 1,480 pipes. Every stop is controlled by a switch that is **displayed and clickable**
on the console (no switch linkage in between), so drawstops in Organium's GUI reach the stops directly.
Release selection by note length (60/140/280 ms, unlimited) matches Organium's `ReleaseSet`.
The definition is valid UTF-8 (the "�" seen earlier was the Windows console code page only).

## Not supported yet (Hauptwerk tables with rows Organium does not read)

| Feature | Melcer data | Effect in Organium now |
|---------|-------------|------------------------|
| Tremulant by alternate ranks | switch 44 "Tremulant"; Brustwerk StopRanks carry `m` = 44, `p` = alternate rank 16–22 ("II Tremmed: …") | tremulant switch does nothing; the 7 tremmed ranks are loaded but never play |
| Swell box | `Enclosure` 1, `EnclosurePipe` 750 (all Brustwerk ranks), 4 continuous controls | Brustwerk plays unenclosed |
| Action noises | key/stop action ranks with 2 alternating attack samples; `DivisionInput` 142, `SwitchLinkage` 308 | noises not triggered; only the first attack sample is used |
| Combinations | `Combination` 1, `CombinationElement` 22 | no combination pistons |

The StopRank tags `m`, `n`, `p` are inferred from Melcer's data, not yet confirmed. Per the
project rule they need evidence (schema order or a probe) before the parser uses them.

## Memory: does not fit with current settings
- Decoded size as Organium loads it today: **3,179 MiB** (including 0.9 GB of tremmed ranks that
  cannot play yet).
- This machine: 7.9 GB RAM, 2.4 GB free (with Hauptwerk running); Java's default maximum heap here
  is 2.0 GB. Loading as-is would fail with OutOfMemoryError.
- Options: load only ranks that can sound (−0.9 GB until the tremulant exists), run with a larger
  heap (e.g. `-Xmx3500m`) after closing Hauptwerk, and later keep releases or attacks on disk
  (streaming) instead of all in RAM.
