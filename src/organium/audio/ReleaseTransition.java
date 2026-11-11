package organium.audio;

/**
 * How a pipe that stops sounding hands over to its release.
 *
 * With a release sample, the sustaining voice fades out over {@code crossfadeSeconds}; if
 * {@code fadeInRelease}, the release sample fades in over the same time, so the two form a symmetric
 * linear crossfade. A release sample starts with the pipe still sounding at full level, at a phase
 * unrelated to the sustain loop, so starting it at full level makes a step in the waveform: an
 * audible click (research/release-transition-report.md). Without a release sample, the sustaining
 * voice simply fades out over {@code fadeOutSecondsWithoutRelease}.
 *
 * Hauptwerk documents a "brief phase-aligned cross-fade" without giving its length; Melcer's
 * definition leaves the release crossfade length unset, so Hauptwerk's built-in default applies,
 * which is not known. The 10 ms default here is the shortest length measured to remove the click
 * on every tested Melcer pipe, with the least level dip from the unaligned phases.
 */
public record ReleaseTransition(float crossfadeSeconds, float fadeOutSecondsWithoutRelease, boolean fadeInRelease) {
    /** 10 ms symmetric crossfade into release samples; 50 ms fade-out for pipes without one. */
    public static final ReleaseTransition DEFAULT = new ReleaseTransition(0.010f, 0.050f, true);

    /**
     * Organium's behaviour before November 2026, kept so the stage-0 regression baselines stay
     * verifiable: the release sample starts at full level while the sustain fades out over 50 ms.
     */
    public static final ReleaseTransition STAGE0 = new ReleaseTransition(0.050f, 0.050f, false);

    public ReleaseTransition {
        if (!(crossfadeSeconds > 0) || !(fadeOutSecondsWithoutRelease > 0)) {
            throw new IllegalArgumentException("Release times must be positive");
        }
    }
}
