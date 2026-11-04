package dev.phonedesk.companion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Host-test stand-in for the real CH340 driver. It touches no USB and no Android
 * class: it records every control-line write, decodes each command frame the
 * protocol sends, and lets a test answer with bytes the way the chip would.
 *
 * The real driver needs a UsbManager and a device, so the only way to run
 * {@link EspRom} on a JVM is to compile it against something with the same shape.
 */
final class UsbSerialCh340 {
    /** Set by the host tests to the clock they installed in EspRom. */
    static EspRom.Clock clock;


    /** Called for every command frame that arrives, with the checksum it carried. */
    interface Responder {
        void onCommand(UsbSerialCh340 port, int op, int checksum, byte[] payload) throws IOException;
    }

    /** Opcode and checksum of each command, in arrival order. */
    final List<int[]> commands = new ArrayList<>();
    /** Payload of each command, index-aligned with {@link #commands}. */
    final List<byte[]> payloads = new ArrayList<>();
    /** Every rate the protocol asked for. */
    final List<Integer> rates = new ArrayList<>();
    /** Every control-line write, as text, so a test can prove what moved. */
    final List<String> control = new ArrayList<>();

    private final ByteArrayOutputStream inbound = new ByteArrayOutputStream();
    private final ByteArrayOutputStream commandBytes = new ByteArrayOutputStream();
    /**
     * Every byte handed to the port, untouched — SLIP framing and all. The decoded
     * lists above share the header layout the protocol writes, so a mistake in that
     * layout would look correct to them; this is the copy an independent pin reads.
     */
    final ByteArrayOutputStream wire = new ByteArrayOutputStream();
    private boolean inFrame;
    private Responder responder;

    /** Refuse {@link #changeBaudRate(int)} the way a bridge without a divisor would. */
    boolean refuseRates;
    /** Fail every write, the way a bridge that will not take bytes would. */
    boolean failWrites;
    /** Text the chip prints on every pulse, the way the ROM prints its banner. */
    String printOnPulse;
    /**
     * Answer only once a control line has moved. The reset-path tests need this:
     * else the quiet phase's packets are answered and no reset ever happens.
     */
    boolean answerOnlyAfterPulse;
    int baud = 115200;

    void answer(Responder responder) {
        this.responder = responder;
    }

    /** Queue bytes for the next read, as if the chip had just sent them. */
    synchronized void inject(byte[] bytes) {
        inbound.write(bytes, 0, bytes.length);
    }

    // ------------------------------------------------------------- filling ---

    void open() {
        // Nothing to open on the host.
    }

    void close() {
        // Nothing to close.
    }

    /**
     * Decodes SLIP as it arrives, frame by frame. The protocol writes whole
     * commands, each one open-and-closed by 0xC0, so this can hand a decoded
     * command to the responder immediately.
     */
    void write(byte[] data) throws IOException {
        if (failWrites) throw new IOException("USB write failed");
        wire.write(data, 0, data.length);
        for (byte value : data) {
            final int current = value & 0xFF;
            if (current == 0xC0) {
                if (inFrame && commandBytes.size() > 0) deliver(commandBytes.toByteArray());
                commandBytes.reset();
                inFrame = true;
                continue;
            }
            if (!inFrame) continue;
            commandBytes.write(current);
        }
    }

    private void deliver(byte[] raw) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length);
        for (int i = 0; i < raw.length; ++i) {
            if ((raw[i] & 0xFF) == 0xDB && i + 1 < raw.length) {
                final int next = raw[++i] & 0xFF;
                out.write(next == 0xDC ? 0xC0 : next == 0xDD ? 0xDB : next);
            } else {
                out.write(raw[i]);
            }
        }
        final byte[] frame = out.toByteArray();
        if (frame.length < 8 || (frame[0] & 0xFF) != 0x00) return;
        final int op = frame[1] & 0xFF;
        final int size = (frame[2] & 0xFF) | ((frame[3] & 0xFF) << 8);
        final int checksum = (frame[4] & 0xFF) | ((frame[5] & 0xFF) << 8)
                | ((frame[6] & 0xFF) << 16) | ((frame[7] & 0xFF) << 24);
        final int end = Math.min(frame.length, 8 + size);
        final byte[] payload = Arrays.copyOfRange(frame, 8, end);
        commands.add(new int[]{ op, checksum });
        payloads.add(payload);
        if (responder != null && (!answerOnlyAfterPulse || !control.isEmpty())) {
            responder.onCommand(this, op, checksum, payload);
        }
    }

    // ------------------------------------------------------------- traffic ---

    /** Bytes read, or -1 when nothing is queued. Returns at most one buffer's worth. */
    int read(byte[] buffer, int timeoutMs) {
        synchronized (this) {
            if (inbound.size() == 0) {
                try {
                    // A real port blocks; sleeping a little keeps the caller's
                    // deadline loop from spinning at full speed.
                    final long pauseMs = Math.min(Math.max(timeoutMs, 1), 2);
                    if (clock != null) clock.sleep(pauseMs);
                    else Thread.sleep(pauseMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return -1;
            }
            final byte[] all = inbound.toByteArray();
            final int count = Math.min(buffer.length, all.length);
            System.arraycopy(all, 0, buffer, 0, count);
            inbound.reset();
            inbound.write(all, count, all.length - count);
            return count;
        }
    }

    synchronized void flushInput() {
        inbound.reset();
        commandBytes.reset();
        inFrame = false;
    }

    void changeBaudRate(int rate) throws IOException {
        if (refuseRates) throw new IOException("CH340 has no divisor for " + rate + " baud");
        rates.add(rate);
        baud = rate;
    }

    void setDtrRts(boolean dtr, boolean rts) {
        control.add("both dtr=" + dtr + " rts=" + rts);
        printBanner();
    }

    /**
     * The ROM prints its banner whenever EN is released, whichever line carried
     * that edge: a wiring the protocol drives one line at a time gets the same
     * answer as one it drives both at once. Injecting only from setDtrRts() made a
     * one-line pulse look silent in the tests while the real chip answered it,
     * which is how the round that reached download mode read as "no bytes".
     */
    void setDtr(boolean on) {
        control.add("dtr " + on);
        if (!on) printBanner();   // false is the line released high, i.e. out of reset
    }

    void setRts(boolean on) {
        control.add("rts " + on);
        if (!on) printBanner();
    }

    private void printBanner() {
        if (printOnPulse != null) inject(printOnPulse.getBytes(StandardCharsets.UTF_8));
    }
}
