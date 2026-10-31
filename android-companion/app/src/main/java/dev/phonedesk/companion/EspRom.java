package dev.phonedesk.companion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

/**
 * The ESP32 ROM bootloader's serial protocol: SLIP framing, the sync and
 * register commands used for identification, and the flash commands that write
 * an image and verify it.
 *
 * Framing matches esptool and esp-serial-flasher (Apache-2.0): a command is
 * 0x00, opcode, size, checksum, payload — SLIP-encoded; a reply is 0x01, opcode,
 * size, value, payload. Register values and command status both come from the
 * reply header, which is what esptool's check_command does.
 */
final class EspRom {

    private static final int OP_FLASH_BEGIN = 0x02;
    private static final int OP_FLASH_DATA = 0x03;
    private static final int OP_FLASH_END = 0x04;
    private static final int OP_SYNC = 0x08;
    private static final int OP_READ_REG = 0x0A;
    private static final int OP_SPI_FLASH_MD5 = 0x13;

    /** Largest block the ROM accepts for a flash write. */
    static final int FLASH_BLOCK = 0x1000;
    private static final int CHECKSUM_SEED = 0xEF;
    private static final int BLOCK_ATTEMPTS = 3;

    /** Register whose value differs per chip; 0x00f01d83 is the ESP32. */
    private static final int CHIP_DETECT_MAGIC = 0x40001000;
    private static final int ESP32_MAGIC = 0x00F01D83;

    /** eFuse block holding the factory MAC; words 0x04 and 0x08 of it. */
    private static final int EFUSE_BASE = 0x3FF5A000;
    private static final int MAC_OFFSET = 0x04;

    private static final byte SLIP_END = (byte) 0xC0;
    private static final byte SLIP_ESC = (byte) 0xDB;
    private static final byte SLIP_ESC_END = (byte) 0xDC;
    private static final byte SLIP_ESC_ESC = (byte) 0xDD;

    /** How much of a silent/failing line to keep for the hex preview. */
    private static final int PREVIEW_BYTES = 32;
    /**
     * How much of the proof-of-life window to keep as text. A ROM banner is about
     * 400 bytes and its boot-mode line is not the first one: 32 bytes kept only
     * the first line, which is why a 2026-10-30 log showed `ets Jul 29 2019`
     * and nothing about `boot:0x13` or `boot:0x3`.
     */
    private static final int BANNER_BYTES = 600;

    private static final int SYNC_ATTEMPTS = 12;
    private static final int CONNECT_ROUNDS = 3;
    private static final int ALT_SYNC_ATTEMPTS = 6;
    private static final int ALT_CONNECT_ROUNDS = 2;

    /**
     * Line rates tried in order until the board answers.
     *
     * 115200 is what the ROM expects. The rest cover a bridge whose crystal is
     * far enough off that the default divisor produces garbage, which is the one
     * failure a software flasher can actually recover from: the ROM measures the
     * sync packet to learn the rate, so a handshake that succeeds at 57600 keeps
     * working at 57600 for the rest of the session.
     */
    private static final int[] BAUDS = {115200, 57600, 230400, 460800, 74880};

    /**
     * Rates the chip prints readable boot text at. The ESP32 ROM talks at 115200;
     * 74880 is the 26 MHz suite the ESP8266 uses and some ESP32 boards emit
     * before their clock is set. Both are probed because guessing one of them is
     * how a working cable was once reported as dead.
     */
    private static final int[] BANNER_BAUDS = {115200, 74880};

    interface Progress {
        void update(int percent, String note);
    }

    /** Where the handshake narrates itself, one line at a time, for the UI log. */
    interface Trace {
        void line(String text);
    }

