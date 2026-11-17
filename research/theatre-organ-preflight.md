# Theatre organ targets: Paramount 310 and Freedom Morton 3/10 (preflight)

Date: 2026-11-16. No Organium source code was changed. Nothing was decrypted, patched or bypassed.

**Decision (2026-11-16):** Freedom Morton 3/10 is Organium's theatre-organ target. Paramount is dropped
unless Paramount Organ Works authorises an unencrypted set.

## Paramount 310 (Paramount Organ Works): blocked by licensing and encryption

Source: official download `ParamountOrganWorks310v2x12.CompPkg_Hauptwerk_rar` (823,843,941 bytes, S3 link on
paramountorganworks.com/scripts/downloads.html). The download was stopped at about 80% once the evidence
below was clear. The licence and three files were extracted from the complete leading entries of the
archive; they are kept as evidence in `D:\OrganiumSampleSets\_downloads\p310peek` (outside the repo).

### Confirmed
- **Licence (`Licence.rtf`, Paramount Organ Works, 2010):** "The Product is licensed exclusively for use with
  Hauptwerk software from Milan Digital Audio … The Product has been encrypted and requires proper license key
  authorization to be enabled by Milan Digital Audio." Free of charge, but not licensed for an independent player.
- **Organ definition:** `OrganDefinitions/Paramount310.Organ_Hauptwerk_hbx` (2.2 MB), not the plain
  `.Organ_Hauptwerk_xml` that Organium reads.
- **Audio:** every sample is `.hbw` (for example `001111/Chrysoglott/060-C.hbw`, `001111/Clarinet/Attack/036-C.hbw`),
  not `.wav`.
- **Container:** the `.hbx` and both `.hbw` files start with `FF FF FF FF` `"HPW3"` `05 00 00 00`, and the rest is
  opaque. The measured entropy is 7.999–8.000 bits/byte, zlib can't shrink it (ratio 1.0003), and there is no RIFF
  or XML structure. RAR stored the files uncompressed. This matches the licence's statement that the files are
  encrypted.
- **Hauptwerk version:** v2.x packages are distributed "for Hauptwerk V". The paid 332 requires Hauptwerk 5.00 or
  later with an Advanced Edition licence (hauptwerk.com store page). The installed Hauptwerk here is 4.2.1.

### Inventory (public spec sheet only; the encrypted definition was not inspected)
From "Paramount 310-320 Specifications.pdf" (items marked 320-only are excluded):
- 3 manuals (Solo, Great, Accomp, plus Accomp 2nd touch) and Pedal.
- 10 ranks: English Horn 16 (85 pipes), Tuba Horn 16 (85), Diaphonic Diapason 16 (97), Tibia Clausa 16 (109),
  Gamba 16 (85), Concert Flute 16 (85), Orch Oboe 8 (61), Clarinet 8 (61), Gamba Celeste 8 (61) and Vox Humana 8 (61).
  Chambers are Main and Solo.
- Tuned percussion: Harp 49, Chrysoglott 44, Xylophone 33, Glockenspiel 37.
- Traps and effects: snare roll, tambourine, castanets, tom tom, cymbal, bass drum, crash, grand crash, triangle,
  train whistle and birds.
- Heavy unification: the same ranks appear at 16/8/4/2 2/3/2/1 3/5/1 1/3/1 on several divisions.
- Tremulants: Main, Solo and Clarinet. The vendor says all ranks use recorded tremulated samples.
- Also: re-iteration (Xylo/Glock), blower and tremulant-box noises, a combined swell pedal, a crescendo pedal,
  sostenuto, and pistons (15 per manual, 15 generals).

### Memory
Not estimated. The decrypted sample format (bit depth, mono or stereo) can't be determined without decryption.

### Conclusion
Organium cannot load Paramount 310 within its licensing rules. The only legitimate route is permission from
Paramount Organ Works, for example an authorised unencrypted development or interoperability set.

## Freedom Morton 3/10 (Melotone Sound Productions): promising, needs a real preflight

Sources: sites.google.com/view/melotone-sound-productions → Freedom Morton Series for Hauptwerk, Freedom Morton 3/10,
and Freedom Morton 3/10 Specification.

### Stated by the vendor (not yet verified against files)
- **Licence:** the samples are under CC BY-NC-SA 3.0 Unported: share and adapt for non-commercial use, with
  attribution and share-alike. There is no "Hauptwerk only" clause. The Hauptwerk ODF is "donationware" from
  Melotone, and its own terms are not stated.
- **Format:** "16-bit 48 kHz Mono (panned to Stereo positions in the sample set)", with recorded tremulant samples.
  Recorded just outside the swell shades, so the sound is semi-dry. Built with the Hauptwerk CODM, for Hauptwerk
  v4.2 or later.
- **Memory:** "approximately 1.15 GB of RAM" in Hauptwerk when fully loaded; it fits the Free Edition's 1.5 GB limit.
- **Distribution:** download links are emailed after an order form. **The Donationware order form is closed**
  ("no longer accepting responses", checked 2026-11-16). The Contact Us form is open.

