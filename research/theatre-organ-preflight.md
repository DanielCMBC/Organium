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

### Next steps
1. The user asks Melotone, through its Contact Us form, for a download link and confirms the ODF licence terms
   (Organium does not send this on the user's behalf).
2. Run the standard preflight once the package is in hand: `OrganPreflight`, `SampleDecodeCheck`, check for
   encryption, list unread tables and measure memory.