    private final UsbSerialCh340 port;
    private Trace trace;
    private boolean connected;
    /** Every byte the board sent at the current rate. */
    private long bytesIn;
    /**
     * The first bytes of the current rate, kept raw so a failed handshake can
     * show what actually arrived instead of only that nothing matched.
     */
    private final ByteArrayOutputStream preview = new ByteArrayOutputStream();
    /** How many bytes {@link #preview} may hold; widened while reading a banner. */
    private int previewCap = PREVIEW_BYTES;
    /** Line rate the port is currently programmed to. */
    private int baud = 115200;
    /** Every byte handed to the bridge at the current rate, to prove the send path. */
    private long bytesOut;
    /** Whether the board's reset transistors invert DTR/RTS; see detectPolarity(). */
    private boolean invertControl;
    /** Whether the handshake may touch DTR/RTS at all; see setManualReset(). */
    private boolean manualReset;
    /** True once a plain reset produced readable boot text, not just noise. */
    private boolean bannerSeen;

    EspRom(UsbSerialCh340 port) {
        this.port = port;
    }

    /** Optional: without a trace the handshake behaves exactly as before. */
    void setTrace(Trace trace) {
        this.trace = trace;
    }

    /**
     * Leave DTR/RTS alone for the whole handshake. For a board whose IO0 leg is
     * not wired to the adapter, BOOT has to be held by hand while the board is
     * reset, and anything this code did to the lines would undo that: connect()
     * then skips the proof of life and the reset rounds, and only sends syncs.
     */
    void setManualReset(boolean manual) {
        manualReset = manual;
    }

    private void log(String text) {
        if (trace != null) trace.line(text);
    }

    /**
     * The bytes seen at this rate as hex, or "nothing" when the line was quiet.
     * Capped at {@link #PREVIEW_BYTES}: a banner window keeps far more than is
     * readable as hex, and the text is what matters there.
     */
    private String previewHex() {
        if (preview.size() == 0) return "nothing";
        final byte[] raw = preview.toByteArray();
        final int shown = Math.min(raw.length, PREVIEW_BYTES);
        final StringBuilder hex = new StringBuilder(shown * 3);
        for (int i = 0; i < shown; ++i) {
            if (i > 0) hex.append(' ');
            hex.append(String.format(Locale.US, "%02x", raw[i] & 0xFF));
        }
        if (bytesIn > shown) hex.append(" …");
        return hex.toString();
    }

    static final class Detected {
        final String chip;
        final String mac;
        /** The rate the bootloader actually answered at. */
        final int baud;
        /** Rates that were silent before it answered, for the status line. */
        final String triedBauds;

        Detected(String chip, String mac, int baud, String triedBauds) {
            this.chip = chip;
            this.mac = mac;
            this.baud = baud;
            this.triedBauds = triedBauds;
        }
    }

    // ------------------------------------------------------------- connect ---

