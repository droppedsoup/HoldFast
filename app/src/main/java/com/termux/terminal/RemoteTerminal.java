package com.termux.terminal;

import java.lang.reflect.Field;

/**
 * HoldFast adapter: drives a Termux TerminalSession from a remote (SSH) byte stream
 * instead of a local pty. Lives in this package to reach package-private fields of the
 * Apache-2.0 terminal-emulator library.
 */
public final class RemoteTerminal {
    /** Fake pid: marks the session as running so keyboard input is queued. Never signalled. */
    private static final int REMOTE_PID = Integer.MAX_VALUE;

    private RemoteTerminal() {}

    public static TerminalSession create(int cols, int rows, int transcriptRows, TerminalSessionClient client) {
        TerminalSession s = new TerminalSession("/system/bin/sh", "/", new String[0], new String[0], transcriptRows, client);
        s.mEmulator = new TerminalEmulator(s, cols, rows, transcriptRows, client);
        s.mShellPid = REMOTE_PID;
        try {
            Field f = TerminalSession.class.getDeclaredField("mTerminalFileDescriptor");
            f.setAccessible(true);
            f.setInt(s, -1); // window-size ioctl becomes a harmless EBADF
        } catch (Exception ignored) {
        }
        return s;
    }

    /** Blocks until keyboard bytes are queued. Returns -1 once closed. */
    public static int readInput(TerminalSession s, byte[] buf) {
        return s.mTerminalToProcessIOQueue.read(buf, true);
    }

    public static void closeInput(TerminalSession s) {
        s.mTerminalToProcessIOQueue.close();
        s.mShellPid = -1;
    }

    /** Must be called on the main thread. */
    public static void feed(TerminalSession s, byte[] data, int len) {
        s.mEmulator.append(data, len);
    }
}
