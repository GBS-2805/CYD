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
 * than the board, a silent board fails, flash refuses a session that never synced,
 * a hand on BOOT and RESET is tried before any wiring is, and the wiring the serial
 * card's buttons name is the first one the fallback sweep tries. The palette is in
 * there too: every ink the app draws is measured against the surface it sits on,
 * because an alpha is the one design decision that can go wrong one step at a time.
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

    /**
     * Time the tests drive. EspRom's windows are real seconds wide, and every
     * wiring is tried: on this clock a sleep advances the deadline instead of
     * waiting for it, so "a silent board fails every wiring" still runs every
     * wiring and every window, in milliseconds.
     */
    private static final class VirtualClock implements EspRom.Clock {
        private long ms = 1_000_000L;

        @Override
        public long now() {
            return ms;
        }

        @Override
        public void sleep(long millis) {
            ms += Math.max(1, millis);
        }
    }

    private static final VirtualClock CLOCK = new VirtualClock();

    public static void main(String[] args) throws Exception {
        sketchStamp();
        deckVersion();
        EspRom.useClock(CLOCK);
        UsbSerialCh340.clock = CLOCK;
        framingRoundTrip();
        bondAdvice();
        noticeText();
        contrast();
        labels();
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
        handResetComesBeforeAnyWiring();
        sweepTriesEveryWiring();
        preferredWiringGoesFirst();
        rememberedBaudAnswersStep1();
        rememberedBaudLeadsTheSweep();
        flashBootNamesTheStrap();
        downloadBootAsksForASecondHost();
        spotifyCallbackLink();
        spotifyCallbackUrl();
        deckIdentity();
        deckAddresses();
        nowPacket();
        linkCommand();
        deckTheme();

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

        // The bytes as they leave the port, not as this app's own decoder reads
        // them: the stub's decoded lists assume the header layout sendCommand
        // writes, so only a literal pin can catch that layout being wrong. These
        // are esptool's sync frame — 0xC0, direction 0x00, opcode 0x08, size 36,
        // checksum 0xDD, the 0x20120707 request and 32 0x55s, 0xC0 — and 46 bytes
        // is what made "552 B out" twelve attempts on the phone's log.
        final byte[] expected = new byte[46];
        int at = 0;
        expected[at++] = (byte) 0xC0;
        expected[at++] = 0x00;
        expected[at++] = (byte) OP_SYNC;
        expected[at++] = 36;
        expected[at++] = 0x00;
        expected[at++] = (byte) 0xDD;
        expected[at++] = 0x00;
        expected[at++] = 0x00;
        expected[at++] = 0x00;
        expected[at++] = 0x07;
        expected[at++] = 0x07;
        expected[at++] = 0x12;
        expected[at++] = 0x20;
        for (int i = 0; i < 32; ++i) expected[at++] = 0x55;
        expected[at] = (byte) 0xC0;
        check("the sync frame on the wire is esptool's 46 bytes",
                Arrays.equals(expected, port.wire.toByteArray()));
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

    /**
     * An awake loader is the cheapest entry there is, so it must be taken before a
     * line moves: the probe may not pulse a board that was already listening.
     */
    private static void sweepStopsAtFirstAnswer() throws Exception {
        final UsbSerialCh340 port = new UsbSerialCh340();
        port.answer(chip(false));
        final EspRom rom = new EspRom(port);
        rom.setBootHoldLeadIn(0);
        final List<String> trace = new ArrayList<>();
        rom.setTrace(trace::add);

        final EspRom.Sweep sweep = rom.connectAll();

        check("the probe finds the chip", "ESP32".equals(sweep.detected.chip));
        check("an awake loader answers before any wiring", sweep.phases == 0);
        check("no wiring is claimed when none was needed",
                !sweep.swapped && !sweep.manual);
        check("the log says the loader was already in its loader",
                joined(trace).contains("already sitting in its loader"));
        check("no wiring is tried at all", !joined(trace).contains("wiring 1/4"));
        check("no control line moves for an awake loader", port.control.isEmpty());
    }

    /**
     * Step 2, which is what the board's buttons are for: no control line ever moves,
     * and a loader that starts answering mid-window is exactly a chip someone just put
     * into download mode by hand. It has to be found before the four wirings, because
     * on the board this was written for the wirings all depend on an EN leg that
     * reaches nothing.
     */
    private static void handResetComesBeforeAnyWiring() throws Exception {
        final UsbSerialCh340 port = new UsbSerialCh340();
        final UsbSerialCh340.Responder answers = chip(false);
        final int[] packets = {0};
        port.answer((p, op, checksum, payload) -> {
            // Silent through step 1's three packets, then awake: the hand that came
            // down on BOOT and RESET between the two steps.
            if (op == OP_SYNC && ++packets[0] <= 3) return;
            answers.onCommand(p, op, checksum, payload);
        });
        final EspRom rom = new EspRom(port);
        final List<String> trace = new ArrayList<>();
        rom.setTrace(trace::add);

        final EspRom.Sweep sweep = rom.connectAll();

        check("BOOT + RESET enters the loader with no wiring", sweep.phases == 0);
        check("the log asks for BOOT held and RESET tapped",
                joined(trace).contains("hold the board's BOOT button down"));
        check("step 2 is credited, not a wiring", joined(trace).contains("step 2 answered"));
        check("step 2 is credited before any wiring", !joined(trace).contains("wiring 1/4"));
        check("the hand path moves no control line", port.control.isEmpty());
    }

    /**
     * The two buttons on the serial card are the sweep's starting point: whichever
     * wiring they name is phase 1, so a board found by hand is not walked past three
     * wirings that already failed. All four are still tried.
     */
    private static void preferredWiringGoesFirst() throws Exception {
        final UsbSerialCh340 port = new UsbSerialCh340();
        port.refuseRates = true;   // only the order matters here
        final EspRom rom = new EspRom(port);
        rom.setBootHoldLeadIn(0);
        rom.setHandResetWait(0);
        rom.setSwappedLines(true);
        rom.setManualReset(true);
        final List<String> trace = new ArrayList<>();
        rom.setTrace(trace::add);
        try {
            rom.connectAll();
            check("a silent board fails even with a wiring selected", false);
        } catch (IOException e) {
            final String log = joined(trace);
            check("the log names the wiring the buttons select",
                    log.contains("the buttons select lines swapped, EN pulsed with BOOT held"));
            check("the selected wiring is tried first",
                    log.contains("wiring 1/4: lines swapped, EN pulsed with BOOT held"));
            check("the other three still run", log.contains("wiring 4/4"));
            check("every wiring is still counted", e.getMessage().contains("4 wirings"));
        }
    }

    /**
     * A bridge whose crystal is off only answers at one rate, so the rate that worked
     * last time is asked first — in step 1, before any reset. Here nothing but 460800
     * reaches the chip, which is exactly the board this fixes.
     */
    private static void rememberedBaudAnswersStep1() throws Exception {
        final UsbSerialCh340 port = new UsbSerialCh340();
        final UsbSerialCh340.Responder answers = chip(false);
        port.answer((p, op, checksum, payload) -> {
            // Bytes at any other rate are noise to this bridge.
            if (p.baud != 460800) return;
            answers.onCommand(p, op, checksum, payload);
        });
        final EspRom rom = new EspRom(port);
        rom.setPreferredBaud(460800);
        rom.setHandResetWait(0);   // if step 1 misses, fail fast instead of waiting
        final List<String> trace = new ArrayList<>();
        rom.setTrace(trace::add);

        final EspRom.Sweep sweep = rom.connectAll();

        check("a remembered rate answers step 1", sweep.phases == 0);
        check("the rate that answered is the remembered one", sweep.detected.baud == 460800);
        check("step 1 asks again at the remembered rate",
                joined(trace).contains("quiet sync again at the remembered 460800 baud"));
        check("the remembered rate takes no reset", port.control.isEmpty());
        check("the probe never reaches the wirings", !joined(trace).contains("wiring 1/4"));
    }

    /**
     * When step 1 cannot answer, the remembered rate is the sweep's first rate, not its
     * second: re-learning that the default is silent costs a wiring's silent rounds.
     */
    private static void rememberedBaudLeadsTheSweep() throws Exception {
        final UsbSerialCh340 port = new UsbSerialCh340();
        final UsbSerialCh340.Responder answers = chip(false);
        port.answerOnlyAfterPulse = true;   // step 1 must fail, or no rate is tried
        port.answer((p, op, checksum, payload) -> {
            if (p.baud != 460800) return;
            answers.onCommand(p, op, checksum, payload);
        });
        final EspRom rom = new EspRom(port);
        rom.setPreferredBaud(460800);
        rom.setHandResetWait(0);
        rom.setBootHoldLeadIn(0);
        final List<String> trace = new ArrayList<>();
        rom.setTrace(trace::add);

        final EspRom.Sweep sweep = rom.connectAll();

        check("the remembered rate answers the first wiring", sweep.phases == 1);
        check("the remembered rate leads the sweep",
                joined(trace).contains("460800 baud: 3 rounds of 12 syncs"));
        check("the sweep never asks for the default rate first",
                !joined(trace).contains("115200 baud: 3 rounds"));
        check("the log says the remembered rate comes first", joined(trace).contains(
                "the last handshake answered at 460800 baud, so that rate comes first"));
        check("the forgotten rates still follow", port.rates.contains(74880)
                || joined(trace).contains("74880 baud"));
    }

    /** A board that never answers must say all four were tried, not one. */
    private static void sweepTriesEveryWiring() throws Exception {
        final UsbSerialCh340 port = new UsbSerialCh340();
        port.refuseRates = true;
        final EspRom rom = new EspRom(port);
        rom.setBootHoldLeadIn(0);
        rom.setHandResetWait(0);   // no hand to wait for; go straight to the wirings
        final List<String> trace = new ArrayList<>();
        rom.setTrace(trace::add);
        try {
            rom.connectAll();
            check("a silent board fails every wiring", false);
        } catch (IOException e) {
            check("a silent board fails every wiring", e.getMessage().contains("4 wirings"));
            check("the sweep reached the last wiring", joined(trace).contains("wiring 4/4"));
            check("the failure counts BOOT + RESET before the wirings",
                    e.getMessage().contains("after BOOT + RESET by hand"));
            check("the log asked for BOOT + RESET before sweeping",
                    joined(trace).contains("hold the board's BOOT button down"));
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

    // --------------------------------------------------------- spotify link ---

    /**
     * The deck's sign-in comes back as a custom-scheme link. What matters is that
     * the code survives percent-encoding, a refusal carries no code, and the
     * address the deck sends back in state is only used when it is a bare IPv4
     * literal — anything else would aim the phone's browser somewhere else.
     */
    private static void spotifyCallbackLink() {
        final String link = "phonedesk://spotify-callback?code=AQDx%2By%2Fz&state=192.168.1.50";
        check("the sign-in link carries its code, decoded",
                "AQDx+y/z".equals(SpotifyLink.code(link)));
        check("the sign-in link carries the deck's address",
                "192.168.1.50".equals(SpotifyLink.deck(link)));
        check("a link with no query carries no code",
                SpotifyLink.code("phonedesk://spotify-callback").isEmpty());
        check("a refused sign-in carries no code",
                SpotifyLink.code("phonedesk://spotify-callback?error=access_denied")
                        .isEmpty());
        check("a refused sign-in names its reason",
                "access_denied".equals(SpotifyLink.query(
                        "phonedesk://spotify-callback?error=access_denied", "error")));
        check("a spelled-out address is refused",
                SpotifyLink.deck("phonedesk://spotify-callback?state=evil.example").isEmpty());
        check("an address with a scheme is refused",
                SpotifyLink.deck("phonedesk://spotify-callback?state=http%3A%2F%2F192.168.1.5")
                        .isEmpty());
        check("an out-of-range octet is refused",
                SpotifyLink.deck("phonedesk://spotify-callback?state=192.168.1.999").isEmpty());
        check("an address with a port is kept",
                "192.168.4.1:8080".equals(SpotifyLink.deck(
                        "phonedesk://spotify-callback?state=192.168.4.1%3A8080")));
    }

    /**
     * The one request the app makes. It has to survive a code holding characters
     * that mean something in a query string — which is why nothing is escaped as
     * '+' — and it has to ask for the one-line answer, or the app would be reading
     * a page to work out whether the sign-in worked.
     */
    private static void spotifyCallbackUrl() {
        final String url = SpotifyLink.codeUrl("192.168.1.50", "AQDx+y/z=");
        check("the hand-off asks the deck's own address",
                url.startsWith("http://192.168.1.50/code?redir=app&code="));
        check("the hand-off marks the app's redirect", url.contains("redir=app"));
        check("the hand-off asks for a one-line answer", url.endsWith("&json=1"));
        check("a plus in the code is escaped, never a space",
                url.contains("%2B") && !url.contains("+"));
        check("a slash and an equals in the code are escaped",
                url.contains("%2F") && url.contains("%3D"));
        check("the hand-off keeps the deck's port",
                SpotifyLink.codeUrl("192.168.4.1:8080", "c")
                        .startsWith("http://192.168.4.1:8080/code?"));
    }

    /**
     * The identity document is what tells two decks apart, so the parser is held to
     * the line the sketch prints: the id carries the tag, the booleans read as
     * booleans, and a document that names no deck is refused rather than guessed at.
     */
    private static void deckIdentity() {
        final String json = "{\"id\":\"spotify_deck_cyd24R-A1B2C3\","
                + "\"widget\":\"spotify_deck_cyd24R\",\"tag\":\"A1B2C3\","
                + "\"name\":\"Spotify Deck\",\"role\":\"spotify\","
                + "\"board\":\"2432S024R\",\"ip\":\"192.168.1.50\","
                + "\"setup\":false,\"signed\":true}";
        final DeckIdentity identity = DeckIdentity.parse(json);
        check("the deck names its widget and tag",
                "spotify_deck_cyd24R".equals(identity.widget)
                        && "A1B2C3".equals(identity.tag));
        check("the deck id is the widget plus the tag",
                (identity.widget + "-" + identity.tag).equals(identity.id));
        check("the deck says what it is called", "Spotify Deck".equals(identity.label));
        check("the deck says where it is", "192.168.1.50".equals(identity.ip));
        check("a signed deck reads as signed", identity.signed);
        check("a player-mode deck is not in setup", !identity.setup);
        check("a document with no id is not usable",
                !DeckIdentity.parse("{\"signed\":true}").usable());
        check("two decks of one widget are two ids",
                !DeckIdentity.parse(json).id.equals(DeckIdentity.parse(
                        json.replace("A1B2C3", "D4E5F6")).id));

        // The Bluetooth deck publishes the same keys in a read-only characteristic,
        // minus the three a board with no network cannot answer. The same parser
        // has to hold it, or the app would key one deck by id and the other by MAC.
        final String ble = "{\"id\":\"phone_deck_cyd24R-A1B2C3\","
                + "\"widget\":\"phone_deck_cyd24R\",\"tag\":\"A1B2C3\","
                + "\"name\":\"Phone Deck\",\"role\":\"phone\","
                + "\"board\":\"2432S024R\",\"link\":\"ble\"}";
        final DeckIdentity overBle = DeckIdentity.parse(ble);
        check("a Bluetooth deck names itself the same way",
                overBle.usable() && "phone_deck_cyd24R-A1B2C3".equals(overBle.id));
        check("the document says which link it arrived on", overBle.bluetooth());
        check("a Bluetooth deck has no network state to report",
                overBle.ip.isEmpty() && !overBle.signed && !overBle.setup);
        check("a read cut at the default ATT MTU names no widget",
                !DeckIdentity.parse(ble.substring(0, 22)).usable());
    }

    /**
     * The addresses, from a typed one and from the sign-in link's state: both end up
     * as the same bare host, and nothing that is not an IPv4 literal gets through.
     * The two URLs are what the app asks the deck for, so their shape is pinned.
     */
    private static void deckAddresses() {
        check("a typed address is kept", "192.168.1.50".equals(LanDeck.host("192.168.1.50")));
        check("a pasted URL loses its scheme and path",
                "192.168.1.50".equals(LanDeck.host("http://192.168.1.50/spotify")));
        check("a port survives",
                "192.168.4.1:8080".equals(LanDeck.host(" 192.168.4.1:8080 ")));
        check("a spelled-out name is refused", LanDeck.host("evil.example").isEmpty());
        check("an octet over 255 is refused", LanDeck.host("192.168.1.999").isEmpty());
        check("the identity is one path off the deck",
                "http://192.168.1.50/deck.json".equals(LanDeck.identityUrl("192.168.1.50")));
        check("the sign-in asks for the app's scheme, as text",
                "http://192.168.1.50/auth?app=1&json=1"
                        .equals(LanDeck.signInUrl("192.168.1.50")));
        check("a text answer yields the authorize URL",
                new LanDeck.Reply(200, LanDeck.AUTH_PREFIX + "client_id=x\n", "")
                        .url().startsWith(LanDeck.AUTH_PREFIX));
        check("an older deck's redirect yields it too",
                new LanDeck.Reply(302, "", LanDeck.AUTH_PREFIX + "client_id=x")
                        .url().startsWith(LanDeck.AUTH_PREFIX));
        check("a refusal explains itself",
                "no client id stored".equals(new LanDeck.Reply(409,
                        "refused: no client id stored", "").why("192.168.1.50")));
        check("an answer from anywhere else is not a URL",
                new LanDeck.Reply(200, "https://evil.example/authorize?", "").url().isEmpty());
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

    // ---------------------------------------------------------- now playing ---

    /** The line the deck's Now Playing characteristic takes: a mirror, not a login. */
    private static void nowPacket() {
        check("a now line is state|position|duration|title|artist|album",
                NowPacket.encode("play", 61_000, 210_000, "Song", "Artist", "Album")
                        .equals("play|61|210|Song|Artist|Album"));
        check("a paused line says pause",
                NowPacket.encode("pause", 5_000, 0, "T", "A", "").equals("pause|5|0|T|A|"));
        check("a state the app does not know reads as pause, not as a cleared deck",
                NowPacket.encode("buffering", 0, 0, "", "", "").equals("pause|0|0|||"));
        check("a pipe in the text becomes a slash, so it cannot move the fields",
                NowPacket.encode("play", 0, 0, "Left | Right", "A|B", "C")
                        .equals("play|0|0|Left / Right|A/B|C"));
        check("a newline never reaches the board",
                NowPacket.encode("play", 0, 0, "one\ntwo", "", "").equals("play|0|0|one two||"));
        check("a title is cut to the sketch's buffer, byte for byte",
                NowPacket.text("x".repeat(200), NowPacket.TITLE_BYTES)
                        .getBytes(StandardCharsets.UTF_8).length == NowPacket.TITLE_BYTES);
        check("a missing duration reads as --:-- on the deck, i.e. zero here",
                NowPacket.encode("play", 1_000, -1, "a", "b", "c").equals("play|1|0|a|b|c"));
        check("a position past a day is a broken line, not a bootleg concert",
                NowPacket.seconds(9_000_000_000L) == 86_400L);
        check("the longest line still fits one ATT write at MTU 185",
                NowPacket.encode("pause", 86_400_000, 86_400_000, "x".repeat(80),
                        "y".repeat(60), "z".repeat(60))
                        .getBytes(StandardCharsets.UTF_8).length <= NowPacket.ONE_WRITE_BYTES);
        check("a state that is neither play nor stop never claims to be playing",
                !NowPacket.word("buffering").equals("play") && !NowPacket.word(null).equals("play"));
    }

    // ---------------------------------------------------------- link command ---

    /**
     * The deck's tap line, held to the sketch's own buffer: {@code <serial>|<action>}
     * in at most 20 bytes, only three actions, serial from 1, and the same serial
     * twice is one gesture. Nothing here needs a phone: the parser is plain Java.
     */
    private static void linkCommand() {
        final LinkCommand toggle = LinkCommand.parse("3|toggle");
        check("the deck's own tap line parses",
                toggle != null && toggle.serial == 3
                        && LinkCommand.TOGGLE.equals(toggle.action));

        check("every action a chip can raise is understood",
                LinkCommand.parse("1|prev") != null
                        && LinkCommand.parse("2|toggle") != null
                        && LinkCommand.parse("3|next") != null);

        check("a key this app cannot run is dropped rather than guessed",
                LinkCommand.parse("7|seek") == null
                        && LinkCommand.parse("7|TOGGLE") == null
                        && LinkCommand.parse("7|Toggle") == null);

        check("a line with no serial or no action is dropped",
                LinkCommand.parse("|toggle") == null
                        && LinkCommand.parse("toggle") == null
                        && LinkCommand.parse("9|") == null);

        check("a serial that is not a positive number is dropped",
                LinkCommand.parse("0|toggle") == null
                        && LinkCommand.parse("x1|toggle") == null
                        && LinkCommand.parse("-4|next") == null);

        check("a line past the sketch's 20-byte buffer is dropped",
                LinkCommand.parse("1|toggle" + "xxxxxxxxxxxx") == null
                        && "1|toggle".getBytes(StandardCharsets.UTF_8).length
                                <= LinkCommand.MAX_BYTES);

        check("the value the stack hands over is the same line as the bytes",
                LinkCommand.parse("12|next".getBytes(StandardCharsets.UTF_8)) != null
                        && LinkCommand.parse("12|next".getBytes(StandardCharsets.UTF_8)).serial == 12);

        check("a terminator the sketch never sends is padding, not a bad character",
                LinkCommand.parse("4|next\0") != null
                        && LinkCommand.parse(" 5|prev ") != null);

        check("the same notify twice is one tap, and a rebooted counter is a new one",
                !LinkCommand.fresh(LinkCommand.parse("8|toggle"), 8)
                        && LinkCommand.fresh(LinkCommand.parse("1|toggle"), 8)
                        && !LinkCommand.fresh(null, 0));
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

    private static void bondAdvice() {
        check("a failed connect while bonded asks for the stale bond to go",
                BondAdvice.forFailure(BondAdvice.ERROR, BondAdvice.BOND_BONDED)
                        .equals(BondAdvice.FORGET));
        check("a refused write with no bond asks for one",
                BondAdvice.forFailure(BondAdvice.INSUFFICIENT_AUTHENTICATION, BondAdvice.BOND_NONE)
                        .equals(BondAdvice.PAIR));
        check("a clean disconnect, and a refusal from an already-bonded phone, ask for nothing",
                BondAdvice.forFailure(BondAdvice.SUCCESS, BondAdvice.BOND_BONDED)
                        .equals(BondAdvice.NONE)
                        && BondAdvice.forFailure(BondAdvice.INSUFFICIENT_AUTHENTICATION,
                                BondAdvice.BOND_BONDED).equals(BondAdvice.NONE));
        check("a bonding phone is still asked to pair rather than have its bond dropped",
                BondAdvice.forFailure(BondAdvice.ERROR, BondAdvice.BOND_BONDING)
                        .equals(BondAdvice.NONE));
    }

    private static void noticeText() {
        NoticeText plain = NoticeText.from("Mail", "Subject", null, "Body", null, null, null);
        check("title and text are used when an app sets them",
                plain.title.equals("Subject") && plain.body.equals("Body"));

        NoticeText ticking = NoticeText.from("Chat", null, null, null, null, "3 new", null);
        check("an app that only sets a ticker still says something",
                ticking.title.equals("3 new") && ticking.body.isEmpty());

        NoticeText inbox = NoticeText.from("Chat", null, null, null, "2 unread",
                null, Arrays.asList("first line", "second"));
        check("a sub-text and an inbox line fill in for the missing body",
                inbox.body.equals("2 unread"));

        NoticeText named = NoticeText.from("Wallet", null, null, null, null, null, null);
        check("a custom layout with nothing readable still names the app",
                named.title.equals("Wallet") && !named.empty());

        check("nothing at all is still nothing, not an empty post",
                NoticeText.from(null, null, null, null, null, null, null).empty());
    }

    /**
     * The palette's floor, measured rather than asserted in prose.
     *
     * The inks are the band grey at an alpha, so a quieter hint is always one small
     * edit away and never looks wrong in a diff. This walks what the app actually
     * draws — words on the field, words on the band, text on a filled action, and the
     * one hairline — and holds each to a ratio: 4.5:1 for body ink, 3:1 for a hint or
     * a status word, 1.5:1 for a line, which only has to be visible. It also pins that
     * a 48 % ink FAILS, so the check cannot quietly pass everything.
     */
    private static void contrast() {
        inks("dark", Palette.DARK_FIELD, Palette.DARK_PRIMARY, Palette.DARK_ON_FIELD,
                new int[] {Palette.DARK_TEXT, Palette.DARK_PRIMARY, Palette.DARK_ALOUD},
                Palette.DARK_ON_ACTION, Palette.DARK_RULES);
        inks("light", Palette.LIGHT_FIELD, Palette.LIGHT_PRIMARY, Palette.LIGHT_ON_FIELD,
                new int[] {Palette.LIGHT_TEXT, Palette.LIGHT_PRIMARY, Palette.LIGHT_ALOUD},
                Palette.LIGHT_ON_ACTION, Palette.LIGHT_RULES);
        for (int ink : Palette.ON_BAND) {
            check("contrast: " + percent(ink) + " on the band clears 3:1 ("
                            + tenths(Contrast.ratio(ink, Palette.BAND)) + ")",
                    Contrast.ratio(ink, Palette.BAND) >= Contrast.QUIET);
        }
        check("contrast: black ink on the band carries body text at 4.5:1 ("
                        + tenths(Contrast.ratio(Palette.INK, Palette.BAND)) + ")",
                Contrast.ratio(Palette.INK, Palette.BAND) >= Contrast.BODY);
        check("contrast: the band's own paper reads under black ink at 4.5:1 ("
                        + tenths(Contrast.ratio(Palette.BAND, Palette.INK)) + ")",
                Contrast.ratio(Palette.BAND, Palette.INK) >= Contrast.BODY);
        // The refusal: this is the ink strength the sheet shipped that read as a
        // hairline, and the reason the check exists. If it ever passes, the floor
        // stopped meaning anything.
        check("contrast: a quarter-strength ink on the off-white is refused ("
                        + tenths(Contrast.ratio(0x40000000, Palette.LIGHT_FIELD)) + " < 3.0)",
                Contrast.ratio(0x40000000, Palette.LIGHT_FIELD) < Contrast.QUIET);
    }

    /**
     * One theme's floors: every ink that carries a word on the page, the ones that
     * carry body text, the ones that sit on a filled action, and the hairline. The
     * page is two values, so the ramp order is what stops a theme that keeps every
     * floor while collapsing two steps into one.
     */
    private static void inks(String name, int field, int primary, int[] onField,
                             int[] body, int[] onAction, int[] rules) {
        for (int ink : onField) {
            check("contrast: " + name + " " + percent(ink) + " ink on the page clears 3:1 ("
                            + tenths(Contrast.ratio(ink, field)) + ")",
                    Contrast.ratio(ink, field) >= Contrast.QUIET);
        }
        for (int ink : body) {
            check("contrast: " + name + " " + percent(ink)
                            + " ink carries body text at 4.5:1 ("
                            + tenths(Contrast.ratio(ink, field)) + ")",
                    Contrast.ratio(ink, field) >= Contrast.BODY);
        }
        for (int fill : onAction) {
            check("contrast: " + name + " ink on a filled action clears 4.5:1 ("
                            + tenths(Contrast.ratio(fill, primary)) + ")",
                    Contrast.ratio(fill, primary) >= Contrast.BODY);
        }
        for (int rule : rules) {
            check("contrast: the " + name + " hairline is visible at 1.5:1 ("
                            + tenths(Contrast.ratio(rule, field)) + ")",
                    Contrast.ratio(rule, field) >= Contrast.RULE);
        }
        // Quietest first, so each step has to be at least as loud as the one before.
        double previous = 0;
        boolean ordered = true;
        final StringBuilder ramp = new StringBuilder();
        for (int ink : onField) {
            final double ratio = Contrast.ratio(ink, field);
            ordered &= ratio >= previous;
            previous = ratio;
            ramp.append(tenths(ratio)).append(' ');
        }
        check("contrast: the " + name + " ramp stays in order, quietest first ("
                        + ramp.toString().trim() + ")", ordered);
    }

    /**
     * Every control fills its own background and then draws its own label on it, and
     * the pair is what has to clear the floor: a fill can be lifted a step at a time
     * until the word on it is as quiet as the page around it, while every check that
     * measures an ink against the page still passes. That is how a button went from
     * reading as a panel to reading as a caption, and no paragraph caught it.
     */
    private static void labels() {
        label("dark", Palette.DARK_FIELD, Palette.DARK_WASH, Palette.DARK_INPUT,
                Palette.DARK_TEXT, Palette.DARK_DIM, Palette.DARK_PRIMARY, 0xFF000000);
        label("light", Palette.LIGHT_FIELD, Palette.LIGHT_WASH, Palette.LIGHT_INPUT,
                Palette.LIGHT_TEXT, Palette.LIGHT_DIM, Palette.LIGHT_PRIMARY, 0xFFF1F0EB);
    }

    private static void label(String name, int page, int wash, int input, int text, int dim,
                              int filled, int onFilled) {
        // The wash and the field fill are translucent, so each is laid over the page
        // first: a ratio taken against a half-transparent colour measures nothing.
        final int washOnPage = Contrast.over(wash, page);
        final int inputOnPage = Contrast.over(input, page);
        check("contrast: " + name + " a secondary action's label on its own wash clears "
                        + "4.5:1 (" + tenths(Contrast.ratio(text, washOnPage)) + ")",
                Contrast.ratio(text, washOnPage) >= Contrast.BODY);
        check("contrast: " + name + " a field's text on its own fill clears 4.5:1 ("
                        + tenths(Contrast.ratio(text, inputOnPage)) + ")",
                Contrast.ratio(text, inputOnPage) >= Contrast.BODY);
        check("contrast: " + name + " a field's placeholder on that fill clears 3:1 ("
                        + tenths(Contrast.ratio(dim, inputOnPage)) + ")",
                Contrast.ratio(dim, inputOnPage) >= Contrast.QUIET);
        check("contrast: " + name + " the live segment's label on the page ink clears 4.5:1 ("
                        + tenths(Contrast.ratio(onFilled, text)) + ")",
                Contrast.ratio(onFilled, text) >= Contrast.BODY);
        check("contrast: " + name + " the primary action's label on its fill clears 4.5:1 ("
                        + tenths(Contrast.ratio(onFilled, filled)) + ")",
                Contrast.ratio(onFilled, filled) >= Contrast.BODY);
    }

    /** {@code 0xA8D9D9D9} reads as "66 %", which is how the palette is described. */
    private static String percent(int argb) {
        return Math.round(100.0 * ((argb >>> 24) & 0xFF) / 255.0) + " %";
    }

    private static String tenths(double ratio) {
        return String.format(java.util.Locale.US, "%.2f:1", ratio);
    }

    /**
     * The palette byte is the one thing both ends must agree on: 0 PAPER, 1 INK,
     * nothing else written and nothing else accepted. The app draws "PAPER" on a
     * chip that sends 0 to a board whose own table calls 0 PAPER, so a swap here
     * is a switch that lies in both directions at once.
     */
    private static void deckTheme() {
        check("theme: PAPER is the byte 0 the board's table calls paper",
                DeckTheme.payload(DeckTheme.PAPER)[0] == 0);
        check("theme: INK is the byte 1 the board's table calls ink",
                DeckTheme.payload(DeckTheme.INK)[0] == 1);
        check("theme: a read of 0 decodes to PAPER",
                DeckTheme.decode(new byte[] {0}) == DeckTheme.PAPER);
        check("theme: a read of 1 decodes to INK",
                DeckTheme.decode(new byte[] {1}) == DeckTheme.INK);
        check("theme: a palette the board does not have is unknown, not guessed",
                DeckTheme.decode(new byte[] {2}) == DeckTheme.UNKNOWN
                        && DeckTheme.decode(new byte[0]) == DeckTheme.UNKNOWN
                        && DeckTheme.decode(null) == DeckTheme.UNKNOWN);
        check("theme: the chips are named the way the deck's table spells them",
                DeckTheme.name(DeckTheme.PAPER).equals("PAPER")
                        && DeckTheme.name(DeckTheme.INK).equals("INK"));
    }

    /**
     * The stamp on the flash shelf is what stops a stale COPY being flashed as if
     * it were today's sketch: the shelf names the bytes, Arduino names the size it
     * compiled, and this tree quotes the md5. These are the formatting rules the
     * card promises, so a change to them has to be a deliberate one.
     */
    private static void sketchStamp() {
        byte[] abc = "abc".getBytes(StandardCharsets.UTF_8);
        check("stamp: a sketch reads as its size and the first eight md5 digits",
                SketchStamp.of(abc).equals("3 B  ·  90015098"));
        check("stamp: the digest is the md5 this tree quotes, to eight digits",
                SketchStamp.digest(abc).equals("90015098")
                        && SketchStamp.digest("".getBytes(StandardCharsets.UTF_8))
                                .equals("d41d8cd9"));
        check("stamp: thousands are separated the way the docs write them",
                SketchStamp.of(new byte[150544]).startsWith("150,544 B  ·  "));
        check("stamp: nothing to read says so instead of looking like a build",
                SketchStamp.of(null).equals("unreadable")
                        && SketchStamp.of(new byte[0]).equals("empty"));
    }

    /**
     * One constant per sketch names the build, and both readers have to agree with
     * it: the identity document carries it so a linked deck says what it runs, and
     * the shelf reads the same declaration out of the bundled text.
     */
    private static void deckVersion() {
        DeckIdentity named = DeckIdentity.parse("{\"id\":\"phone_deck_cyd24R-A1B2C3\","
                + "\"widget\":\"phone_deck_cyd24R\",\"v\":\"1.0.0\",\"link\":\"ble\"}");
        check("version: a deck's own build is read from the document",
                named.version.equals("1.0.0") && named.usable());
        DeckIdentity older = DeckIdentity.parse("{\"id\":\"phone_deck_cyd24R-A1B2C3\","
                + "\"widget\":\"phone_deck_cyd24R\",\"link\":\"ble\"}");
        check("version: a deck flashed before the key reads empty, not a guess",
                older.version.isEmpty() && older.usable() && older.bluetooth());
        check("version: a sketch names its build in one declaration",
                SketchVersion.of("static const char DECK_VERSION[] = \"1.0.0\";").equals("1.0.0")
                        && SketchVersion.of("static constexpr char DECK_VERSION[] = \"2.3.1\";")
                                .equals("2.3.1"));
        check("version: a sketch that declares none reads empty",
                SketchVersion.of("/* nothing here */").isEmpty()
                        && SketchVersion.of(null).isEmpty());
        check("version: a card writes it the way a person reads it",
                SketchVersion.label("1.0.0").equals("v1.0.0")
                        && SketchVersion.label("").isEmpty());
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