    /**
     * Get the board into its bootloader and confirm the chip type.
     *
     * Every round resets, because detectPolarity() has just left the chip running
     * its firmware — a board already sitting in download mode is not the case by
     * the time this loop starts.
     */
    Detected connect() throws IOException {
        final StringBuilder silent = new StringBuilder();
        boolean synced = false;

        log("sync: " + BAUDS.length + " rates queued, " + CONNECT_ROUNDS
                + " reset rounds x " + SYNC_ATTEMPTS + " sync packets at the first");
        if (manualReset) {
            log("manual mode: the control lines are never touched. Hold BOOT and tap the"
                    + " board's reset while this sweeps, then let go of BOOT.");
        } else {
            detectPolarity();
        }
        // Whatever polarity the boot text arrived on is the starting point; the
        // rounds then alternate away from it and back.
        final boolean base = invertControl;

        for (int index = 0; index < BAUDS.length && !synced; ++index) {
            final int rate = BAUDS[index];
            final boolean first = index == 0;
            try {
                changeBaud(rate);
            } catch (IOException e) {
                log(rate + " baud: bridge refused the rate (" + e.getMessage() + ")");
                if (silent.length() > 0) silent.append(", ");
                silent.append(rate).append("?");   // the bridge refused this rate
                continue;
            }
            // Counted per rate: a total across five attempts says nothing about
            // which one the board was actually talking at.
            bytesIn = 0;
            bytesOut = 0;
            preview.reset();

            // Manual mode runs one round per rate: there is no polarity to alternate
            // when nothing touches the lines, and the sweep should stay listening
            // long enough for a hand on the BOOT button.
            final int rounds = manualReset ? 1 : (first ? CONNECT_ROUNDS : ALT_CONNECT_ROUNDS);
            final int attempts = first ? SYNC_ATTEMPTS : ALT_SYNC_ATTEMPTS;
            log(rate + " baud: " + rounds + " rounds of " + attempts + " syncs");
            for (int round = 0; round < rounds && !synced; ++round) {
                // Alternate the control polarity round by round. An inverted
                // EN/IO0 pair holds the chip in reset for the whole handshake,
                // which reads exactly like a dead link: no bytes, every rate.
                invertControl = base ^ ((round & 1) == 1);
                if (manualReset) {
                    log("  round " + (round + 1) + ": sync only, control lines left alone");
                } else {
                    enterBootloader();
                    sleep(120);
                    log("  round " + (round + 1) + ": reset into download mode"
                            + (invertControl ? ", inverted lines" : ""));
                }
                try {
                    sync(attempts);
                    synced = true;
                } catch (WriteFailure e) {
                    // The bridge is refusing bytes: no other rate will change that.
                    log("failed: " + e.getMessage());
                    throw e;
                } catch (IOException e) {
                    // Try the next reset round, then the next rate.
                    log("  round " + (round + 1) + ": " + bytesIn + " B in / " + bytesOut
                            + " B out, " + previewHex() + " (" + e.getMessage() + ")");
                }
            }
            invertControl = base;
            if (!synced) {
                log(rate + " baud: no sync reply");
                if (silent.length() > 0) silent.append(", ");
                silent.append(rate);
            } else {
                log("synced at " + rate + " baud");
            }
        }

        if (!synced) {
            final String rates = silent.length() == 0 ? "none" : silent.toString();
            final String message = "no answer from the bootloader at any of "
                    + BAUDS.length + " baud rates (" + rates + ")"
                    + (manualReset
                    ? ", with the control lines left alone. Nothing in this run reset the"
                            + " board, so hold BOOT and tap its reset while the sweep"
                            + " listens, then let go of BOOT."
                    : ", in either reset polarity. " + (bannerSeen
                    ? "Boot text did arrive after a reset, so the cable, the adapter"
                            + " and the reset wiring work and the chip runs when it is"
                            + " released. Every sync round still saw silence, so the"
                            + " reset is not leaving it in download mode (IO0) or the sync"
                            + " packets never reach the ROM."
                    : "Not one readable byte arrived after a plain reset, at 115200 or"
                            + " 74880, in either polarity. That points at the cable, the"
                            + " adapter's receive line or a board held in reset."));
            throw new IOException(message);
        }

        final int magic = readRegister(CHIP_DETECT_MAGIC);
        log(String.format(Locale.US, "chip magic 0x%08x", magic));
        if (magic != ESP32_MAGIC) {
            throw new IOException(String.format(Locale.US,
                    "bootloader answered, but chip magic is 0x%08x — not an ESP32", magic));
        }
        String mac = "unreadable";
        try {
            mac = readMac();
        } catch (IOException e) {
            // Detection already succeeded; the MAC is a bonus.
            log("MAC unreadable: " + e.getMessage());
        }
        log("MAC " + mac);
        connected = true;
        return new Detected("ESP32", mac, baud, silent.toString());
    }

    /** Which rate answered, so the UI can say so when it was not the default. */
    int baudRate() {
        return baud;
    }

    private void changeBaud(int rate) throws IOException {
        if (rate == baud) return;
        port.changeBaudRate(rate);
        baud = rate;
        sleep(40);
    }

    /**
     * DTR drives IO0 and RTS drives EN through the board's two reset
     * transistors. Some CYD revisions wire that pair the other way round, which
     * holds the chip in reset for the whole probe: silence at every rate and no
     * boot banner either. Trying both polarities costs a second and tells that
     * apart from a dead cable.
     */
    private void lines(boolean io0Low, boolean enLow) throws IOException {
        port.setDtrRts(invertControl ? !io0Low : io0Low, invertControl ? !enLow : enLow);
    }

    private void changeBaudQuietly(int rate) {
        if (rate == baud) return;
        try {
            port.changeBaudRate(rate);
            baud = rate;
            sleep(40);
        } catch (IOException e) {
            log(rate + " baud: bridge refused the rate (" + e.getMessage() + ")");
        }
    }

