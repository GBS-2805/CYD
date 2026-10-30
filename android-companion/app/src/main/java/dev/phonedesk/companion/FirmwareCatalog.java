package dev.phonedesk.companion;

import java.util.Arrays;
import java.util.List;

/**
 * Third-party firmware for the exact board this repo targets: ESP32-2432S024R,
 * the 2.4" CYD with a resistive panel and a CH340 bridge.
 *
 * Only the deck sketch is an ArduinoDroid build. These are prebuilt images whose
 * sources are PlatformIO projects with pinned dependencies, so each entry says
 * which image to pick rather than pretending it can be pasted into an editor.
 */
final class FirmwareCatalog {

    static final class Item {
        final String name;
        final String tagline;
        final String body;
        final String repoUrl;
        final String flasherUrl;
        final String note;
        final String libraries;

        Item(String name, String tagline, String body, String repoUrl, String flasherUrl,
             String note, String libraries) {
            this.name = name;
            this.tagline = tagline;
            this.body = body;
            this.repoUrl = repoUrl;
            this.flasherUrl = flasherUrl;
            this.note = note;
            this.libraries = libraries;
        }
    }

    private FirmwareCatalog() {
    }

    static List<Item> all() {
        return Arrays.asList(
                new Item(
                        "ESP32 Marauder",
                        "2432S024R BUILD",
                        "Pentest firmware. Fr4nkFletcher's CYD fork names this panel; the "
                                + "official installer also carries a 2432S024 image.",
                        "https://github.com/Fr4nkFletcher/ESP32-Marauder-Cheap-Yellow-Display",
                        "https://justcallmekoko.github.io/MarauderInstaller/",
                        "Pick CYD 2432S024. Blank screen or dead touch: use the fork's "
                                + "2432S024R build.",
                        "Prebuilt image."),
                new Item(
                        "Bruce",
                        "2432S024R BUILD",
                        "Wi-Fi, BLE, RF and IR multi-tool with a CYD-2432S024R build.",
                        "https://github.com/BruceDevices/firmware",
                        "https://bruce.computer/flasher",
                        "CYD-2432S024R, not 2432S028. Accept the first-boot touch calibration.",
                        "Prebuilt image."),
                new Item(
                        "Launcher",
                        "2432S024R PORT",
                        "Boots Marauder, Bruce or any .bin from SD or OTA, so no reflashing.",
                        "https://github.com/bmorcelli/Launcher",
                        "https://bmorcelli.github.io/Launcher/catalog.html",
                        "Pick a Launcher-<device>.bin with 2432S024R in the name.",
                        "Prebuilt image."));
    }
}
