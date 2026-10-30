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

    /** How much of a silent/failing line to keep for the log. */
    private static final int PREVIEW_BYTES = 32;

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
    /** Line rate the port is currently programmed to. */
    private int baud = 115200;

    EspRom(UsbSerialCh340 port) {
        this.port = port;
    }

    /** Optional: without a trace the handshake behaves exactly as before. */
    void setTrace(Trace trace) {
        this.trace = trace;
    }

    private void log(String text) {
        if (trace != null) trace.line(text);
    }

    /** The bytes seen at this rate as hex, or "nothing" when the line was quiet. */
    private String previewHex() {
        if (preview.size() == 0) return "nothing";
        final byte[] raw = preview.toByteArray();
        final StringBuilder hex = new StringBuilder(raw.length * 3);
        for (int i = 0; i < raw.length; ++i) {
            if (i > 0) hex.append(' ');
            hex.append(String.format(Locale.US, "%02x", raw[i] & 0xFF));
        }
        if (bytesIn > raw.length) hex.append(" …");
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
     * The first round syncs without a reset, because a board already sitting in
     * download mode answers straight away; later rounds reset first, which is
     * what a board running its firmware needs.
     */
    Detected connect() throws IOException {
        final StringBuilder silent = new StringBuilder();
        boolean synced = false;

        log("sync: " + BAUDS.length + " rates queued, " + CONNECT_ROUNDS
                + " reset rounds x " + SYNC_ATTEMPTS + " sync packets at the first");

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
            preview.reset();

            final int rounds = first ? CONNECT_ROUNDS : ALT_CONNECT_ROUNDS;
            final int attempts = first ? SYNC_ATTEMPTS : ALT_SYNC_ATTEMPTS;
            log(rate + " baud: " + rounds + " rounds of " + attempts + " syncs");
            for (int round = 0; round < rounds && !synced; ++round) {
                if (round > 0) {
                    enterBootloader();
                    sleep(120);
                    log("  round " + (round + 1) + ": reset into download mode");
                }
                try {
                    sync(attempts);
                    synced = true;
                } catch (IOException ignored) {
                    // Try the next reset round, then the next rate.
                    log("  round " + (round + 1) + ": " + bytesIn + " B arrived, "
                            + previewHex());
                }
            }
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
                    + BAUDS.length + " baud rates (" + rates + "; the last one saw "
                    + previewHex() + "). The ROM stays silent until it accepts a"
                    + " sync packet, so silence points at the cable, the reset"
                    + " wiring or the adapter rather than the baud rate";
            log("failed: " + message);
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
     * esptool's classic reset: hold EN low, drop IO0 low, release EN, then
     * release IO0 — the ROM samples the strapping pin as it leaves reset.
     */
    private void enterBootloader() throws IOException {
        port.flushInput();
        port.setDtrRts(false, true);   // IO0 high, EN low (in reset)
        sleep(150);
        port.setDtrRts(true, false);   // IO0 low, EN released
        sleep(120);
        port.setDtrRts(false, false);  // IO0 high again, chip now in download mode
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
        port.setDtrRts(false, false); // IO0 high, EN released
        sleep(60);
        port.setDtrRts(false, true);  // EN low: reset
        sleep(150);
        port.setDtrRts(false, false); // EN released, IO0 high: boot from flash
        sleep(200);
        connected = false;
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
        port.write(slipEncode(packet));
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
            if (preview.size() < PREVIEW_BYTES) preview.write(value);
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
