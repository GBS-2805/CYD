package dev.phonedesk.companion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Host tests for the ROM protocol. Compiled by test.sh against the stub serial
 * port, so this runs on a JVM with no phone, no board, no emulator and no
 * Android SDK.
 *
 * What is covered: SLIP framing round-trips, the sync packet carries esptool's
 * checksum, the handshake reaches download mode and reads the MAC, a reply with
 * the wrong opcode is skipped, a refused write is blamed on the bridge rather
 * than the board, a silent board fails, and flash refuses a session that never
 * synced.
 */
public final class HostTests {

    private static final int OP_SYNC = 0x08;
    private static final int OP_READ_REG = 0x0A;

    /** The ROM's banner: bytes before the reply that readFrame() must skip. */
    private static final byte[] BANNER = ("ets Jul 29 2019 12:21:46\r\n\r\n"
            + "rst:0x1 (POWERON_RESET),boot:0x3 (DOWNLOAD_BOOT(UART0/UART1/SDIO_REI_REO_V2))"
            + " waiting for download\r\n").getBytes(StandardCharsets.UTF_8);

    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        framingRoundTrip();
        syncChecksum();
        handshakeIntoDownloadMode();
        quietSyncUsesNoControlLines();
        pingLoaderSendsOnePacketWithoutReset();
        pingLoaderSaysSilenceProvesLittle();
        decoyReplyIsSkipped();
        refusedWrite();
        silentBoard();
        flashWithoutHandshake();
        sweepStopsAtFirstAnswer();
        sweepTriesEveryWiring();
        flashBootNamesTheStrap();
        downloadBootAsksForASecondHost();

        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("all " + checks + " host checks passed");
            return;
        }
        System.out.println(failures.size() + " of " + checks + " host checks FAILED");
        for (String failure : failures) System.out.println("  - " + failure);
        System.exit(1);
    }

    // ------------------------------------------------------------- framing ---

    private static void framingRoundTrip() throws Exception {
        final byte[] packet = {
                0x00, (byte) 0xC0, 0x08, 0x24, (byte) 0xDB, (byte) 0xDC, (byte) 0xDD,
                0x00, (byte) 0xFF
        };
        final byte[] framed = (byte[]) invoke("slipEncode", packet);

        check("framing opens with an END byte", (framed[0] & 0xFF) == 0xC0);
        check("framing closes with an END byte", (framed[framed.length - 1] & 0xFF) == 0xC0);
        check("framing escapes END as DB DC", contains(framed, new byte[]{(byte) 0xDB, (byte) 0xDC}));
        check("framing escapes ESC as DB DD", contains(framed, new byte[]{(byte) 0xDB, (byte) 0xDD}));

        final byte[] inner = Arrays.copyOfRange(framed, 1, framed.length - 1);
        final byte[] back = (byte[]) invoke("unescape", inner);
        check("framing round-trips byte for byte", Arrays.equals(packet, back));
    }

    private static void syncChecksum() throws Exception {
        check("the sync checksum is 0xDD, as esptool sends it",
                (Integer) invoke("checksum", syncPayload()) == 0xDD);
        check("an empty payload checksums to the 0xEF seed",
                (Integer) invoke("checksum", new byte[0]) == 0xEF);
    }

    // ----------------------------------------------------------- handshake ---

    private static void handshakeIntoDownloadMode() throws Exception {
        final UsbSerialCh340 port = new UsbSerialCh340();
        port.answer(chip(false));
        port.answerOnlyAfterPulse = true;   // keep this test on the reset path
        final EspRom rom = new EspRom(port);
        rom.setManualReset(true);
        final List<String> trace = new ArrayList<>();
        rom.setTrace(trace::add);

        final EspRom.Detected detected = rom.connect();

        check("the handshake names the chip", "ESP32".equals(detected.chip));
        check("the handshake reads the MAC", "24:0a:c4:12:34:56".equals(detected.mac));
        check("the handshake reports the rate it used", detected.baud == 115200);
        check("the log says where it synced", joined(trace).contains("synced at 115200 baud"));

        final int index = indexOf(port, OP_SYNC);
        check("the sync packet was sent", index >= 0);
        if (index >= 0) {
            check("the sync packet carries checksum 0xDD", port.commands.get(index)[1] == 0xDD);
            check("the sync payload is the 36-byte esptool one",
                    Arrays.equals(port.payloads.get(index), syncPayload()));
        }

        final String lines = String.join(" | ", port.control);
        check("a pulse writes both control lines together",
                lines.contains("both dtr="));
        check("no pulse moves one line on its own",
                !lines.contains("dtr ") && !lines.contains("rts "));
    }

    /**
     * A loader that is already awake answers packets with no reset of ours. This is
     * the case no other phase can reach: each of them resets first, which is also
     * why a board with a broken EN or IO0 leg could never show an awake loader.
     */
    private static void quietSyncUsesNoControlLines() throws Exception {
        final UsbSerialCh340 port = new UsbSerialCh340();
        port.answer(chip(false));
        final EspRom rom = new EspRom(port);
        final List<String> trace = new ArrayList<>();
        rom.setTrace(trace::add);

        final EspRom.Detected detected = rom.connect();

        check("an awake loader is found without a reset", "ESP32".equals(detected.chip));
        check("the quiet phase reads the MAC too", "24:0a:c4:12:34:56".equals(detected.mac));
        check("the probe finishes having written no control line", port.control.isEmpty());
        check("the log says the loader needed no reset",
                joined(trace).contains("the loader was already"));
        check("the log says the lines were untouched",
                joined(trace).contains("control lines untouched"));
    }

    /** SYNC ONLY: one packet, no control line, and the answer is the whole result. */
    private static void pingLoaderSendsOnePacketWithoutReset() throws Exception {
        final UsbSerialCh340 port = new UsbSerialCh340();
        port.answer(chip(false));
        final EspRom rom = new EspRom(port);
        final List<String> trace = new ArrayList<>();
        rom.setTrace(trace::add);

        check("a bare sync finds an awake loader", rom.pingLoader());
        check("the ping wrote no control line", port.control.isEmpty());
        check("the ping sent exactly one packet",
                port.commands.size() == 1 && port.commands.get(0)[0] == OP_SYNC);
        check("the ping counts as no reset at all",
                joined(trace).contains("control lines untouched"));
    }

    /**
     * Silence to a bare packet must not read as a fault: firmware does not answer
     * sync either, so the button only means something when the chip is known to be
     * in its loader.
     */
    private static void pingLoaderSaysSilenceProvesLittle() throws Exception {
        final EspRom rom = new EspRom(new UsbSerialCh340());
        final List<String> trace = new ArrayList<>();
        rom.setTrace(trace::add);

        check("a silent chip is not called broken by a bare sync", !rom.pingLoader());
        check("the log says silence alone proves nothing",
                joined(trace).contains("proves nothing"));
    }

    private static void decoyReplyIsSkipped() throws Exception {
        final UsbSerialCh340 port = new UsbSerialCh340();
        port.answer(chip(true));
        port.answerOnlyAfterPulse = true;
        final EspRom rom = new EspRom(port);
        rom.setManualReset(true);

        check("a reply with another opcode is skipped, not accepted",
                "ESP32".equals(rom.connect().chip));
    }

    private static void refusedWrite() throws Exception {
        final UsbSerialCh340 port = new UsbSerialCh340();
        port.failWrites = true;
        final EspRom rom = new EspRom(port);
        rom.setManualReset(true);
        try {
            rom.connect();
            check("a refused write fails the probe", false);
        } catch (IOException e) {
            check("a refused write is blamed on the bridge, not the board",
                    e.getMessage().contains("bridge would not take"));
        }
    }

    private static void silentBoard() throws Exception {
        final UsbSerialCh340 port = new UsbSerialCh340();
        // One round at 115200 is 12 sync attempts; the other four rates are
        // refused instantly, which keeps this test near three seconds.
        port.refuseRates = true;
        final EspRom rom = new EspRom(port);
        rom.setManualReset(true);
        try {
            rom.connect();
            check("a board that never answers fails the probe", false);
        } catch (IOException e) {
            check("a board that never answers fails the probe",
                    e.getMessage().contains("no answer from the bootloader"));
        }
    }

    /** The first wiring is the one most adapters need, so it must win immediately. */
    private static void sweepStopsAtFirstAnswer() throws Exception {
        final UsbSerialCh340 port = new UsbSerialCh340();
        port.answer(chip(false));
        final EspRom rom = new EspRom(port);
        rom.setBootHoldLeadIn(0);
        final List<String> trace = new ArrayList<>();
        rom.setTrace(trace::add);

        final EspRom.Sweep sweep = rom.connectAll();

        check("a sweep ends on the wiring that answers", "ESP32".equals(sweep.detected.chip));
        check("the first wiring is the one that answered", !sweep.swapped && !sweep.manual);
        check("the sweep stops at wiring 1", sweep.phases == 1);
        check("later wirings are never tried", !joined(trace).contains("wiring 2/4"));
    }

    /** A board that never answers must say all four were tried, not one. */
    private static void sweepTriesEveryWiring() throws Exception {
        final UsbSerialCh340 port = new UsbSerialCh340();
        port.refuseRates = true;
        final EspRom rom = new EspRom(port);
        rom.setBootHoldLeadIn(0);
        final List<String> trace = new ArrayList<>();
        rom.setTrace(trace::add);
        try {
            rom.connectAll();
            check("a silent board fails every wiring", false);
        } catch (IOException e) {
            check("a silent board fails every wiring", e.getMessage().contains("4 wirings"));
            check("the sweep reached the last wiring", joined(trace).contains("wiring 4/4"));
        }
    }

    /**
     * The 31 October banner: the chip restarts, but into its firmware. That means
     * IO0 was high at reset, so the failure message has to name the strap instead of
     * blaming the packet or the rate the way it used to.
     */
    private static void flashBootNamesTheStrap() throws Exception {
        final UsbSerialCh340 port = new UsbSerialCh340();
        port.refuseRates = true;   // the banner is all this test needs
        port.printOnPulse = "ets Jul 29 2019 12:21:46\r\n\r\n"
                + "rst:0x1 (POWERON_RESET),boot:0x13 (SPI_FAST_FLASH_BOOT)\r\n";
        final EspRom rom = new EspRom(port);
        final List<String> trace = new ArrayList<>();
        rom.setTrace(trace::add);
        try {
            rom.connect();
            check("a flash-boot banner still fails the probe", false);
        } catch (IOException e) {
            check("a flash-boot banner is called a flash boot",
                    joined(trace).contains("flash boot"));
            check("a flash boot blames the IO0 strap, not the packet",
                    e.getMessage().contains("IO0 stayed high"));
            check("a flash boot points at BOOT by hand",
                    e.getMessage().contains("MANUAL BOOT"));
        }
    }

    /**
     * The 13:49 banner: \"boot:0x3 (DOWNLOAD_BOOT…) waiting for download\" and then
     * not one byte back from 552 B of sync packets. The chip was in its loader, so
     * the old advice — hold BOOT, tap reset — would send a hand to the wrong place.
     * With the CYD's bridge on the board there is no TX leg to check either, so the
     * only test that separates the app from the bridge is another host on the cable.
     */
    private static void downloadBootAsksForASecondHost() throws Exception {
        final UsbSerialCh340 port = new UsbSerialCh340();
        port.refuseRates = true;   // one round at 115200, the rest refused instantly
        port.printOnPulse = "ets Jul 29 2019 12:21:46\r\n\r\n"
                + "rst:0x1 (POWERON_RESET),boot:0x3 (DOWNLOAD_BOOT(UART0/UART1/SDIO_REI_REO_V2))"
                + " waiting for download\r\n";
        final EspRom rom = new EspRom(port);
        final List<String> trace = new ArrayList<>();
        rom.setTrace(trace::add);
        try {
            rom.connect();
            check("a download-mode banner still fails without a sync reply", false);
        } catch (IOException e) {
            check("a download-mode banner is called download mode",
                    joined(trace).contains("download mode"));
            check("a round line says the loader is waiting for a packet",
                    joined(trace).contains("in the ROM loader"));
            check("the failure asks for a second host, not a rate",
                    e.getMessage().contains("second host"));
            check("the failure names esptool on the same cable",
                    e.getMessage().contains("esptool.py"));
            check("the failure says the bridge is on the board",
                    e.getMessage().contains("no wire to inspect"));
            check("download mode stops the BOOT-holding advice",
                    !e.getMessage().contains("hold BOOT down and tap"));
        }
    }

    private static void flashWithoutHandshake() {
        try {
            new EspRom(new UsbSerialCh340()).flash(0, new byte[]{1, 2, 3, 4}, null);
            check("flash refuses a session that never synced", false);
        } catch (IOException e) {
            check("flash refuses a session that never synced",
                    e.getMessage().contains("not connected"));
        }
    }

    // ------------------------------------------------------------ fixtures ---

    /**
     * A chip that answers sync and the two register reads. With {@code decoy} it
     * first sends a reply carrying the flash-write opcode, which the handshake
     * has to ignore.
     */
    private static UsbSerialCh340.Responder chip(boolean decoy) {
        return (port, op, checksum, payload) -> {
            if (op == OP_SYNC) {
                if (decoy) port.inject(reply(0x03, 0, new byte[]{0x00, 0x00}));
                port.inject(BANNER);
                port.inject(reply(OP_SYNC, 0, new byte[]{0x00, 0x00}));
            } else if (op == OP_READ_REG && payload.length >= 4) {
                final int address = u32(payload, 0);
                final int value;
                if (address == 0x40001000) value = 0x00F01D83;          // ESP32 magic
                else if (address == 0x3FF5A004) value = 0xC4123456;     // MAC word 1
                else if (address == 0x3FF5A008) value = 0x0000240A;     // MAC word 2
                else value = 0;
                port.inject(reply(OP_READ_REG, value, new byte[]{0x00, 0x00}));
            }
        };
    }

    /** The 36 bytes esptool 4.5.1 puts in a sync packet. */
    private static byte[] syncPayload() {
        final byte[] payload = new byte[36];
        payload[0] = 0x07;
        payload[1] = 0x07;
        payload[2] = 0x12;
        payload[3] = 0x20;
        Arrays.fill(payload, 4, payload.length, (byte) 0x55);
        return payload;
    }

    /** One reply frame: direction 0x01, opcode, size, value, data — SLIP-framed. */
    private static byte[] reply(int op, int value, byte[] data) {
        final ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x01);
        frame.write(op);
        frame.write(data.length & 0xFF);
        frame.write((data.length >> 8) & 0xFF);
        for (int i = 0; i < 4; ++i) frame.write((value >>> (8 * i)) & 0xFF);
        frame.write(data, 0, data.length);
        return slip(frame.toByteArray());
    }

    private static byte[] slip(byte[] packet) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xC0);
        for (byte value : packet) {
            if ((value & 0xFF) == 0xC0) {
                out.write(0xDB);
                out.write(0xDC);
            } else if ((value & 0xFF) == 0xDB) {
                out.write(0xDB);
                out.write(0xDD);
            } else {
                out.write(value);
            }
        }
        out.write(0xC0);
        return out.toByteArray();
    }

    // ------------------------------------------------------------- helpers ---

    private static Object invoke(String name, Object argument) throws Exception {
        final Class<?> type = argument instanceof byte[] ? byte[].class : argument.getClass();
        final Method method = EspRom.class.getDeclaredMethod(name, type);
        method.setAccessible(true);
        return method.invoke(null, argument);
    }

    private static int indexOf(UsbSerialCh340 port, int op) {
        for (int i = 0; i < port.commands.size(); ++i) {
            if (port.commands.get(i)[0] == op) return i;
        }
        return -1;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        for (int i = 0; i + needle.length <= haystack.length; ++i) {
            boolean match = true;
            for (int j = 0; j < needle.length; ++j) {
                if (haystack[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return true;
        }
        return false;
    }

    private static int u32(byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF)
                | ((buffer[offset + 1] & 0xFF) << 8)
                | ((buffer[offset + 2] & 0xFF) << 16)
                | ((buffer[offset + 3] & 0xFF) << 24);
    }

    private static String joined(List<String> lines) {
        return String.join("\n", lines);
    }

    private static void check(String name, boolean ok) {
        ++checks;
        if (ok) {
            System.out.println("  ok    " + name);
        } else {
            System.out.println("  FAIL  " + name);
            failures.add(name);
        }
    }
}
