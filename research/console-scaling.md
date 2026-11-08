# Console scaling

Organ consoles are designed at a fixed pixel size (Melcer Chamber Music Hall: 1692 x 1200), which may
not fit the screen (this machine's working area is 1920 x 1040, so Melcer's bottom row of stop knobs
was cut off). `ConsoleWindow` now scales the console proportionally to fit its window. Requirement:
fit, keep the aspect ratio, keep all controls visible and clickable, handle resizing and other
resolutions, keep the work off the audio thread.

Hauptwerk also shows consoles scaled to its window. Its exact scaling options and filtering were not
investigated; this is the requested proportional fit, not a measured reproduction of Hauptwerk's.

## Behaviour

- **Opening:** the window opens as large as the screen's working area allows (the screen minus the
  taskbar, with room for the window frame), never larger than the design size, and centred. Melcer
  opens at 1427 x 1040 (console 1411 x 1001, scale 0.834).
- **Resizing and maximising:** the console scales to the largest size that fits the window, up as
  well as down, and is centred; the leftover area on two sides is black.
- **Other resolutions and displays:** the opening size comes from the display the window opens on;
  after that, any resize or maximise refits the console. On a display with Windows scaling above
  100%, Java scales the whole window again by that factor (logical pixels), so the console stays the
  same size on screen but is resampled twice; this was not tested (this machine runs at 100%).
- **Clicks:** a mouse position is mapped back to the organ's design coordinates (the design pixel
  under the centre of the screen pixel), and hit testing uses the organ's own hit boxes unchanged.
  Clicks on the bars do nothing.

## How it is drawn

`ConsoleScale` holds the fit (scale, offsets, scaled size) and the mapping between screen and design
coordinates. It is updated in place on every resize. For the current scale, the visible page is
prepared once as a `View`:

- the background, resampled to the scaled console size;
- every sprite, resampled so its edges land where the scaled design edges do;
- positions relative to the console, and fonts derived at the scaled size.

Resampling is bilinear, halving in steps first when shrinking to half or less, so every source pixel
contributes. At scale 1 the view reuses the design images and coordinates, so rendering is exactly as
before.

A paint blits the view's background and one sprite per switch and draws the strings. The paint code
allocates nothing. Java2D's `drawImage` allocates about 32-96 bytes per call internally (JDK 25,
software and accelerated surfaces), and nothing else in the paint path does.

While the window is being dragged, the last view is drawn stretched (bilinear) until the size has
not changed for 150 ms. Then the view is rebuilt for the new scale and views at old scales are
dropped. Other pages are prepared when shown. Everything runs on the Swing event thread; nothing
touches the audio callback.

## Tests

- `test/organium/ui/ConsoleScaleCheck`: fit math for Melcer's console in a range of areas (screen
  size, small, wide, tall, 4K, exact, one pixel short). Checks that the console always fits, keeps
  the aspect ratio, fills one dimension and is centred. Checks that every screen pixel on the console
  maps inside the design, that a design pixel maps back to itself to within one screen pixel's
  coverage, and that the bars are outside. An off-by-one when enlarging (mapping by a pixel's left
  edge) was found and fixed here.
- `test/organium/ConsoleWindowCheck <organ.xml> [screenshot dir]`: opens the real console (no
  samples, no audio). It checks that the window opens inside the screen's working area at the
  console's aspect ratio. Then, at five window sizes, it clicks the centres of seven stop knobs
  through the operating system's mouse (`Robot`): the top-most, bottom-most, left-most, right-most
  and three between. It checks that each click toggles exactly that stop, measures the longest
  event-thread stall while rescaling, and measures paint allocation.

Results on Melcer (this machine, 1920 x 1040 working area):

| Window | Surface | Scale | Knobs toggled by mouse | Longest event-thread stall |
|---|---|---|---|---|
| as opened | 1411 x 1001 | 0.834 | 7/7 | 0 ms (prepared before the check) |
| 800 x 600 | 784 x 561 | 0.463 | 7/7 | 25 ms |
| 1400 x 600 | 1384 x 561 | 0.468 | 7/7 | 20 ms |
| 700 x 1000 | 684 x 961 | 0.404 | 7/7 | 19 ms |
| maximised | 1920 x 1017 | 0.848 | 7/7 | 34 ms |

Paint: 768 bytes per paint, which is 24 blits x 32 bytes, all inside Java2D. The audio regression
suite is bit-identical; audio, MIDI and the voice engine are unchanged.
