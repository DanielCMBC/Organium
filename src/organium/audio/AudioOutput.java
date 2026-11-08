package organium.audio;

/**
 * Callback-driven audio device. Matches the model used by ASIO/WASAPI/PortAudio so a
 * lower-latency native backend can replace Java Sound without touching the engine.
 */
public interface AudioOutput {
    interface Renderer {
        /** Fill {@code out} with {@code frames} interleaved stereo float frames in [-1, 1]. */
        void render(float[] out, int frames);
    }

    void start(int sampleRate, int periodFrames, Renderer renderer) throws Exception;

    void stop();
}
