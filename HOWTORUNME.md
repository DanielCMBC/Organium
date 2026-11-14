# How to run Organium (beta testing)

Organium is a free, open-source virtual pipe organ that plays Hauptwerk-format sample sets. This beta
is a work in progress: it plays organs live from a MIDI keyboard, but many features are still missing
(see [What doesn't work yet](#7-what-doesnt-work-yet)). Please read [Reporting problems](#8-reporting-problems)
before you start, so you know what to note down.

## 1. What you need

| | |
|---|---|
| Operating system | Windows 10 or 11 (64-bit). Other systems are untested. |
| Java | JDK 25 (both `java` and `javac`, version 25 or later), on your `PATH`. Check with `java -version` and `javac -version`. |
| Memory | 8 GB of RAM or more. A mid-sized organ needs roughly what its samples take in memory, plus about 0.5 GB: Melcer Chamber Music Hall needs about 2.6 GB. |
| MIDI keyboard | Any USB MIDI keyboard (optional: you can also play a demo chord without one). |
| A sample set | A Hauptwerk-format organ whose samples are plain WAV files, installed in the usual Hauptwerk folder layout (below). Organium includes no sample sets. Use only sets you are licensed to use, and don't redistribute them. |

Tested so far: **Melcer Chamber Music Hall** and the Hauptwerk **Polyphony Testing Organ**.

### Sample set folder layout

Organium finds samples the way Hauptwerk lays them out:

```
<sample sets folder>\
    OrganDefinitions\
        <Organ name>.Organ_Hauptwerk_xml          <- you point Organium at this file
    OrganInstallationPackages\
        000696\                                   <- one folder per installation package (6 digits)
            ...WAV files...
```

An existing Hauptwerk installation already has this layout (for example
`C:\Hauptwerk\HauptwerkSampleSetsAndComponents`). Hauptwerk does not need to be running, or installed.

## 2. Get and build Organium

In PowerShell:

```powershell
git clone https://github.com/DanielCMBC/Organium.git
cd Organium
.\build.ps1
```

`build.ps1` compiles Organium into the `out` folder and should end with `Built NN files into out\`.
Run it again whenever you update (`git pull`).

If PowerShell refuses to run the scripts, allow local scripts for your user once:

```powershell
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
```

## 3. Before you start

- **Close Hauptwerk** if it is running. Only one program can use an ASIO driver at a time, and both
  need a lot of memory.
- **Close memory-hungry programs**, especially web browsers. If Windows has less free memory than the
  organ needs, loading gets slow and playback can stutter. To check, open Task Manager and look at
  Performance > Memory > Available.
- **Connect your MIDI keyboard** before starting Organium. MIDI inputs are opened only at start-up.

## 4. Start Organium

```powershell
.\run.ps1 --organ="D:\OrganiumSampleSets\OrganDefinitions\Melcer Chamber Music Hall.Organ_Hauptwerk_xml"
```

Replace the path with your organ definition file. Keep the quotes if the path contains spaces.

Loading takes a few seconds to a minute. Then the terminal shows the organ, the MIDI channel for each
keyboard, your MIDI input, and the list of stops, and the organ's console opens in its own window.

`run.ps1` sets the Java options Organium was tested with (4 GB memory limit, ZGC garbage collector).
Please use it rather than starting `java` yourself.

### Other ways to start

| Command | What it does |
|---|---|
| `.\run.ps1 --organ="<file>"` | Play the organ through Windows' normal sound output (recommended to start with). |
| `.\run.ps1 --organ="<file>" --asio` | Play through the first ASIO driver (lower latency; see [Audio](#6-audio-windows-sound-or-asio)). |
| `.\run.ps1 --organ="<file>" --asio="<driver name>"` | Use a specific ASIO driver. |
| `.\run.ps1 --list-asio` | List the installed ASIO drivers. |
| `.\run.ps1 --organ="<file>" --asio-panel` | Use ASIO and open the driver's own settings window. |
| `.\run.ps1 --organ="<file>" --demo` | No MIDI: plays a short chord on the first stops and exits (sound check). |
| `.\run.ps1 --demo` | No organ at all: a synthetic test chord (quickest sound check). |
| `.\run.ps1` | No organ: your MIDI keyboard plays a simple synthetic test tone. |

Extra options:

- `--trim=<dB>` sets the master output level. The default is `-10`, like Hauptwerk's default output
  level adjustment. Use a lower value (for example `--trim=-16`) if full registrations distort.
- `--pan=centred` places every mono pipe in the middle, instead of spreading pipes across the stereo
  field by their position in the organ (the default, `--pan=hauptwerk`).

## 5. Playing

### MIDI keyboards

Organium assigns the organ's keyboards to MIDI channels in order:

```
MIDI channel 1 -> first keyboard (Melcer: Hauptwerk)
MIDI channel 2 -> second keyboard (Melcer: Brustwerk)
MIDI channel 3 -> third keyboard (Melcer: Pedal)
```

The terminal prints the exact list for your organ at start-up. If your keyboard sends on another channel,
the first time you play Organium gives that channel the first keyboard not yet played, and prints
`Auto-assigned MIDI channel N -> ...`. There is no MIDI settings screen yet.

With a single keyboard you play the first manual. To hear another manual's stops from it, use a
coupler (for example Melcer's `BW/HW`, which couples Brustwerk to Hauptwerk). Each keyboard,
including the pedal, plays only from its own MIDI channel (Melcer's Pedal: channel 3).

### Stops and couplers

**All stops start off: you hear nothing until you draw a stop.** Either:

- **click the stop knobs** on the console window (click again to retire them), or
- **type in the Organium terminal** and press Enter:

| Type | Does |
|---|---|
| a number, e.g. `6` | toggles stop 6 (numbers are in the stop list) |
| `c1`, `c2`, ... | toggles coupler 1, 2, ... |
| Enter on an empty line | prints the current registration |
| `q` | quits |

### The console window

- The console is scaled to fit your screen. Resize or maximise the window freely: it keeps its
  proportions, with black bars at the sides if needed.
- Organs with several console pages: **Page Down / Page Up** switch pages, and **1-9** jump to a page.
- Closing the window quits Organium.

## 6. Audio: Windows sound or ASIO

**Windows sound** (the default, no option) plays through your normal Windows output device, the one
that the Windows volume control and other programs use. It works out of the box, but expect roughly
40-60 ms between pressing a key and hearing it. That's fine for testing and slow pieces, but noticeable
in fast playing.

**ASIO** (`--asio`) is much faster: about 5 ms of buffering. Organium has been tested with
**ASIO4ALL v2**. ASIO4ALL keeps separate settings for each program, so it must be set up for Organium
itself (settings made in Hauptwerk do not carry over):

1. Start with `.\run.ps1 --asio-panel` (no organ needed: your keyboard plays a test tone).
2. In the ASIO4ALL window, under *WDM Device List*, switch **on** the output you listen through
   (for example your speakers or headphones) with the button left of its name. Switch **off** virtual
   devices such as *VB-Audio Virtual Cable* or *FxSound*, and HDMI outputs you don't use. The wrench
   icon shows the detailed view.
3. If a device shows a red X, another program (for example an audio "enhancer" such as FxSound) is
   holding it. Quit that program.
4. Close the ASIO4ALL window, press Enter in the terminal to quit, and start again with `--asio`.

**Known issue:** ASIO4ALL may not accept device changes while audio is running. If its window
won't let you switch a device off, or nothing changes, use Windows sound for now and tell us what
you saw.

With `--asio`, Organium reports audio problems as they happen:

```
Audio dropout at 12.3 s: 1 late callback(s) so far (worst gap 45.1 ms), 0 render overrun(s)
```

When you quit it prints a summary line starting with `Audio:`. Please include these lines in reports.

## 7. What doesn't work yet

- **Tremulants:** the knob moves, but the sound doesn't change.
- **Swell boxes / expression pedals:** not implemented. Enclosed divisions play at a fixed level.
- **Organ noises** (blower, key and stop action sounds, ambience): not loaded.
- **Combinations, pistons, transposer, temperaments, recording:** not implemented.
- **MIDI configuration:** fixed channels 1, 2, 3, ... plus auto-assignment. Velocity, controllers and
  program changes are ignored.
- **Voicing** (per-rank or per-pipe adjustment): not available.
- **Releases** crossfade over a fixed 10 ms, without Hauptwerk's phase alignment. On some notes the
  release can sound slightly louder or softer than the held note for a moment. That's known; a click
  or pop is not (see below).
- **Displays** with Windows scaling above 100% are untested. The console may look soft.
- **Sample sets** with encrypted or compressed samples are not supported.

## 8. Reporting problems

Please report anything that sounds or behaves wrong, especially:

- **clicks, pops, crackles or dropouts** (when exactly: key press, key release, stop change, chords?);
- **wrong notes, stuck notes, wrong stops** sounding, or keys that don't play;
- **anything that looks wrong on the console**, or knobs that don't react to clicks;
- **slow loading or out-of-memory errors.**

For each problem, note:

1. what you did (stops drawn, what you played, how long it took to happen);
2. the **organ** and its definition file name;
3. **audio**: Windows sound or ASIO (and which driver);
4. your **MIDI keyboard** model;
5. **free memory** before starting (Task Manager > Performance > Memory > Available);
6. the **terminal output**: copy all the text from the Organium window, including the start-up lines
   and any `Audio dropout` / `Audio:` lines (right-click the title bar > Edit > Select All, then Enter
   to copy).

Report issues at https://github.com/DanielCMBC/Organium/issues (or to the project maintainer).
Don't attach sample-set files.

## 9. Troubleshooting

| Problem | Try |
|---|---|
| No sound at all | Draw a stop first (all stops start off). Check the Windows volume and output device. Try `.\run.ps1 --demo`: if you hear that chord, audio works and the problem is MIDI or stops. |
| No sound with `--asio`, but Windows sound works | ASIO4ALL is sending to a different device: see [Audio](#6-audio-windows-sound-or-asio). |
| Keys don't play | The terminal should show `MIDI in: <your keyboard>` at start-up. If not, connect the keyboard and restart. Check the channel (section 5). |
| `Skipping <device>: ...` at start-up | Another program has the MIDI port open (for example Hauptwerk or a DAW). Close it and restart. |
| `java` or `javac` not found, or "unsupported class file version" | Install JDK 25 and make sure it comes first on your `PATH`. |
| Loading is very slow, or playback stutters | Not enough free memory: close browsers and other large programs (section 3). |
| `OutOfMemoryError` | The organ needs more than the 4 GB limit in `run.ps1`, or the computer has too little RAM. Report it with the organ's name. |
| `Not loaded (no stop plays these ranks yet): ...` | Normal: ranks Organium can't use yet (tremulant ranks, noises) are skipped to save memory. |
| The console window is cut off or too small | Resize or maximise it; it rescales automatically. |

### For testers comfortable with a terminal: MIDI monitor

To see exactly what your keyboard sends, build the test tools once and run the monitor:

```powershell
$t = Get-ChildItem test -Recurse -Filter *.java | ForEach-Object FullName
javac --release 25 -cp out -d out-test $t
java -cp "out;out-test" organium.MidiMonitor 60
```

It lists the MIDI devices and prints every message for 60 seconds (close Organium first: Windows lets
only one program open a MIDI port).