    /**
     * esptool's classic reset: hold EN low, drop IO0 low, release EN, then
     * release IO0 — the ROM samples the strapping pin as it leaves reset.
     */
    private void enterBootloader() throws IOException {
        port.flushInput();
        lines(false, true);   // IO0 high, EN low (held in reset)
        sleep(150);
        lines(true, false);   // IO0 low, EN released: the ROM samples IO0
        sleep(120);
        lines(false, false);  // IO0 high again, chip now in download mode
        sleep(120);
    }

    /**
     * Reboot whatever is in flash with IO0 high. This is what brings a board
     * back out of download mode after a failed probe: reset only, no bootloader
     * entry, so the firmware runs again.
     */
    void hardReset() throws IOException {
        log("reset: IO0 high, EN pulsed (boot from flash)");
        port.flushInput();
        lines(false, false); // IO0 high, EN released
        sleep(60);
        lines(false, true);  // EN low: reset
        sleep(150);
        lines(false, false); // EN released, IO0 high: boot from flash
        sleep(200);
        connected = false;
    }

    /**
     * Listen for readable boot text once per rate and polarity. The chip says
     * something on every reset whatever is in flash, so *text* here proves the
     * cable, the adapter and the reset wiring all work — which "0 B at five
     * rates" cannot tell you on its own.
     *
     * Bytes that are not text are recorded as noise rather than success: a board
     * talking at a rate we are not listening on produces exactly that, and
     * treating it as proof of life locks in the wrong polarity. That mistake cost
     * a round trip on hardware.
     */
    private void detectPolarity() throws IOException {
        log("proof of life: listening at 115200 and 74880 for boot text after a reset");
        boolean noiseOnly = false;
        for (int candidate : BANNER_BAUDS) {
            changeBaudQuietly(candidate);
            for (boolean inverted : new boolean[]{false, true}) {
                invertControl = inverted;
                final int seen = listenAfterReset(600);
                final String text = textPreview();
                log("  " + candidate + (inverted ? " inverted: " : " normal: ") + seen + " B"
                        + (seen == 0 ? ", nothing"
                        : text.isEmpty() ? ", no text — " + previewHex()
                        : " — \"" + bannerText() + "\""));
                if (!text.isEmpty()) {
                    bannerSeen = true;
                    log("  boot text arrived, so the cable, the adapter and the reset"
                            + " wiring work; keeping "
                            + (inverted ? "inverted" : "normal") + " lines");
                    return;
                }
                noiseOnly |= seen > 0;
            }
        }
        invertControl = false;
        log(noiseOnly
                ? "  bytes arrived, none of them text: the board talked at some other"
                        + " rate, so the data path works but the rate is unknown"
                : "  nothing at either rate in either polarity — the failure line says"
                        + " what that leaves");
    }

    /** The longest printable-ASCII run in the preview, or "" when it is all noise. */
    private String textPreview() {
        final byte[] raw = preview.toByteArray();
        final StringBuilder best = new StringBuilder();
        final StringBuilder run = new StringBuilder();
        for (byte value : raw) {
            final int c = value & 0xFF;
            if (c >= 0x20 && c <= 0x7E) {
                run.append((char) c);
                if (run.length() > best.length()) {
                    best.setLength(0);
                    best.append(run);
                }
            } else {
                run.setLength(0);
            }
        }
        return best.length() >= 6 ? best.toString() : "";
    }

    /**
     * The whole captured window as one line: printable text kept, line breaks and
     * control bytes ending a run. This is what the log quotes, so the boot-mode
     * line and the ROM's build date are both visible instead of only the first
     * line.
     */
    private String bannerText() {
        final byte[] raw = preview.toByteArray();
        final StringBuilder out = new StringBuilder(raw.length);
        boolean gap = false;
        for (byte value : raw) {
            final int c = value & 0xFF;
            if (c >= 0x20 && c <= 0x7E) {
                if (gap && out.length() > 0) out.append(' ');
                gap = false;
                out.append((char) c);
            } else {
                gap = out.length() > 0;
            }
        }
        final String text = out.toString().trim();
        return bytesIn > raw.length ? text + " …" : text;
    }

