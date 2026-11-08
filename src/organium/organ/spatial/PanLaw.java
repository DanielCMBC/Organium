package organium.organ.spatial;

/**
 * Maps a pipe's virtual position to a stereo pan amount p in [-1, 1]. Gains follow Hauptwerk's
 * measured constant-sum law: left = 1 - p, right = 1 + p, so p = 0 is centred at unity gain and
 * p = 1 is fully right at +6 dB. Called at organ load time only, never on the audio thread.
 */
@FunctionalInterface
public interface PanLaw {
    /** Every pipe centred: the behaviour before positioning existed. */
    PanLaw CENTRED = (x, y, z) -> 0.0;

    double pan(double x, double y, double z);
}