### Inventory (from the specification page)
- Pedal, Accomp, Great and Solo, plus Pedal 2T, Accomp 2T, Great 2T and Solo 2T second-touch divisions.
- 10 ranks: Main (left) has Clarinet, Concert Flute, Tuba, Violin, Viole Celeste and Vox Humana; Solo (right) has
  Diaphonic Diapason, English Horn, Tibia Clausa and Orchestral Oboe. The same ranks as the Miditzer 216.
- Percussion: Chrysoglott, Xylophone, Glockenspiel and Chimes.
- Traps: tambourine, castanets, tom tom, wood block, snare, cymbal, bass drum, kettle drum, crash and triangle.
- Toe-piston effects: auto horn, fire gong, steam boat, siren, bird, train, fog horn, whistle, cow bell and
  Chinese gong.
- 7 tremulants: Main, Solo, Tibia, Vox, Clarinet, Tuba and English Horn.
- Re-iteration switches: tambourine, castanets, xylophone and glockenspiel.
- Melody couplers: "Accomp to Accomp2T Melody" and similar. Octave, sub-octave and unison-off couplers.
- A Contra Bourdon 32' stop.

### Expected Organium gaps (hypotheses until the ODF is parsed)

| Feature | Organium today | Note |
|---|---|---|
| WAV 16-bit 48 kHz mono, loops, release markers | supported | measured positioning applies to mono samples |
| Unification (one rank at many pitches) | supported | StopRank note offset |
| Octave and sub-octave couplers | supported | KeyAction transpose |
| Unison off | unknown | check how the ODF models it |
| Tremulants by alternate (tremmed) ranks | not implemented | same gap as Melcer; StopRank tags `m`/`p` are still unconfirmed |
| Swell boxes (2 chambers) | not implemented | same gap as Melcer |
| Second touch | not implemented | needs velocity or a second-contact MIDI input |
| Melody couplers | parsed, warned, not played | KeyAction kind 3 |
| Re-iterating percussion | not implemented | probably a Hauptwerk feature, needs evidence |
| Toe-piston effects, traps (one-shots) | unknown | may work as unlooped samples; trigger path to verify |
| Pistons and combinations | not implemented | same gap as Melcer |
| Memory (≈1.15 GB in Hauptwerk) | fits | Organium stores 16-bit; mono keeps it small |

### Next steps (done 2026-11-17, see below)
1. The user asks Melotone, through its Contact Us form, for a download link and confirms the ODF licence terms
   (Organium does not send this on the user's behalf).
2. Run the standard preflight once the package is in hand: `OrganPreflight`, `SampleDecodeCheck`, check for
   encryption, list unread tables and measure memory.

## Freedom Morton preflight on the real package (2026-11-17)

Package: `FreedomMorton3-17_Release_v1.02.CompPkg_Hauptwerk_rar` (784 MB, obtained by the user from Melotone).
Extracted unchanged to `D:\OrganiumSampleSets\_staging\FreedomMorton`: 4,591 files, 1.46 GB, every CRC verified.
Tools: `OrganPreflight`, `SampleDecodeCheck`, `ConsoleWindowCheck` and two read-only Python inventories. No code
was changed.

### Format and licence: confirmed usable
- Three plain-XML definitions (`FreedomMorton2-6`, `3-10` and `3-17.Organ_Hauptwerk_xml`, format 4.00, compacted
  rows) and plain RIFF WAV samples. **No encryption, no `.hbw`.**
- `Licence.rtf`: the ODFs are © 2013 Melotone Sound Productions and released as donationware. The WAV samples are
  CC BY-NC-SA 3.0, with two exceptions:
  - The Piano samples come from Mats Helgesson's Maestro Concert Grand: "You may not use these samples in another
    sample set or format without first obtaining permission".
  - The Vibraphone samples come from Dominique Lacaud's GrandOrgue set.

  Both exceptions are 3/17 features; the 3/10 uses neither. The licence restricts installation to "Hauptwerk 4.0
  (or higher)" but does not restrict use of the samples to Hauptwerk. Melotone's answer on independent players
  is still open.
- Attribution required: VTPO Yahoo group recordists, Joe Hardy, Lynn Walls, Graham Goode / Melotone, and Graham
  Wykes (jOrgan Morton skin, CC BY-NC-SA 3.0 AU) for the graphics.

### Freedom Morton 3/10: inventory (confirmed from the definition)
- **Keyboards:** Pedal (32 keys), Accomp, Great and Solo (61 keys), plus four second-touch keyboards (Pedal 2T,
  Accomp 2T, Great 2T, Solo2T). Eight further `CustPg1_*` keyboards are display keyboards only.
- **Stops and switches:** 193 stops, 892 switches, 273 clickable console switches, 1 display page (1260×905).
- **54 ranks:**
  - 9 voices are split into Bass (MIDI 24–47, never tremulated), main and "Tremulant" (separately recorded)
    ranks: Concert Flute, Diaphonic Diapason, Tibia Clausa, Tuba, Violin, Viol Celeste, Clarinet, Vox Humana
    and Orchestral Oboe. The last two have no Bass rank.
  - The English Horn (Wurlitzer) has no tremmed rank. It uses Hauptwerk's synthesised tremulant (`Tremulant`
    1720, 11 `TremulantWaveform`s recorded from its own pipes, 73 pipes).
  - Percussion has 49 notes each: Xylophone, Glockenspiel, Chrysoglott and Chimes.
  - Single-pipe ranks: 10 traps and 10 toys.
  - `DummySingle` and `DummyMulti` ranks are used for re-iteration.
  - Noise ranks: blower and stop action.
