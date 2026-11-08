package organium.io;

import java.io.IOException;

/** A readable WAV file whose encoding Organium cannot play yet (e.g. 32-bit float). */
public final class UnsupportedWavFormatException extends IOException {
    private static final long serialVersionUID = 1L;

    public UnsupportedWavFormatException(String message) {
        super(message);
    }
}
