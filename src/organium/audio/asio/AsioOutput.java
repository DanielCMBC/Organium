package organium.audio.asio;

import organium.audio.AudioOutput;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * ASIO host written against the IASIO COM interface through Java's Foreign Function API,
 * so no native bridge has to be compiled. Windows x64 only.
 *
 * ASIO callbacks carry no user pointer, so only one AsioOutput can run at a time.
 * Call start() and stop() from the same thread: drivers expect a single COM apartment.
 */
public final class AsioOutput implements AudioOutput {
    // IASIO vtable slots (IUnknown occupies 0-2)
    private static final int RELEASE = 2;
    private static final int INIT = 3;
    private static final int GET_DRIVER_NAME = 4;
    private static final int GET_ERROR_MESSAGE = 6;
    private static final int START = 7;
    private static final int STOP = 8;
    private static final int GET_CHANNELS = 9;
    private static final int GET_LATENCIES = 10;
    private static final int GET_BUFFER_SIZE = 11;
    private static final int CAN_SAMPLE_RATE = 12;
    private static final int GET_SAMPLE_RATE = 13;
    private static final int SET_SAMPLE_RATE = 14;
    private static final int GET_CHANNEL_INFO = 18;
    private static final int CREATE_BUFFERS = 19;
    private static final int DISPOSE_BUFFERS = 20;
    private static final int OUTPUT_READY = 23;
    private static final int VTABLE_SLOTS = 24;

    private static final int ASE_OK = 0;
    private static final int ASE_SUCCESS = 0x3f4847a0;

    // ASIOSampleType values used on little-endian hosts
    private static final int INT16_LSB = 16;
    private static final int INT24_LSB = 17;
    private static final int INT32_LSB = 18;
    private static final int FLOAT32_LSB = 19;
    private static final int FLOAT64_LSB = 20;
    private static final int INT32_LSB16 = 24;
    private static final int INT32_LSB18 = 25;
    private static final int INT32_LSB20 = 26;
    private static final int INT32_LSB24 = 27;

    // asioMessage selectors
    private static final int SELECTOR_SUPPORTED = 1;
    private static final int ENGINE_VERSION = 2;
    private static final int RESET_REQUEST = 3;
    private static final int BUFFER_SIZE_CHANGE = 4;
    private static final int RESYNC_REQUEST = 5;
    private static final int LATENCIES_CHANGED = 6;