    /** Bytes the board sent in the window after a reset with IO0 high. */
    private int listenAfterReset(int windowMs) throws IOException {
        bytesIn = 0;
        preview.reset();
        previewCap = BANNER_BYTES;
        port.flushInput();
        lines(false, true);   // EN low: reset, IO0 stays high
        sleep(120);
        lines(false, false);  // EN released: the ROM prints its banner now
        final long deadline = System.currentTimeMillis() + windowMs;
        final byte[] buffer = new byte[64];
        while (System.currentTimeMillis() < deadline) {
            final int read = port.read(buffer, 50);
            if (read <= 0) continue;
            bytesIn += read;
            for (int i = 0; i < read && preview.size() < previewCap; ++i) {
                preview.write(buffer[i]);
            }
        }
        previewCap = PREVIEW_BYTES;
        return (int) bytesIn;
    }

    private void sync(int attempts) throws IOException {
        final byte[] payload = new byte[36];
        payload[0] = 0x07;
        payload[1] = 0x07;
        payload[2] = 0x12;
        payload[3] = 0x20;
        Arrays.fill(payload, 4, payload.length, (byte) 0x55);

        for (int attempt = 0; attempt < attempts; ++attempt) {
            sendCommand(OP_SYNC, payload, 0);
            if (awaitResponse(OP_SYNC, 200) != null) return;
            sleep(50);
        }
        throw new IOException("no answer from the bootloader");
    }

    // --------------------------------------------------------------- flash ---

    /**
     * Erase and write an image through the ROM loader, verify it by MD5, then
     * reboot into it. Nothing is erased until the first flash command is
     * accepted, so a board that never syncs keeps its firmware.
     */
    void flash(int offset, byte[] image, Progress progress) throws IOException {
        if (!connected) throw new IOException("not connected to the bootloader");
        if (image.length == 0) throw new IOException("image is empty");
        if (offset % FLASH_BLOCK != 0) {
            throw new IOException("offset must be a multiple of " + FLASH_BLOCK);
        }

        final int blocks = (image.length + FLASH_BLOCK - 1) / FLASH_BLOCK;
        final int eraseSize = blocks * FLASH_BLOCK;

        log("flash: " + image.length + " B in " + blocks + " blocks at 0x"
                + Integer.toHexString(offset) + ", erasing " + (eraseSize / 1024) + " kB");
        progress.update(0, "erasing " + (eraseSize / 1024) + " kB");
        final byte[] begin = new byte[16];
        putU32(begin, 0, eraseSize);
        putU32(begin, 4, blocks);
        putU32(begin, 8, FLASH_BLOCK);
        putU32(begin, 12, offset);
        sendCommand(OP_FLASH_BEGIN, begin, 0);
        awaitStatus(OP_FLASH_BEGIN, 30000);  // the ROM erases before replying

        for (int sequence = 0; sequence < blocks; ++sequence) {
            final int from = sequence * FLASH_BLOCK;
            final int to = Math.min(image.length, from + FLASH_BLOCK);
            byte[] block = Arrays.copyOfRange(image, from, to);
            if (block.length % 4 != 0) {
                block = Arrays.copyOf(block, block.length + (4 - block.length % 4));
                Arrays.fill(block, to - from, block.length, (byte) 0xFF);
            }
            writeBlock(block, sequence);
            progress.update(1 + (sequence + 1) * 90 / blocks,
                    "writing block " + (sequence + 1) + "/" + blocks);
            if ((sequence + 1) % 16 == 0 || sequence + 1 == blocks) {
                log("  block " + (sequence + 1) + "/" + blocks + " accepted");
            }
        }

        progress.update(92, "verifying MD5");
        verifyMd5(offset, image);

        log("rebooting into the new firmware");
        progress.update(98, "rebooting into the new firmware");
        sendCommand(OP_FLASH_END, new byte[]{0, 0, 0, 0}, 0);  // 0 = reboot
        connected = false;
        sleep(300);
        hardReset();
        progress.update(100, "written and verified");
    }

