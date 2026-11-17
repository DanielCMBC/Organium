# Switch linkage: investigation and implementation proposal

Date: 2026-11-17. Investigated, then implemented as proposed (see "Implementation and verification" at the end). Target: Freedom Morton 3/10
(`research/theatre-organ-preflight.md`), where every stop is reached only through a `SwitchLinkage`.
Evidence levels are those of `research/hauptwerk-gain-pan-report.md`: Confirmed / Strong / Hypothesis /
Unresolved.

## Sources

| # | Source | Method |
|---|--------|--------|
| D1 | Hauptwerk 4.2.1 User Guide and CODM Guide (`Documentation/*.pdf`) | Text search. Linkages aren't documented; the CODM guide documents momentary (non-latching) stops such as "toy counter" effects. |
| D4 | `Hauptwerk.exe` 4.2.1 | `research/hauptwerk-probe/lea_scan.py` (read-only string-reference scan): the SwitchLinkage schema registration at 0x9b30d0 and the "switch linkage action" enum display strings at file offset 0x1a7be2c. Nothing executed, patched or copied. |
| D3 | Organ definitions on this machine | St. Anne's Moseley (format 4.11, Milan's example organ), Melcer, Freedom Morton 2/6, 3/10 and 3/17 (format 4.00), and our CODM probe organs compiled by 4.2.1 (format 4.20). Field-usage statistics, plus every linkage row grouped by its non-default fields together with the names of the switches it joins. |

## Field mapping

D4 registration order: `SourceSwitchID`, `DestSwitchID`, `Conditional`, `ConditionSwitchID`,
`SourceSwitchLinkIfEngaged`, `ConditionSwitchLinkIfEngaged`, `ReevaluateIfCondSwitchChangesState`,
`EngageLinkActionCode`, `DisengageLinkActionCode`. As with TextInstance, the letters don't simply follow this
order, so each letter below is fixed by the data.

