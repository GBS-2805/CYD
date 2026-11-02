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
 * card's buttons name is the first one the fallback sweep tries.
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