    private void writeBlock(byte[] block, int sequence) throws IOException {
        final byte[] payload = new byte[16 + block.length];
        putU32(payload, 0, block.length);
        putU32(payload, 4, sequence);
        putU32(payload, 8, 0);
        putU32(payload, 12, 0);
        System.arraycopy(block, 0, payload, 16, block.length);
        final int checksum = checksum(block);

        IOException last = null;
        for (int attempt = 0; attempt < BLOCK_ATTEMPTS; ++attempt) {
            try {
                sendCommand(OP_FLASH_DATA, payload, checksum);
                awaitStatus(OP_FLASH_DATA, 3000);
                return;
            } catch (IOException e) {
                last = e;  // the ROM is allowed to miss one block; send it again
                log("  block " + sequence + " attempt " + (attempt + 1) + " failed: "
                        + e.getMessage());
            }
        }
        final String message = "block " + sequence + " would not write: "
                + (last == null ? "unknown error" : last.getMessage());
        log("failed: " + message);
        throw new IOException(message);
    }

    private void verifyMd5(int offset, byte[] image) throws IOException {
        final byte[] payload = new byte[16];
        putU32(payload, 0, offset);
        putU32(payload, 4, image.length);
        sendCommand(OP_SPI_FLASH_MD5, payload, 0);

        final Response response = awaitResponse(OP_SPI_FLASH_MD5, 10000);
        if (response == null) throw new IOException("no answer to the MD5 check");
        if (response.data.length >= 2 && response.data[0] != 0) {
            throw new IOException(String.format(Locale.US,
                    "the board rejected the MD5 check (status 0x%02x)", response.data[0] & 0xFF));
        }

        // The ROM answers with 32 ASCII hex characters, sometimes followed by
        // padding; take the hex run and stop at the first byte that is not hex.
        final StringBuilder reported = new StringBuilder(32);
        for (byte value : response.data) {
            final char c = (char) (value & 0xFF);
            if (Character.digit(c, 16) >= 0) reported.append(Character.toLowerCase(c));
            else if (reported.length() > 0) break;
        }
        final String expected = md5Hex(image);
        log("MD5 board " + reported + ", file " + expected);
        if (!reported.toString().startsWith(expected)) {
            throw new IOException("MD5 mismatch: the board has " + reported
                    + ", the file is " + expected);
        }
    }