- **Unification:** 633 StopRank rows (234 normal pipe rows).
- **Tremulants and Reit:** 153 StopRank rows carry `m` (switch) and `p` (alternate rank), the same pattern as
  Melcer. `m` is one of 7 switches: Main, Solo, Tibia, Vox, Clarinet, Tuba and Reit. With the switch on, the
  tremmed rank replaces the normal one. Re-iteration uses the same mechanism: the Reit switch swaps a silent
  dummy rank for the percussion rank, and 9 of those rows also carry `k`/`l` (meaning unresolved).
- **Swell boxes:** 2 enclosures, Main (210) and Solo (220), with shutter, box and expression-shoe continuous
  controls. 1,663 pipes are enclosed.
- **Key actions:** 38. These include octave, sub-octave and unison-off couplers (Great UnisonOff is a KeyAction
  active while its switch is off), quint, tenth and tierce couplers, and 3 melody couplers into the 2T divisions.
- **Combinations:** 169 combinations with 9,611 elements.

### Audio and memory (measured)
- 2,133 sample files decode through `WavReader` in 3.8 s: 2,114 mono 48 kHz, 1 mono 44.1 kHz, 1 stereo. 1,221 of
  1,418 attacks are looped. Releases are separate files (1,529).
- **Decoded size is 611 MiB as 16-bit, including the tremmed ranks.** Small enough for the 8 GB machine with room
  to spare. Organium's measured mono positioning applies.
- 17 files fail, all from required package **000001 "Hauptwerk Standard Components" (Milan Digital Audio)**: the
  St. Anne's Moseley CODM blower and stop-action noises. They are not in the Melotone package; Hauptwerk installs
  them. Organium should not use them (Milan's content), so these noises stay silent.

### Console (ConsoleWindowCheck)
- Renders at 1260×905. Clicking toggles the knobs correctly at all window sizes. **The tab labels overlap**: the
  CODM captions need text wrapping and bounding boxes, which Organium doesn't model yet.
- **FAIL: paint allocation.** A repaint allocates 9,728 bytes against a budget of about 8.8 KB (274 images × 32 B).

### Gaps, in the order they block playing

| # | Gap | Evidence | Status |
|---|-----|----------|--------|
| 1 | **SwitchLinkage**: all 193 stop switches (`StopNode_*`) are hidden and non-clickable. Console tabs drive them through 714 linkages (fields `a`, `b` only). Without this, **no stop can be engaged**. | Names suggest `a` = source and `b` = target (tab 2030 "Toys Auto Horn" → 10116 "StopNode_2030"). 442 switches are both source and target, and 12↔10068 link both ways. | **Done 2026-11-17** (research/switch-linkage-report.md) |
| 2 | Alternate ranks (StopRank `m`/`p`/`n`): tremulants and Reit | Now seen in two organs | Field names unconfirmed |
| 3 | StopRank `g` action type: 1 = key-played, 2 = stop-engaging effect, 3 = stop-disengaging effect, 4 = dummy/Reit. Noise rows have key count 0, so they are harmless today. **Toys** (f = 21, g = 1, one key) would wrongly play from Pedal key 36. | Row names: `StopEffectEngaging_2001` / `StopEffectDisengaging_2001` | Strong hypothesis (`g`), `f` unresolved |
| 4 | Enclosures + expression shoes | 2 boxes, 1,663 pipes | Not implemented (same gap as Melcer) |
| 5 | Synthesised tremulant (English Horn) | `Tremulant` / `TremulantWaveform` rows | Not implemented; new engine feature |
| 6 | Second touch: MIDI routing to the 4 2T keyboards (Hauptwerk does this in its MIDI settings, by velocity or separate contacts) | Keyboards 5–8 | Not implemented |
| 7 | Melody couplers | 3 KeyActions, kind 3 | Parsed, not played |
| 8 | Combinations, pistons and setter | 169 combinations | Not implemented |
| 9 | Caption layout (wrapping) and the paint-allocation budget | Screenshot; check failure | Cosmetic / test |
| 10 | Blower and stop-action noises | Package 000001 | Excluded (Milan content) |
