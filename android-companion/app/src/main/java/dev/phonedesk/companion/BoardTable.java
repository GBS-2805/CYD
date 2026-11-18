package dev.phonedesk.companion;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The CYD variants this app can target, as plain data.
 *
 * <b>Why this is not inside {@link BoardCatalog}.</b> The catalogue needs
 * {@code SharedPreferences} to remember a choice, and {@code test.sh} compiles
 * against no Android classes at all. Keeping the table here means the host suite
 * can check that every code is unique, that the default is the board the deck
 * firmware was written for, and that an image is refused when it cannot fit —
 * without an emulator.
 *
 * <b>Only classic ESP32 boards belong here.</b> {@link EspRom} speaks the classic
 * ESP32 ROM protocol: the sync frame carries 0x07 0x07 0x12 0x20, and the chip
 * magic it reads is the ESP32's. An ESP32-S3 CYD (the 8048S0xx family) answers a
 * different sync and needs a different loader, so listing one would produce a
 * board that is selectable and can never flash. The picker says so instead.
 *
 * <b>Flash size is a guard, not a promise.</b> Every board below ships with 4 MB.
 * The app writes a merged image at 0x0, so the image has to fit the whole flash,
 * not just the app partition. {@link #fits} is what the flash dialog asks before
 * it offers to erase anything.
 */
final class BoardTable {

    static final class Board {
        /** Short code used in section headers, e.g. 2432S024R. */
        final String code;
        final String label;
        /** Panel, touch and bridge, in the words the picker shows. */
        final String note;
        /** Diagonal and resolution, e.g. {@code 2.4" · 320×240}. */
        final String panel;
        /** {@code resistive} or {@code capacitive}. */
        final String touch;
        /** Whole flash in megabytes. A merged image must fit all of it. */
        final int flashMb;
        /** The USB bridge the board ships with. */
        final String bridge;

        Board(String code, String label, String panel, String touch, int flashMb,
              String bridge, String note) {
            this.code = code;
            this.label = label;
            this.panel = panel;
            this.touch = touch;
            this.flashMb = flashMb;
            this.bridge = bridge;
            this.note = note;
        }

        String spinnerLabel() {
            return label + "  ·  " + code;
        }

        /** One line for the flash dialog and the probe log. */
        String summary() {
            return code + " · " + panel + " · " + touch + " · " + flashMb + " MB · " + bridge;
        }

        long flashBytes() {
            return flashMb * 1024L * 1024L;
        }
    }

    /**
     * The 2.4" resistive board first: it is the one the bundled deck firmware was
     * written for, and the default a fresh install gets.
     */
    private static final List<Board> ALL = Collections.unmodifiableList(Arrays.asList(
            new Board("2432S024R", "ESP32-2432S024R", "2.4\" · 320×240", "resistive", 4, "CH340",
                    "The board the deck firmware runs on. Resistive touch, 4 MB flash, "
                            + "CH340 over USB-C."),
            new Board("2432S024C", "ESP32-2432S024C", "2.4\" · 320×240", "capacitive", 4, "CH340",
                    "Same panel and flash as the R, with a capacitive touch controller. "
                            + "The deck sketch needs its touch driver swapped."),
            new Board("2432S028R", "ESP32-2432S028R", "2.4\" · 320×240", "resistive", 4, "CH340",
                    "The older 2.4\" CYD. Same panel as the 024R, different pin map; "
                            + "pick a firmware build that names 2432S028."),
            new Board("3248S035R", "ESP32-3248S035R", "3.5\" · 480×320", "resistive", 4, "CH340",
                    "3.5\" panel. The deck sketch is written for 320×240, so it will "
                            + "draw in a corner until its layout is ported."),
            new Board("3248S035C", "ESP32-3248S035C", "3.5\" · 480×320", "capacitive", 4, "CH340",
                    "3.5\" capacitive. Needs both a layout port and a touch driver."),
            new Board("3248S040", "ESP32-3248S040", "4.0\" · 480×320", "resistive", 4, "CH340",
                    "4.0\" panel, same 480×320 resolution as the 3.5\". Layout port needed.")
    ));

    private BoardTable() {
    }

    static List<Board> all() {
        return ALL;
    }

    static Board defaultBoard() {
        return ALL.get(0);
    }

    static Board byCode(String code) {
        if (code == null) return null;
        for (Board board : ALL) {
            if (board.code.equalsIgnoreCase(code)) return board;
        }
        return null;
    }

    /**
     * Whether a merged image can be written to this board at 0x0. A merged image
     * covers the bootloader, the partition table and the app, so it is compared
     * with the whole flash rather than with a partition.
     */
    static boolean fits(Board board, long imageBytes) {
        return board != null && imageBytes > 0 && imageBytes <= board.flashBytes();
    }
}
