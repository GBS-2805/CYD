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
    /**
     * Whether IO0 is left to the board's BOOT button while the app pulses EN
     * itself; see setManualReset().
     */
    private boolean manualReset;
    /** DTR drives EN and RTS drives IO0 instead of the other way round. */
    private boolean swapControl;
    /** True once a plain reset produced readable boot text, not just noise. */
    private boolean bannerSeen;
    /**
     * The ROM's boot-mode line when a banner showed a flash boot, e.g.
     * "boot:0x13 (SPI_FAST_FLASH_BOOT)", or "" until one is seen. A flash boot after
     * a pulse is proof that EN worked and IO0 was high as the chip left reset, which
     * no sync packet can change, so the verdict is kept for the closing message.
     */
    private String flashBootMode = "";
    /** Reset rounds that ran, and how many of those read any byte at all. */
    private int roundsSeen;
    private int roundsWithBytes;
    /** How long a hand gets to reach BOOT during a sweep's manual phases. */
    private int bootHoldLeadMs = 2500;

    EspRom(UsbSerialCh340 port) {
        this.port = port;
    }

    /** Optional: without a trace the handshake behaves exactly as before. */
    void setTrace(Trace trace) {
        this.trace = trace;
    }

    /**
     * Never drive IO0 low, and pulse EN from here instead, once per round. For a
     * board whose IO0 leg is not driven by the adapter, BOOT has to be held by hand
     * while something resets the chip; pulsing EN is that something, and driving
     * IO0 low is what would fight the button.
     */
    void setManualReset(boolean manual) {
        manualReset = manual;
    }

    /**
     * Drive EN and IO0 on the opposite lines. A CH340 adapter whose DTR/RTS pair
     * is crosswired looks exactly like a dead link: the sequence releases reset
     * with the chip's IO0 still high, so the ROM never runs and every rate stays
     * silent while the bytes leave the phone. The polarity rounds in
     * detectPolarity() cannot fix that, because inverting both lines swaps
     * nothing — it only turns each one into its opposite.
     */
    void setSwappedLines(boolean swapped) {
        swapControl = swapped;
    }

    /**
     * The whole probe in one press: the four wirings a silent board can hide behind.
     *
     * Each phase is exactly the one-shot handshake this class already ran — normal
     * or swapped control lines, each with the reset driven from here or with EN
     * pulsed while BOOT is held by hand — because {@link #connect()} reads
     * swapControl and manualReset rather than being told which one to use. Trying a
     * crosswired adapter used to mean flipping a toggle, pressing PROBE, waiting out
     * a silent sweep and flipping it back; the sweep does that four times and stops
     * at the first phase that syncs.
     *
     * A bridge that refuses to take bytes is fatal to every phase, so WriteFailure
     * is rethrown instead of being retried three more times.
     */
    Sweep connectAll() throws IOException {
        IOException last = null;
        int phase = 0;
        log("sweep: 4 wirings in one press, stopping at the first that answers."
                + " Hold BOOT down from the moment this log reaches wiring 3.");
        for (boolean swap : new boolean[]{false, true}) {
            for (boolean manual : new boolean[]{false, true}) {
                swapControl = swap;
                manualReset = manual;
                ++phase;
                // Per phase, so one wiring's rounds cannot be counted as another's.
                roundsSeen = 0;
                roundsWithBytes = 0;
                bannerSeen = false;
                flashBootMode = "";
                connected = false;
                log("-- wiring " + phase + "/4: " + wiringName(swap, manual) + " --");
                if (manual) {
                    log("   hold BOOT down now: this phase pulses EN with IO0 held high,"
                            + " so the strap is the button's");
                    sleep(bootHoldLeadMs);
                }
                try {
                    return new Sweep(connect(), swap, manual, phase);
                } catch (WriteFailure e) {
                    // No wiring makes a bridge accept bytes it is refusing.
                    throw e;
                } catch (IOException e) {
                    last = e;
                    log("   wiring " + phase + " gave no answer");
                }
            }
        }
        throw new IOException("no answer from the bootloader on any of the 4 wirings"
                + " (normal and swapped lines, app reset and BOOT held by hand); last: "
                + (last == null ? "no attempt ran" : last.getMessage()));
    }

    private static String wiringName(boolean swap, boolean manual) {
        return (swap ? "lines swapped" : "lines normal")
                + (manual ? ", EN pulsed with BOOT held" : ", reset driven by the app");
    }

    /** How long the manual phases wait for a finger on BOOT; the tests zero it. */
    void setBootHoldLeadIn(int millis) {
        bootHoldLeadMs = millis;
    }

    /** Which of the four wirings answered, so the tab can persist it. */
    static final class Sweep {
        final Detected detected;
        final boolean swapped;
        final boolean manual;
        /** 1 means the first wiring worked; 4 means only the last one did. */
        final int phases;

        Sweep(Detected detected, boolean swapped, boolean manual, int phases) {
            this.detected = detected;
            this.swapped = swapped;
            this.manual = manual;
            this.phases = phases;
        }
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
        if (swapControl) {
            log("control lines swapped: DTR drives EN, RTS drives IO0");
        }
        if (manualReset) {
            log("manual mode: IO0 is held high and EN is pulsed once per round. Hold"
                    + " BOOT down while this sweeps, then let go of BOOT.");
        }
        // Measured in both modes. The boot text is what says which control polarity
        // resets this board, and manual mode needs that answer as much as the other
        // three: it used to inherit whatever the phase before it happened to leave.
        detectPolarity();
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

            // Manual mode runs one round per rate: each round pulses EN once, so five
            // rates give five chances to catch the chip with BOOT held down.
            final int rounds = manualReset ? 1 : (first ? CONNECT_ROUNDS : ALT_CONNECT_ROUNDS);
            final int attempts = first ? SYNC_ATTEMPTS : ALT_SYNC_ATTEMPTS;
            log(rate + " baud: " + rounds + " rounds of " + attempts + " syncs");
            for (int round = 0; round < rounds && !synced; ++round) {
                // Alternate the control polarity round by round. An inverted
                // EN/IO0 pair holds the chip in reset for the whole handshake,
                // which reads exactly like a dead link: no bytes, every rate.
                // Per round, not per rate: bytes round 1 read must not be counted
                // again when round 2 comes up empty.
                final long roundStartIn = bytesIn;
                invertControl = base ^ ((round & 1) == 1);
                if (manualReset) {
                    resetOnly();
                    final int afterPulse = listenWindow(300);
                    log("  round " + (round + 1) + ": EN pulsed, IO0 held high"
                            + (invertControl ? ", inverted lines" : "")
                            + (afterPulse == 0
                            ? " — the pulse produced no bytes: EN did not reset the chip"
                            : " — " + afterPulse + " B of boot text after the pulse"
                                    + flashNote()));
                } else {
                    enterBootloader();
                    // Read the pulse's own output before any sync packet goes out, so
                    // the log says whether the chip printed its banner at all rather
                    // than inferring it from a total.
                    final int afterPulse = listenWindow(250);
                    log("  round " + (round + 1) + ": reset into download mode"
                            + (invertControl ? ", inverted lines" : "")
                            + (afterPulse == 0
                            ? " — the pulse produced no bytes at all"
                            : " — " + afterPulse + " B of boot text after the pulse"
                                    + flashNote()));
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
                    ++roundsSeen;
                    if (bytesIn > roundStartIn) ++roundsWithBytes;
                    // A reset is never silent: the ROM prints its banner on the way up,
                    // and download mode's banner ends with "waiting for download". A
                    // round with nothing in at all therefore means the chip did not
                    // restart, which is a different fault from a refused packet.
                    log("  round " + (round + 1) + ": " + bytesIn + " B in / " + bytesOut
                            + " B out, " + previewHex() + " (" + e.getMessage() + ")"
                            + (bytesIn == roundStartIn
                            ? " — no ROM banner, so the chip never restarted" : ""));
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
            final String tail;
            if (manualReset) {
                tail = ", with IO0 held high and EN pulsed once per round. If the rounds"
                        + " read no bytes at all, EN is not resetting the chip at all: hold"
                        + " BOOT down and tap the board's own reset while the sweep"
                        + " listens, then let go of BOOT.";
            } else if (!bannerSeen) {
                tail = ", in either reset polarity. Not one readable byte arrived after a"
                        + " plain reset, at 115200 or 74880, in either polarity. That"
                        + " points at the cable, the adapter's receive line or a board"
                        + " held in reset.";
            } else if (!flashBootMode.isEmpty()) {
                tail = ", in either reset polarity. The chip does restart, and the banner"
                        + " it prints says " + flashBootMode + ": that is the firmware"
                        + " booting, not download mode, so EN, the cable and the receive"
                        + " path all work and IO0 stayed high at the moment of reset. No"
                        + " packet or rate can undo that — hold BOOT by hand (MANUAL"
                        + " BOOT) or look at where the adapter's IO0 leg stops reaching"
                        + " the board.";
            } else if (roundsWithBytes == 0) {
                tail = ", in either reset polarity. Boot text did arrive once, so the"
                        + " cable and the receive path work, but none of the "
                        + roundsSeen + " reset rounds produced a ROM banner. A reset"
                        + " always prints one, and download mode's ends with \"waiting"
                        + " for download\", so the chip never restarted in those rounds:"
                        + " the reset line is the suspect, not the sync packet.";
            } else {
                tail = ", in either reset polarity. Boot text did arrive and "
                        + roundsWithBytes + " of " + roundsSeen + " reset rounds read"
                        + " bytes without answering a sync, so the chip restarted and"
                        + " ignored the packets: the packet or the rate is the open"
                        + " question.";
            }
            final String message = "no answer from the bootloader at any of "
                    + BAUDS.length + " baud rates (" + rates + ")" + tail;
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
        // DTR is assumed to drive IO0 and RTS EN; swapControl covers an adapter
        // wired the other way round.
        final boolean dtrLow = swapControl ? enLow : io0Low;
        final boolean rtsLow = swapControl ? io0Low : enLow;
        port.setDtrRts(invertControl ? !dtrLow : dtrLow, invertControl ? !rtsLow : rtsLow);
    }

    /**
     * One line at a time, in the same mapping lines() uses. esptool changes DTR
     * and RTS with separate calls, and that difference turned out to matter: the
     * only pulse this board ever answered was one that moved RTS alone.
     */
    private void io0(boolean low) throws IOException {
        final boolean value = invertControl ? !low : low;
        if (swapControl) port.setRts(value);
        else port.setDtr(value);
    }

    private void en(boolean low) throws IOException {
        final boolean value = invertControl ? !low : low;
        if (swapControl) port.setDtr(value);
        else port.setRts(value);
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
     * Manual-BOOT entry: pulse EN and never drive IO0 low, so the strap is whatever
     * the board's BOOT button holds.
     *
     * Both lines are written together, at levels that leave IO0 high, because one
     * line on its own is not a reset on a board with the usual pair of reset
     * transistors: EN's state there is a function of the pair, not of DTR alone. The
     * 31 October run showed exactly that — wiring 3 pulsed both lines and printed a
     * banner on every round, while wiring 4 moved DTR alone for the whole phase and
     * read 0 B at all five rates, as if nothing had been pressed.
     */
    private void resetOnly() throws IOException {
        port.flushInput();
        lines(false, true);   // IO0 high, EN low: reset, with both lines defined
        sleep(120);
        lines(false, false);  // EN released: BOOT decides flash or download mode
        sleep(120);
    }

    private void enterBootloader() throws IOException {
        port.flushInput();
        // esptool's hard_reset() drives the two lines with separate calls, in this
        // order, 100 ms and 50 ms apart. Driving both bits in one control transfer
        // is not the same thing to the bridge, and the 2026-10-31 log showed the
        // difference: a pulse that moved RTS alone printed a 396 B ROM banner,
        // while these rounds — which moved DTR and RTS together — read nothing at
        // all across eleven attempts.
        io0(false);           // IO0 high
        en(true);             // EN low: chip held in reset
        sleep(100);
        io0(true);            // IO0 low: the strap the ROM samples
        en(false);            // EN high: chip leaves reset into download mode
        sleep(50);
        io0(false);           // IO0 high again, strap already sampled
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
        // Listen once without touching the lines first. The board prints its banner
        // when it powers up, and those bytes can still be queued when the port opens;
        // crediting them to the pulse that follows would call a dead reset circuit
        // healthy and send the next test in the wrong direction.
        final int queued = listenQuiet(400);
        if (queued > 0) {
            log("  " + queued + " B arrived with no line change: the board was still"
                    + " booting from the plug-in, so those bytes say nothing about the"
                    + " reset. Draining them before the sweep.");
        }
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
                    log("  boot text arrived after a reset, so the cable, the adapter"
                            + " and the reset wiring work; keeping "
                            + (inverted ? "inverted" : "normal") + " lines");
                    final String mode = bootMode();
                    if (isFlashBoot(mode)) {
                        flashBootMode = mode;
                        log("  that banner is a flash boot (" + mode + "), not download"
                                + " mode: EN resets the chip and IO0 was high while it"
                                + " did, so the strap never took");
                    }
                    return;
                }
                noiseOnly |= seen > 0;
            }
        }
        invertControl = false;
        log(noiseOnly
                ? "  bytes arrived, none of them text: the board talked at some other"
                        + " rate, so the data path works but the rate is unknown"
                : "  nothing at either rate in either polarity — and no ROM banner from"
                        + " any pulse, which a reset always prints, so the reset lines are"
                        + " the first thing to suspect");
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

    /**
     * The ROM's boot-mode line from the captured banner — "boot:0x13
     * (SPI_FAST_FLASH_BOOT)" — or "" when the window held no banner. That line is
     * the only direct evidence of whether IO0 was low as the chip left reset, so it
     * decides which advice a failed probe gives.
     */
    private String bootMode() {
        final String text = bannerText();
        final int at = text.indexOf("boot:0x");
        if (at < 0) return "";
        final int close = text.indexOf(')', at);
        return text.substring(at, close < 0 ? Math.min(text.length(), at + 16) : close + 1);
    }

    private static boolean isFlashBoot(String mode) {
        return mode.contains("SPI_FAST_FLASH_BOOT");
    }

    /** A round-line clause when its banner shows a flash boot, else "". */
    private String flashNote() {
        final String mode = bootMode();
        if (!isFlashBoot(mode)) return "";
        flashBootMode = mode;
        return " — " + mode + ": flash boot, so IO0 was high at reset";
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

    /**
     * Read for a window without touching DTR/RTS. Anything here came from a boot
     * that had already happened, so it cannot be evidence that a line change
     * worked.
     */
    private int listenQuiet(int windowMs) throws IOException {
        bytesIn = 0;
        preview.reset();
        previewCap = BANNER_BYTES;
        port.flushInput();
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
        final int seen = (int) bytesIn;
        bytesIn = 0;
        return seen;
    }

    /**
     * Read a window and add it to the round's byte count, without flushing and
     * without touching the lines: this is the chip's answer to the pulse that just
     * happened, not a separate test.
     */
    private int listenWindow(int windowMs) throws IOException {
        preview.reset();
        previewCap = BANNER_BYTES;
        int seen = 0;
        final long deadline = System.currentTimeMillis() + windowMs;
        final byte[] buffer = new byte[64];
        while (System.currentTimeMillis() < deadline) {
            final int read = port.read(buffer, 50);
            if (read <= 0) continue;
            seen += read;
            for (int i = 0; i < read && preview.size() < previewCap; ++i) {
                preview.write(buffer[i]);
            }
        }
        previewCap = PREVIEW_BYTES;
        bytesIn += seen;
        return seen;
    }

    private void sync(int attempts) throws IOException {
        final byte[] payload = new byte[36];
        payload[0] = 0x07;
        payload[1] = 0x07;
        payload[2] = 0x12;
        payload[3] = 0x20;
        Arrays.fill(payload, 4, payload.length, (byte) 0x55);

        // esptool sends this packet with its checksum, not zero: 0xEF XOR every
        // payload byte, which is 0xDD here because the 32 0x55s cancel out. The
        // ROM accepts the sync before any handshake, so a zero matched nothing and
        // broke nothing — but the point of this probe is to send what esptool
        // 4.5.1 sends, so one more difference is gone from the comparison.
        final int payloadChecksum = checksum(payload);
        for (int attempt = 0; attempt < attempts; ++attempt) {
            sendCommand(OP_SYNC, payload, payloadChecksum);
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
