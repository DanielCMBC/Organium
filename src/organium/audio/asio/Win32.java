package organium.audio.asio;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** The handful of Win32/COM calls needed to find and instantiate ASIO drivers. */
final class Win32 {
    static final int S_OK = 0;
    static final int S_FALSE = 1;
    static final int RPC_E_CHANGED_MODE = 0x80010106;

    private static final int COINIT_APARTMENTTHREADED = 0x2;
    private static final int CLSCTX_INPROC_SERVER = 0x1;
    private static final long HKEY_LOCAL_MACHINE = 0xFFFFFFFF80000002L; // (HKEY)(LONG)0x80000002
    private static final int KEY_READ = 0x20019;
    private static final int KEY_WOW64_64KEY = 0x0100;
    private static final int RRF_RT_REG_SZ = 0x2;
    private static final int RRF_SUBKEY_WOW6464KEY = 0x10000;
    private static final int ERROR_SUCCESS = 0;
    private static final int ERROR_NO_MORE_ITEMS = 259;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup OLE32 = SymbolLookup.libraryLookup("ole32.dll", Arena.global());
    private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32.dll", Arena.global());
    private static final SymbolLookup ADVAPI32 = SymbolLookup.libraryLookup("advapi32.dll", Arena.global());
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());

    private static final MethodHandle CO_INITIALIZE_EX = function(OLE32, "CoInitializeEx",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle CO_UNINITIALIZE = function(OLE32, "CoUninitialize",
            FunctionDescriptor.ofVoid());
    private static final MethodHandle CO_CREATE_INSTANCE = function(OLE32, "CoCreateInstance",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle CLSID_FROM_STRING = function(OLE32, "CLSIDFromString",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle GET_DESKTOP_WINDOW = function(USER32, "GetDesktopWindow",
            FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle GET_CURRENT_THREAD = function(KERNEL32, "GetCurrentThread",
            FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle SET_THREAD_PRIORITY = function(KERNEL32, "SetThreadPriority",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle GET_THREAD_PRIORITY = function(KERNEL32, "GetThreadPriority",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle REG_OPEN_KEY_EX = function(ADVAPI32, "RegOpenKeyExW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle REG_ENUM_KEY_EX = function(ADVAPI32, "RegEnumKeyExW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle REG_GET_VALUE = function(ADVAPI32, "RegGetValueW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle REG_CLOSE_KEY = function(ADVAPI32, "RegCloseKey",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private Win32() {
    }

    private static MethodHandle function(SymbolLookup lib, String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(lib.find(name).orElseThrow(), descriptor);
    }

    static int coInitialize() {
        try {
            return (int) CO_INITIALIZE_EX.invokeExact(MemorySegment.NULL, COINIT_APARTMENTTHREADED);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static void coUninitialize() {
        try {
            CO_UNINITIALIZE.invokeExact();
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static final int THREAD_PRIORITY_TIME_CRITICAL = 15;

    /**
     * Raises the calling thread to {@code priority} and returns the priority it ends up with.
     * Never throws: it runs on driver-owned threads, where an exception would crash the JVM.
     */
    static int setCurrentThreadPriority(int priority) {
        try {
            MemorySegment self = (MemorySegment) GET_CURRENT_THREAD.invokeExact(); // pseudo-handle, no need to close
            int ignored = (int) SET_THREAD_PRIORITY.invokeExact(self, priority);
            return (int) GET_THREAD_PRIORITY.invokeExact(self);
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }

    static MemorySegment desktopWindow() {
        try {
            return (MemorySegment) GET_DESKTOP_WINDOW.invokeExact();
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** ASIO drivers are created with their CLSID doubling as the interface ID. */
    static MemorySegment createAsioDriver(String clsid, Arena arena) {
        try {
            MemorySegment guid = arena.allocate(16);
            int hr = (int) CLSID_FROM_STRING.invokeExact(wide(clsid, arena), guid);
            if (hr != S_OK) {
                throw new IllegalStateException("Bad CLSID " + clsid);
            }
            MemorySegment out = arena.allocate(ADDRESS);
            hr = (int) CO_CREATE_INSTANCE.invokeExact(guid, MemorySegment.NULL, CLSCTX_INPROC_SERVER, guid, out);
            if (hr != S_OK) {
                throw new IllegalStateException(String.format("CoCreateInstance failed: 0x%08X", hr));
            }
            return out.get(ADDRESS, 0);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** Driver name to CLSID, from HKLM\SOFTWARE\ASIO (64-bit view). */
    static Map<String, String> asioDrivers() {
        Map<String, String> drivers = new LinkedHashMap<>();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment keyOut = arena.allocate(ADDRESS);
            int rc = (int) REG_OPEN_KEY_EX.invokeExact(MemorySegment.ofAddress(HKEY_LOCAL_MACHINE),
                    wide("SOFTWARE\\ASIO", arena), 0, KEY_READ | KEY_WOW64_64KEY, keyOut);
            if (rc != ERROR_SUCCESS) {
                return drivers;
            }
            MemorySegment key = keyOut.get(ADDRESS, 0);
            MemorySegment name = arena.allocate(512);
            MemorySegment nameLength = arena.allocate(JAVA_INT);
            MemorySegment value = arena.allocate(512);
            MemorySegment valueSize = arena.allocate(JAVA_INT);
            MemorySegment clsidName = wide("CLSID", arena);
            try {
                for (int i = 0; ; i++) {
                    nameLength.set(JAVA_INT, 0, 256);
                    rc = (int) REG_ENUM_KEY_EX.invokeExact(key, i, name, nameLength, MemorySegment.NULL,
                            MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
                    if (rc == ERROR_NO_MORE_ITEMS) {
                        break;
                    }
                    if (rc != ERROR_SUCCESS) {
                        continue;
                    }
                    valueSize.set(JAVA_INT, 0, 512);
                    rc = (int) REG_GET_VALUE.invokeExact(key, name, clsidName,
                            RRF_RT_REG_SZ | RRF_SUBKEY_WOW6464KEY, MemorySegment.NULL, value, valueSize);
                    if (rc == ERROR_SUCCESS) {
                        drivers.put(name.getString(0, StandardCharsets.UTF_16LE),
                                value.getString(0, StandardCharsets.UTF_16LE));
                    }
                }
            } finally {
                int ignored = (int) REG_CLOSE_KEY.invokeExact(key);
            }
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        return drivers;
    }

    private static MemorySegment wide(String s, Arena arena) {
        return arena.allocateFrom(s, StandardCharsets.UTF_16LE);
    }
}