    private static final int BUFFER_INFO_SIZE = 24;   // ASIOBool isInput; long channelNum; void* buffers[2]
    private static final int CHANNEL_INFO_SIZE = 52;  // 5 longs + char name[32]
    private static final int CHANNEL_INFO_TYPE = 16;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle CALL_INT = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle CALL_INT_PTR = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle CALL_VOID_PTR = LINKER.downcallHandle(
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle CALL_INT_PTR2 = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CALL_INT_PTR4 = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle CALL_INT_DOUBLE = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_DOUBLE));
    private static final MethodHandle CALL_CREATE_BUFFERS = LINKER.downcallHandle(
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));

    private static final MemorySegment CALLBACKS = createCallbacks();
    private static volatile AsioOutput active;

    private final String driverName;
    private Arena arena;
    private boolean comInitialized;
    private MemorySegment driver = MemorySegment.NULL;
    private MemorySegment vtable;
    private boolean buffersCreated;
    private boolean running;

    // Owned by the driver's callback thread once started
    private Renderer renderer;
    private float[] mix;
    private int frames;
    private int channels;
    private int[] sampleTypes;
    private MemorySegment[][] buffers;
    private MemorySegment outputReadyFn;
    private boolean postOutput;

    private Thread boostedThread;
    private volatile int callbackThreadPriority = Integer.MIN_VALUE;

    private volatile boolean resetRequested;
    private int bufferFrames;
    private int outputLatencyFrames;

    /** @param driverName registry name as returned by {@link #drivers()}, or null for the first one */
    public AsioOutput(String driverName) {
        this.driverName = driverName;
    }

    /** Names of installed 64-bit ASIO drivers. */
    public static List<String> drivers() {
        return new ArrayList<>(Win32.asioDrivers().keySet());
    }

    public int bufferFrames() {
        return bufferFrames;
    }

    public int outputLatencyFrames() {
        return outputLatencyFrames;
    }

    /** Windows priority of the driver's callback thread after boosting (15 = time critical); MIN_VALUE before the first callback. */
    public int callbackThreadPriority() {
        return callbackThreadPriority;
    }

    /** True once the driver has asked to be restarted (e.g. buffer size changed in its control panel). */
    public boolean resetRequested() {
        return resetRequested;
    }

    @Override
    public void start(int sampleRate, int periodFrames, Renderer renderer) {
        if (active != null) {
            throw new IllegalStateException("Another ASIO driver is already running");
        }
        Map<String, String> installed = Win32.asioDrivers();
        String name = driverName != null ? driverName
                : installed.keySet().stream().findFirst().orElse(null);
        String clsid = name != null ? installed.get(name) : null;
        if (clsid == null) {
            throw new IllegalStateException(name == null ? "No ASIO drivers installed"
                    : "ASIO driver not found: " + name + " (installed: " + installed.keySet() + ")");
        }

        arena = Arena.ofShared();
        try {
            int hr = Win32.coInitialize();
            comInitialized = hr == Win32.S_OK || hr == Win32.S_FALSE;
            if (!comInitialized && hr != Win32.RPC_E_CHANGED_MODE) {
                throw new IllegalStateException(String.format("CoInitializeEx failed: 0x%08X", hr));
            }
            driver = Win32.createAsioDriver(clsid, arena);
            vtable = driver.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0)
                    .reinterpret(ADDRESS.byteSize() * VTABLE_SLOTS);

            if (callIntPtr(INIT, Win32.desktopWindow()) == 0) {
                throw new IllegalStateException("ASIO init failed: " + errorMessage());
            }
            configure(sampleRate, periodFrames, renderer);
            active = this;
            check(callInt(START), "start");
            running = true;
        } catch (RuntimeException e) {
            stop();
            throw e;
        }
    }

    private void configure(int sampleRate, int periodFrames, Renderer renderer) {
        MemorySegment a = arena.allocate(JAVA_INT);
        MemorySegment b = arena.allocate(JAVA_INT);
        MemorySegment c = arena.allocate(JAVA_INT);
        MemorySegment d = arena.allocate(JAVA_INT);

        check(callIntPtr2(GET_CHANNELS, a, b), "getChannels");
        int outputs = b.get(JAVA_INT, 0);
        if (outputs < 1) {
            throw new IllegalStateException("Driver has no output channels");
        }
        channels = Math.min(2, outputs);

        MemorySegment rate = arena.allocate(JAVA_DOUBLE);
        check(callIntPtr(GET_SAMPLE_RATE, rate), "getSampleRate");
        if (rate.get(JAVA_DOUBLE, 0) != sampleRate) {
            if (callIntDouble(CAN_SAMPLE_RATE, sampleRate) != ASE_OK) {
                throw new IllegalStateException("Driver cannot run at " + sampleRate + " Hz");
            }
            check(callIntDouble(SET_SAMPLE_RATE, sampleRate), "setSampleRate");
        }

        check(callIntPtr4(GET_BUFFER_SIZE, a, b, c, d), "getBufferSize");
        frames = chooseBufferSize(periodFrames, a.get(JAVA_INT, 0), b.get(JAVA_INT, 0),
                c.get(JAVA_INT, 0), d.get(JAVA_INT, 0));

        sampleTypes = new int[channels];
        MemorySegment channelInfo = arena.allocate(CHANNEL_INFO_SIZE);
        for (int ch = 0; ch < channels; ch++) {
            channelInfo.fill((byte) 0);
            channelInfo.set(JAVA_INT, 0, ch);
            check(callIntPtr(GET_CHANNEL_INFO, channelInfo), "getChannelInfo");
            sampleTypes[ch] = channelInfo.get(JAVA_INT, CHANNEL_INFO_TYPE);
            if (bytesPerSample(sampleTypes[ch]) == 0) {
                throw new IllegalStateException("Unsupported ASIO sample type " + sampleTypes[ch]);
            }
        }

        MemorySegment infos = arena.allocate((long) BUFFER_INFO_SIZE * channels, 8);
        for (int ch = 0; ch < channels; ch++) {
            infos.set(JAVA_INT, (long) ch * BUFFER_INFO_SIZE, 0);      // isInput = false
            infos.set(JAVA_INT, (long) ch * BUFFER_INFO_SIZE + 4, ch); // channelNum
        }
        check(callCreateBuffers(infos, channels, frames), "createBuffers");
        buffersCreated = true;

        buffers = new MemorySegment[channels][2];
        for (int ch = 0; ch < channels; ch++) {
            long bytes = (long) frames * bytesPerSample(sampleTypes[ch]);
            for (int half = 0; half < 2; half++) {
                MemorySegment buf = infos.get(ADDRESS, (long) ch * BUFFER_INFO_SIZE + 8 + half * 8L)
                        .reinterpret(bytes);
                buf.fill((byte) 0);
                buffers[ch][half] = buf;
            }
        }

        if (callIntPtr2(GET_LATENCIES, a, b) == ASE_OK) {
            outputLatencyFrames = b.get(JAVA_INT, 0);
        }
        bufferFrames = frames;
        this.renderer = renderer;
        mix = new float[frames * 2];
        outputReadyFn = vtable.getAtIndex(ADDRESS, OUTPUT_READY);
        postOutput = callInt(OUTPUT_READY) == ASE_OK;
    }

    private static int chooseBufferSize(int wanted, int min, int max, int preferred, int granularity) {
        if (wanted < min || wanted > max) {
            return preferred;
        }
        if (granularity == -1) {
            return Integer.bitCount(wanted) == 1 ? wanted : preferred; // powers of two only
        }
        if (granularity > 0 && (wanted - min) % granularity == 0) {
            return wanted;
        }
        return preferred;
    }

    @Override
    public void stop() {
        if (running) {
            callInt(STOP);
            running = false;
        }
        active = null;
        if (buffersCreated) {
            callInt(DISPOSE_BUFFERS);
            buffersCreated = false;
        }
        if (!driver.equals(MemorySegment.NULL)) {
            callInt(RELEASE);
            driver = MemorySegment.NULL;
        }
        if (comInitialized) {
            Win32.coUninitialize();
            comInitialized = false;
        }
        if (arena != null) {
            arena.close();
            arena = null;
        }
    }

    public String driverDescription() {
        MemorySegment name = arena.allocate(64);
        callVoidPtr(GET_DRIVER_NAME, name);
        return name.getString(0);
    }

    private String errorMessage() {
        MemorySegment msg = arena.allocate(128);
        callVoidPtr(GET_ERROR_MESSAGE, msg);
        return msg.getString(0);
    }

    // ---- audio callback path: no allocation, no locks, never throws into native code ----

    private void process(int half) {
        Thread current = Thread.currentThread();
        if (current != boostedThread) {
            // ASIO4ALL calls back on a normal-priority thread, which Windows deschedules for
            // 50-300 ms at a time (measured: ~1 s of dropouts per 20 s, none once boosted).
            callbackThreadPriority = Win32.setCurrentThreadPriority(Win32.THREAD_PRIORITY_TIME_CRITICAL);
            boostedThread = current;
        }
        renderer.render(mix, frames);
        for (int ch = 0; ch < channels; ch++) {
            writeChannel(buffers[ch][half], sampleTypes[ch], ch);
        }
        if (postOutput) {
            try {
                int ignored = (int) CALL_INT.invokeExact(outputReadyFn, driver);
            } catch (Throwable t) {
                // nothing sensible to do on the driver thread
            }
        }
    }

    private void writeChannel(MemorySegment dst, int type, int ch) {
        float[] m = mix;
        int n = frames;
        switch (type) {
            case INT32_LSB -> {
                for (int f = 0; f < n; f++) dst.setAtIndex(JAVA_INT, f, (int) (clip(m[(f << 1) + ch]) * 2147483647.0));
            }
            case FLOAT32_LSB -> {
                for (int f = 0; f < n; f++) dst.setAtIndex(JAVA_FLOAT, f, clip(m[(f << 1) + ch]));
            }
            case INT16_LSB -> {
                for (int f = 0; f < n; f++) dst.setAtIndex(JAVA_SHORT, f, (short) (clip(m[(f << 1) + ch]) * 32767f));
            }
            case INT24_LSB -> {
                for (int f = 0; f < n; f++) {
                    int s = (int) (clip(m[(f << 1) + ch]) * 8388607f);
                    long o = f * 3L;
                    dst.set(JAVA_BYTE, o, (byte) s);
                    dst.set(JAVA_BYTE, o + 1, (byte) (s >> 8));
                    dst.set(JAVA_BYTE, o + 2, (byte) (s >> 16));
                }
            }
            case FLOAT64_LSB -> {
                for (int f = 0; f < n; f++) dst.setAtIndex(JAVA_DOUBLE, f, clip(m[(f << 1) + ch]));
            }
            default -> { // INT32_LSB16..24: right-aligned in a 32-bit container
                int bits = switch (type) {
                    case INT32_LSB16 -> 16;
                    case INT32_LSB18 -> 18;
                    case INT32_LSB20 -> 20;
                    default -> 24;
                };
                double scale = (1 << (bits - 1)) - 1;
                for (int f = 0; f < n; f++) dst.setAtIndex(JAVA_INT, f, (int) (clip(m[(f << 1) + ch]) * scale));
            }
        }
    }

    private static float clip(float x) {
        return x > 1f ? 1f : (x < -1f ? -1f : x);
    }

    private static int bytesPerSample(int type) {
        return switch (type) {
            case INT16_LSB -> 2;
            case INT24_LSB -> 3;
            case INT32_LSB, FLOAT32_LSB, INT32_LSB16, INT32_LSB18, INT32_LSB20, INT32_LSB24 -> 4;
            case FLOAT64_LSB -> 8;
            default -> 0;
        };
    }

    // ---- static callbacks handed to the driver ----

    private static void bufferSwitch(int half, int directProcess) {
        AsioOutput out = active;
        if (out != null) {
            try {
                out.process(half);
            } catch (Throwable t) {
                // an exception escaping an upcall would crash the JVM
            }
        }
    }

    private static MemorySegment bufferSwitchTimeInfo(MemorySegment params, int half, int directProcess) {
        bufferSwitch(half, directProcess);
        return params;
    }

    private static void sampleRateDidChange(double rate) {
        AsioOutput out = active;
        if (out != null) {
            out.resetRequested = true;
        }
    }

    private static int asioMessage(int selector, int value, MemorySegment message, MemorySegment opt) {
        switch (selector) {
            case SELECTOR_SUPPORTED:
                return value == ENGINE_VERSION || value == RESET_REQUEST || value == BUFFER_SIZE_CHANGE
                        || value == RESYNC_REQUEST || value == LATENCIES_CHANGED ? 1 : 0;
            case ENGINE_VERSION:
                return 2;
            case RESET_REQUEST:
            case BUFFER_SIZE_CHANGE:
                AsioOutput out = active;
                if (out != null) {
                    out.resetRequested = true;
                }
                return 1;
            case RESYNC_REQUEST:
            case LATENCIES_CHANGED:
                return 1;
            default:
                return 0; // includes kAsioSupportsTimeInfo: we use plain bufferSwitch
        }
    }

    private static MemorySegment createCallbacks() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Arena global = Arena.global();
            MemorySegment bufferSwitch = LINKER.upcallStub(
                    lookup.findStatic(AsioOutput.class, "bufferSwitch",
                            MethodType.methodType(void.class, int.class, int.class)),
                    FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT), global);
            MemorySegment rateChanged = LINKER.upcallStub(
                    lookup.findStatic(AsioOutput.class, "sampleRateDidChange",
                            MethodType.methodType(void.class, double.class)),
                    FunctionDescriptor.ofVoid(JAVA_DOUBLE), global);
            MemorySegment message = LINKER.upcallStub(
                    lookup.findStatic(AsioOutput.class, "asioMessage", MethodType.methodType(
                            int.class, int.class, int.class, MemorySegment.class, MemorySegment.class)),
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS), global);
            MemorySegment timeInfo = LINKER.upcallStub(
                    lookup.findStatic(AsioOutput.class, "bufferSwitchTimeInfo", MethodType.methodType(
                            MemorySegment.class, MemorySegment.class, int.class, int.class)),
                    FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT), global);

            MemorySegment callbacks = global.allocate(ADDRESS.byteSize() * 4, ADDRESS.byteAlignment());
            callbacks.setAtIndex(ADDRESS, 0, bufferSwitch);
            callbacks.setAtIndex(ADDRESS, 1, rateChanged);
            callbacks.setAtIndex(ADDRESS, 2, message);
            callbacks.setAtIndex(ADDRESS, 3, timeInfo);
            return callbacks;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // ---- IASIO method invocation (x64 has a single calling convention, so `this` is just arg 0) ----

    private MemorySegment method(int slot) {
        return vtable.getAtIndex(ADDRESS, slot);
    }

    private int callInt(int slot) {
        try {
            return (int) CALL_INT.invokeExact(method(slot), driver);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private int callIntPtr(int slot, MemorySegment p) {
        try {
            return (int) CALL_INT_PTR.invokeExact(method(slot), driver, p);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private void callVoidPtr(int slot, MemorySegment p) {
        try {
            CALL_VOID_PTR.invokeExact(method(slot), driver, p);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private int callIntPtr2(int slot, MemorySegment p1, MemorySegment p2) {
        try {
            return (int) CALL_INT_PTR2.invokeExact(method(slot), driver, p1, p2);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private int callIntPtr4(int slot, MemorySegment p1, MemorySegment p2, MemorySegment p3, MemorySegment p4) {
        try {
            return (int) CALL_INT_PTR4.invokeExact(method(slot), driver, p1, p2, p3, p4);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private int callIntDouble(int slot, double value) {
        try {
            return (int) CALL_INT_DOUBLE.invokeExact(method(slot), driver, value);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private int callCreateBuffers(MemorySegment infos, int count, int size) {
        try {
            return (int) CALL_CREATE_BUFFERS.invokeExact(method(CREATE_BUFFERS), driver, infos, count, size, CALLBACKS);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private void check(int err, String what) {
        if (err != ASE_OK && err != ASE_SUCCESS) {
            throw new IllegalStateException("ASIO " + what + " failed (" + err + "): " + errorMessage());
        }
    }
}