| Tag | Field | Evidence | Level |
|-----|-------|----------|-------|
| a | SourceSwitchID | First registered field. Every row reads naturally source → destination: tab "CustPg1_Stop 2001" → "Ped_Bourdon32" → "StopNode_2001" (the Stop row's control switch); St. Anne's "Blower On" → "Blower"; Melcer "Tremulant" → "Tremolo if Blower". | Confirmed |
| b | DestSwitchID | Same | Confirmed |
| c | ConditionSwitchID | Holds only switch IDs, and they are the switches a condition would name: "Coupler: Cresc to Sw", "Coupler: Gt + Ped Pistons", Melcer "Blower". | Strong |
| e | ConditionSwitchLinkIfEngaged | Always `Y`, and only on rows with `c`. Crescendo rows act only while "Cresc to …" is engaged. | Strong |
| f | EngageLinkActionCode | Values 2, 4, 7. St. Anne's "Blower Off" (momentary) → "Blower" has f=2 (Disengage destination). Stop-action rows have f=4 (Pulse on) going to "StopActionNoise … On". | Strong |
| g | DisengageLinkActionCode | Values 4 and 7. "… Off" noise rows have f=7 (None) and g=4 (Pulse on). | Strong |
| d | SourceSwitchLinkIfEngaged | By elimination. Only Melcer uses it (d=N on 165 stop → stop-noise rows). | Hypothesis |
| h | ReevaluateIfCondSwitchChangesState | By elimination. Written as `N` on every row by the 4.2.1 compiler; absent from the older files. | Hypothesis |

`Conditional` has no letter, so it is a registration without storage (a UI group header, as seen in other tables).

**Action codes** (D4 display order, numbered from 1): 1 Engage destination switch, 2 Disengage destination
switch, 3 Change state of destination switch, 4 Pulse destination switch on, 5 Pulse destination switch off,
6 Pulse state of destination switch, 7 None (no effect). Codes 2, 4 and 7 match their rows' meaning in
St. Anne's (Strong). Code 1 never appears in any file, and that fits it being f's default.

**Defaults when a field is omitted** (compacted files omit default values):
- f = 1 (Engage) and g = 2 (Disengage): **Strong**.
  - St. Anne's momentary "Blower On" → "Blower" has to state g=7 so that releasing it doesn't turn the blower
    off. So g's default acts on release, and the matching rows show that action is to disengage.
  - Neither 1 in f nor 2 in g is ever written.
  - With these defaults every Freedom Morton row (`a`, `b` only) mirrors its source, which is what the console
    needs: tab on → stop on, tab off → stop off.
- Condition: none when `c` is absent (Strong).

## Behaviour

| Question | Answer | Level |
|----------|--------|-------|
| Directional? | Yes: source → destination. "Bidirectional" behaviour is two rows, one each way: 432 Freedom Morton switches sit in such 2-switch loops (tab ↔ logical switch), and so do St. Anne's / Freedom Morton capture-mode ↔ setter. | Confirmed (fields), Strong (loops) |
| Conditional? | Supported by the format (`c`, `e`, probably `h`). St. Anne's (88 rows), Melcer (1) and Freedom Morton 3/17 (11) use it; **Freedom Morton 3/10 does not.** | Strong |
| State-dependent? | The action is chosen by the source's transition (engaging → f, disengaging → g). Engage and Disengage set a state, so repeating them changes nothing; Change state and the pulses don't. | Strong |
| Held notes | A linkage only changes switch states. Whatever a stop or coupler change does to held keys is the existing Console behaviour (rebuild, then start/stop pipes so held keys match the registration). Hauptwerk speaks newly engaged stops under held keys, and Organium already does this. | Strong |
| Chains | Freedom Morton 3/10: at most tab → logical → node (depth 2). 57 roots, all clickable (momentary tabs: toys, pistons). | Confirmed (data) |
| Tremulants, percussion, combinations | Yes. Tremulant and Reit tabs reach their StopRank `m` switches through tab → logical → `StopNode`. Coupler tabs reach `CouplerNode` switches (KeyAction `f`). CombinationElement `c`/`d` name the *logical* switches (e.g. 2030), whose back-links update the tabs. Toy tabs and pistons are momentary (Switch `d` = Latching, Confirmed earlier). | Confirmed (data) |

### Freedom Morton 3/10 graph

```
console tab (clickable, CustPg1_Stop N) ──► logical switch (Stop name, hidden, latching) ──► StopNode / CouplerNode (Stop.d / KeyAction.f)
          ◄──────────────────────────────── (back-link keeps the tab in step with combinations and MIDI)
```

| Edge kind | Rows |
|-----------|------|
| clickable tab → hidden logical | 273 |
| hidden logical → clickable tab | 216 |
| hidden → stop node | 186 |
| hidden → stop node that is also a tremulant/Reit `m` switch | 7 |
| hidden → coupler node | 32 |

## Effect on organs that already work (if only the supported subset is applied)

| Organ | Linkages | Applied | Skipped (conditional, `d`, or codes 3–6) | Applied rows reaching a stop or coupler control |
|-------|----------|---------|------------------------------------------|-------------------------------------------------|
| Freedom Morton 3/10 | 714 | 714 | 0 | 225 |
| Melcer | 308 | 142 | 166 | **0** |
| St. Anne's Moseley | 662 | 190 | 472 | **0** |
| Polyphony Testing Organ | 0 | 0 | 0 | 0 |

So nothing that sounds in Melcer, St. Anne's or Polyphony changes. Melcer's applied rows drive noise inputs
(`DivisionInput`), which Organium doesn't play.

## Proposal: smallest change

Linkage is control logic, so it lives entirely in the model and in `Console` (UI/MIDI threads). `VoiceEngine`,
`CommandQueue` and the audio callback are untouched; they still receive only pipe start/stop commands, as today.

1. **Model** (`Organ.java`): add `record SwitchLinkage(Switch source, Switch dest, LinkAction onEngage,
   LinkAction onDisengage)` with `enum LinkAction { ENGAGE, DISENGAGE, NONE }`, and a `List<SwitchLinkage>`
   field on `Organ`.
2. **Parser** (`OrganParser.java`): read `SwitchLinkage` as a = source, b = dest, f (default 1), g (default 2).
   - Skip, with one summary warning that gives the count, rows that have `c` (condition), `d`, or codes 3–6.
   - Ignore `h` when there's no condition (it only governs re-evaluating a condition).
   - Warn about rows that name unknown switches.
   - Add `SwitchLinkage` to the tables `OrganPreflight` reports as read.
3. **Console**: precompute an outgoing-linkage list per switch index, plus a `boolean[] routingRelevant` (stop
   controls and key-action controls).
   - `setEngaged`/`toggle` run a cascade over a work queue sized at load. A switch changes, and propagates,
     only if its state actually changes; that ends the tab ↔ logical loops after one step.
   - Exclusive-group releases are cascaded the same way.
   - Guard: each switch may change at most twice per cascade. If a malformed definition exceeds that, the
     cascade stops and the console logs a warning once, instead of oscillating forever.
   - **One** routing rebuild per user action, and none if no routing-relevant switch changed (a tab click whose
     cascade ends at a combination node, for example).
   - Default-engaged switches cascade at load (Freedom Morton has none).
4. **ConsoleWindow**: no change. It already toggles the clicked switch through `Console` and repaints the whole
   page, so tabs changed by back-links redraw.
5. **Out of scope**, as agreed: tremulant/Reit alternate ranks (`m`/`p`), toys and percussion, second touch,
   combinations, and pulse/toggle/conditional linkages.
   - Side effect to know about: a toy tab is momentary in the definition, but Organium treats every switch as
     latching, so clicking a toy tab latches it.
   - The text menu in `App` toggles stop control switches (nodes) directly. That sounds correct, but those tabs
     won't redraw, because nodes have no back-link.

### Tests
- **New `LinkageCheck`** (headless: Freedom Morton 3/10 parsed with a recording fake engine, no audio device):
  1. Click "Great Tibia 8" by toggling its *tab* switch, then press a Great key. Exactly the Tibia Clausa pipe
     for that note starts. Click again, press again: nothing starts.
  2. Several stops, a held chord while stops change, a coupler tab (Solo to Great 8) and the Great Unison Off
     tab.
  3. 1,000 rapid random clicks under held keys: the final engine state equals a from-scratch rebuild, and no
     pipe is left stuck.
  4. Each click changes each linked switch at most once.
  5. A synthetic oscillating definition hits the guard and stops.
  6. Loads with zero linkage warnings.
- **Regression:**
  - `RenderRegression compare` (stage baselines), `GainCheck`, `PanTableCheck`.
  - Melcer: `OrganPreflight` output unchanged apart from the new linkage summary, and a route-table snapshot
    identical before and after for every keyboard key under every single-stop registration.
  - `ConsoleWindowCheck` on Melcer and Freedom Morton. Its 9.7 KB paint allocation is tracked separately as a
    GUI issue.
- **Live acceptance:** click a Freedom Morton tab in the real window, play its manual over ASIO and hear the
  rank. Click it off and later notes don't trigger it. Then multiple stops, held chords, couplers and rapid
  registration changes.

## Not settled, and how to settle it if needed later
- `d` and `h`, and the numeric codes 3, 5 and 6, rest on elimination and on display order. A CODM probe organ
  compiled by the installed Hauptwerk could confirm them, but Freedom Morton 3/10 doesn't need them.

## Implementation and verification (2026-11-17)

Implemented as proposed:
- `Organ.SwitchLinkage` in the model.
- `OrganParser.parseLinkages`: a/b, f default 1 and g default 2. Conditional rows, `d` rows and codes 3–6 are
  skipped, with one counted warning.
- The `Console` cascade:
  - work arrays preallocated at load;
  - a switch changes only on a real state change, at most twice per cascade;
  - one routing rebuild, only if a stop or coupler control changed.
- `ConsoleWindow`, `VoiceEngine` and the audio callback are unchanged.

| Check | Result |
|-------|--------|
| `LinkageCheck` (Freedom Morton 3/10, 38 checks) | All pass. 714 linkages, none skipped. All 193 stops and 32 couplers reachable from a tab. Great Tibia 8 tab → note 60 sounds exactly the RM Tibia Clausa pipe (engine: 1 voice); tab off → note 62 silent. 4 stops at once; a held chord while stops change; Solo to Great 8 (on and off under a held key); Great Unison Off with Great Octave. All 273 tabs on/off restore every switch, with no overflow. A piston tab doesn't rebuild routing. 1,000 random clicks and keys match a freshly set-up console (1,017 pipes) and leave no stuck voices. A synthetic oscillating definition is cut off once, in 0.3 ms. |
| `RouteSnapshot` (new tool): every key of every keyboard under each single stop, all stops, each coupler and everything, before vs after | Identical for Melcer (1,612 lines), St. Anne's (3,407), Polyphony (496) and Freedom Morton driven through its nodes (22,521) |
| `OrganPreflight` Melcer, HEAD vs new | Only the new warning "166 of 308 switch linkages … skipped (1 conditional, 165 source-state option (d))"; same 5,689 sample files |
| `RenderRegression compare` stage-0 and stage-1 (Polyphony 512 MB variant, the one the local console baseline was recorded with) | All scenarios bit-identical |
| `RenderRegression bench` | 3.7 ns per voice-frame, `render()` allocates 0 bytes (unchanged) |
| `GainCheck`, `PanTableCheck`, `ReleaseTransitionCheck` (30), `ConsoleScaleCheck` | Pass |
| `ConsoleWindowCheck` (real mouse) | Melcer passes. Freedom Morton toggles 8/8 tabs at every size; its only failure is the known 9.7 KB repaint allocation (GUI issue, tracked separately). |