    private static String md5Hex(byte[] data) {
        try {
            final byte[] digest = MessageDigest.getInstance("MD5").digest(data);
            final StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format(Locale.US, "%02x", value & 0xFF));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is required by the bootloader", e);
        }
    }

    private static int checksum(byte[] data) {
        int state = CHECKSUM_SEED;
        for (byte value : data) state ^= value;
        return state & 0xFF;
    }

    // ---------------------------------------------------------- registers ---

    private int readRegister(int address) throws IOException {
        final byte[] payload = new byte[4];
        putU32(payload, 0, address);
        sendCommand(OP_READ_REG, payload, 0);

        final Response response = awaitResponse(OP_READ_REG, 700);
        if (response == null) {
            throw new IOException(String.format(Locale.US,
                    "timed out reading register 0x%08x", address));
        }
        if (response.data.length >= 2 && response.data[0] != 0) {
            throw new IOException(String.format(Locale.US,
                    "bootloader rejected the read of 0x%08x (status 0x%02x)",
                    address, response.data[0] & 0xFF));
        }
        return response.value;
    }

    private String readMac() throws IOException {
        final int part1 = readRegister(EFUSE_BASE + MAC_OFFSET);
        final int part2 = readRegister(EFUSE_BASE + MAC_OFFSET + 4);
        return String.format(Locale.US, "%02x:%02x:%02x:%02x:%02x:%02x",
                (part2 >>> 8) & 0xFF, part2 & 0xFF,
                (part1 >>> 24) & 0xFF, (part1 >>> 16) & 0xFF,
                (part1 >>> 8) & 0xFF, part1 & 0xFF);
    }

    // -------------------------------------------------------------- framing ---

    /** A failure to hand bytes to the bridge, as opposed to a silent board. */
    private static final class WriteFailure extends IOException {
        WriteFailure(String message) {
            super(message);
        }
    }

    private static final class Response {
        final int op;
        final int value;
        final byte[] data;

        Response(int op, int value, byte[] data) {
            this.op = op;
            this.value = value;
            this.data = data;
        }
    }

    private void sendCommand(int op, byte[] payload, int checksum) throws IOException {
        final byte[] packet = new byte[8 + payload.length];
        packet[0] = 0x00;                                  // direction: host to chip
        packet[1] = (byte) op;
        packet[2] = (byte) (payload.length & 0xFF);
        packet[3] = (byte) ((payload.length >> 8) & 0xFF);
        putU32(packet, 4, checksum);
        System.arraycopy(payload, 0, packet, 8, payload.length);
        final byte[] framed = slipEncode(packet);
        try {
            port.write(framed);
        } catch (IOException e) {
            // Counted separately from a silent board: these two look identical in
            // a per-rate byte count and need opposite fixes.
            throw new WriteFailure("the bridge would not take a " + framed.length
                    + " B command: " + e.getMessage());
        }
        bytesOut += framed.length;
    }

    private void awaitStatus(int op, int timeoutMs) throws IOException {
        final Response response = awaitResponse(op, timeoutMs);
        if (response == null) {
            throw new IOException(String.format(Locale.US,
                    "timed out waiting for command 0x%02x", op));
        }
        if (response.data.length >= 2 && response.data[0] != 0) {
            throw new IOException(String.format(Locale.US,
                    "the board rejected command 0x%02x (status 0x%02x)",
                    op, response.data[0] & 0xFF));
        }
    }

    /** Waits for a reply with the given opcode, skipping anything else. */
    private Response awaitResponse(int op, int timeoutMs) throws IOException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            final byte[] frame = readFrame(150);
            if (frame == null) continue;
            if (frame.length < 8 || frame[0] != 0x01) continue;
            final int replyOp = frame[1] & 0xFF;
            if (replyOp != op) continue;                   // e.g. a late sync echo
            return new Response(replyOp, u32(frame, 4),
                    Arrays.copyOfRange(frame, 8, frame.length));
        }
        return null;
    }

    /** One SLIP frame, unescaped, or null when nothing complete arrived. */
    private byte[] readFrame(int timeoutMs) throws IOException {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        final byte[] one = new byte[1];
        ByteArrayOutputStream body = null;

        while (System.currentTimeMillis() < deadline) {
            if (port.read(one, 40) <= 0) continue;
            ++bytesIn;
            final int value = one[0] & 0xFF;
            if (preview.size() < previewCap) preview.write(value);
            if (value == (SLIP_END & 0xFF)) {
                if (body != null && body.size() > 0) return unescape(body.toByteArray());
                body = new ByteArrayOutputStream();
                continue;
            }
            if (body == null) continue;                    // still waiting for an opening C0
            body.write(value);
        }
        return null;
    }

    private static byte[] slipEncode(byte[] packet) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(packet.length + 4);
        out.write(SLIP_END);
        for (byte value : packet) {
            if (value == SLIP_END) {
                out.write(SLIP_ESC);
                out.write(SLIP_ESC_END);
            } else if (value == SLIP_ESC) {
                out.write(SLIP_ESC);
                out.write(SLIP_ESC_ESC);
            } else {
                out.write(value);
            }
        }
        out.write(SLIP_END);
        return out.toByteArray();
    }

    private static byte[] unescape(byte[] raw) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length);
        for (int i = 0; i < raw.length; ++i) {
            final byte value = raw[i];
            if (value == SLIP_ESC && i + 1 < raw.length) {
                final byte next = raw[++i];
                if (next == SLIP_ESC_END) out.write(SLIP_END);
                else if (next == SLIP_ESC_ESC) out.write(SLIP_ESC);
                else out.write(next);
            } else {
                out.write(value);
            }
        }
        return out.toByteArray();
    }

    private static void putU32(byte[] buffer, int offset, int value) {
        buffer[offset] = (byte) (value & 0xFF);
        buffer[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        buffer[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        buffer[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static int u32(byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF)
                | ((buffer[offset + 1] & 0xFF) << 8)
                | ((buffer[offset + 2] & 0xFF) << 16)
                | ((buffer[offset + 3] & 0xFF) << 24);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
