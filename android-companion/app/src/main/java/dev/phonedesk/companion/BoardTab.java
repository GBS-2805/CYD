package dev.phonedesk.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Board tab: the OTG link and the images that go on the board.
 *
 * Two modes share one cable. FLASH picks the target, probes the ROM bootloader,
 * restarts it and writes an image; MONITOR opens the same CH340 as a plain serial
 * line and prints what the board sends. The monitor owns the port while it runs,
 * because USB can have one owner at a time: every other path here opens, uses and
 * closes the port inside one thread.
 */
final class BoardTab extends ScrollView {

    static final int REQUEST_IMPORT = 41;
    private static final String ACTION_USB_PERMISSION =
            "dev.phonedesk.companion.USB_PERMISSION";

    private final Activity activity;
    private final LinearLayout body;
    private String signature = "";

    private String usbStatus = "Plug the CYD in with an OTG cable, then tap PROBE.";
    private int usbStatusColor = Ui.DIM;
    private boolean usbProbing;
    private boolean usbFlashing;
    private int usbPercentShown = -1;

    /**
     * Handshake narration from the last probe or flash, newest last. Kept across
     * runs on purpose: a failure is usually only readable next to the attempt
     * before it, so CLEAR is a button rather than something that happens for you.
     */
    private final List<String> usbLog = new ArrayList<>();
    private static final int LOG_MAX = 80;
    private int logVersion;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    // ------------------------------------------------------------- monitor ---

    private static final String PREFS = "board";
    private static final String KEY_MONITOR_MODE = "monitor_mode";
    private static final String KEY_MONITOR_BAUD = "monitor_baud";
    private static final String KEY_MONITOR_EOL = "monitor_eol";
    private static final int PURPOSE_PROBE = 0;
    private static final int PURPOSE_MONITOR = 1;
    /** Transcript ceiling in characters; the oldest complete line is dropped first. */
    private static final int MONITOR_CHARS_MAX = 24000;
    private static final int[] MONITOR_BAUDS = {115200, 9600, 57600, 230400, 460800, 921600};

    /** FLASH shows the images and the bootloader; MONITOR streams the serial line. */
    private boolean monitorMode;
    private volatile int monitorBaud = 115200;
    /** True while the reader thread owns the port. */
    private volatile boolean monitorOpen;
    private volatile boolean monitorRunning;
    private volatile boolean monitorResetRequested;
    private volatile Thread monitorThread;
    private int permissionPurpose = PURPOSE_PROBE;
    private int startRetries;

    /** The transcript, appended on the reader thread and rendered on the UI one. */
    private final StringBuilder monitorText = new StringBuilder();
    private boolean monitorRenderPending;
    /** Held so a rebuild does not lose the view the stream writes into. */
    private TextView monitorView;
    private ScrollView monitorBox;

    /**
     * Typed lines wait here until the reader thread writes them. The reader owns
     * the port, so a send from the UI thread is a hand-off, not a bulkTransfer.
     */
    private final ArrayDeque<String> monitorOutbox = new ArrayDeque<>();
    private final EditText monitorInput;
    /** "\r\n" is what a terminal's Enter sends; "\n" suits a sketch that splits on it. */
    private String monitorLineEnding = "\r\n";

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            unregisterUsbPermission();
            final UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            final boolean granted =
                    intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (!granted || device == null) {
                setUsbStatus("Android denied access to the CH340.", Ui.PINK);
                return;
            }
            if (permissionPurpose == PURPOSE_MONITOR) {
                permissionPurpose = PURPOSE_PROBE;
                final UsbManager manager =
                        (UsbManager) activity.getSystemService(Context.USB_SERVICE);
                if (manager == null) {
                    setUsbStatus("This phone has no USB host support.", Ui.PINK);
                    return;
                }
                openMonitor(manager, device);
                return;
            }
            probeUsb(device);
        }
    };

    BoardTab(Activity activity) {
        super(activity);
        this.activity = activity;
        setBackgroundColor(Ui.BG);
        body = Ui.column(activity);
        body.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 8),
                Ui.dp(activity, 16), Ui.dp(activity, 24));
        addView(body, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        monitorMode = prefs().getBoolean(KEY_MONITOR_MODE, false);
        monitorBaud = prefs().getInt(KEY_MONITOR_BAUD, 115200);
        monitorLineEnding = prefs().getString(KEY_MONITOR_EOL, "\r\n");

        // Built once and re-added on each rebuild, so a half-typed line survives
        // the tab refreshing underneath it.
        monitorInput = new EditText(activity);
        monitorInput.setHint("Type a line for the board");
        monitorInput.setSingleLine(true);
        monitorInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        monitorInput.setTextSize(12);
        monitorInput.setTextColor(Ui.TEXT);
        monitorInput.setHintTextColor(Ui.DIM);
        monitorInput.setTypeface(Typeface.MONOSPACE);
        monitorInput.setBackground(Ui.rounded(activity, Ui.CARD_HI, Ui.EDGE, 8));
        monitorInput.setPadding(Ui.dp(activity, 10), Ui.dp(activity, 8),
                Ui.dp(activity, 10), Ui.dp(activity, 8));
    }

    void refresh() {
        List<FlashLibrary.Entry> entries = FlashLibrary.list(activity);
        StringBuilder sig = new StringBuilder();
        for (FlashLibrary.Entry entry : entries) {
            sig.append(entry.name).append(':').append(entry.size).append(';');
        }
        sig.append('b').append(BoardCatalog.selectedIndex(activity))
                .append('s').append(usbStatus)
                .append(usbProbing).append(usbFlashing)
                .append('l').append(logVersion)
                .append('m').append(monitorMode).append(monitorOpen).append(monitorBaud);
        if (!sig.toString().equals(signature)) {
            signature = sig.toString();
            int keepScroll = getScrollY();
            build(entries);
            post(() -> scrollTo(0, keepScroll));
        }
    }

    void startImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            activity.startActivityForResult(intent, REQUEST_IMPORT);
        } catch (Exception e) {
            Actions.toast(activity, "No file picker");
        }
    }

    void imported(boolean ok) {
        Actions.toast(activity, ok ? "Imported" : "Import failed");
        if (ok) {
            signature = "";
            refresh();
        }
    }

    private void build(List<FlashLibrary.Entry> entries) {
        body.removeAllViews();
        monitorView = null;
        monitorBox = null;

        body.addView(Ui.text(activity, "BOARD", 20, Ui.TEXT));
        TextView subtitle = Ui.text(activity, monitorMode
                ? "Connect over OTG, then watch what the board prints."
                : "Connect over OTG, then flash an image.", 12, Ui.DIM);
        subtitle.setPadding(0, Ui.dp(activity, 4), 0, 0);
        body.addView(subtitle);

        body.addView(boardPicker());
        body.addView(modeRow());

        if (monitorMode) {
            body.addView(monitorCard());
        } else {
            body.addView(usbCard());
            body.addView(imagesSection(entries));
        }
    }

    /**
     * FLASH and MONITOR, side by side. One owns the cable at a time: the monitor
     * holds the CH340 open while it streams, so leaving the mode stops it.
     */
    private LinearLayout modeRow() {
        LinearLayout row = Ui.row(activity);
        row.setPadding(0, Ui.dp(activity, 14), 0, Ui.dp(activity, 2));

        Button flash = Ui.action(activity, "FLASH", !monitorMode);
        flash.setOnClickListener(v -> setMode(false));
        Button monitor = Ui.action(activity, "MONITOR", monitorMode);
        monitor.setOnClickListener(v -> setMode(true));

        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        left.rightMargin = Ui.dp(activity, 8);
        row.addView(flash, left);
        row.addView(monitor, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private void setMode(boolean monitor) {
        if (monitorMode == monitor) return;
        monitorMode = monitor;
        prefs().edit().putBoolean(KEY_MONITOR_MODE, monitor).apply();
        if (!monitor) stopMonitor(null);
        signature = "";
        refresh();
    }

    /** IMAGES: the imported binaries. Bundled widgets are sources, not flashable images. */
    private LinearLayout imagesSection(List<FlashLibrary.Entry> entries) {
        LinearLayout section = Ui.column(activity);
        section.addView(Ui.section(activity, "IMAGES"));

        Button importButton = Ui.action(activity, "IMPORT A FILE", false);
        importButton.setOnClickListener(v -> startImport());
        Ui.space(activity, importButton, 6);
        section.addView(importButton);

        int imported = 0;
        for (FlashLibrary.Entry entry : entries) {
            if (entry.bundled) continue;
            ++imported;
            section.addView(entryCard(entry));
        }
        if (imported == 0) {
            section.addView(Ui.text(activity,
                    "No images yet. Import a .bin to flash it.", 12, Ui.DIM));
        }

        TextView hint = Ui.text(activity,
                "FLASH writes at 0x0, so use a merged image. An app-only .bin belongs at "
                        + "0x10000 and will not boot from 0x0.", 11, Ui.DIM);
        hint.setPadding(0, Ui.dp(activity, 14), 0, 0);
        hint.setLineSpacing(Ui.dp(activity, 2), 1f);
        section.addView(hint);
        return section;
    }

    // --------------------------------------------------------- serial stream ---

    private LinearLayout monitorCard() {
        LinearLayout card = Ui.card(activity);

        TextView tag = Ui.text(activity, "SERIAL", 11, Ui.VIOLET);
        tag.setLetterSpacing(0.14f);
        card.addView(tag);

        TextView hint = Ui.text(activity,
                "START opens the CH340 and prints what the board sends. Flashing needs the "
                        + "cable to itself, so leaving this mode stops the stream. Baud can "
                        + "be changed while it runs; the board has to be set to the same rate. "
                        + "SEND writes the typed line plus a line ending, so a sketch waiting "
                        + "on Serial can be answered.",
                11, Ui.DIM);
        hint.setPadding(0, Ui.dp(activity, 6), 0, 0);
        hint.setLineSpacing(Ui.dp(activity, 2), 1f);
        card.addView(hint);

        TextView status = Ui.text(activity, usbStatus, 12, usbStatusColor);
        status.setPadding(0, Ui.dp(activity, 10), 0, 0);
        status.setLineSpacing(Ui.dp(activity, 2), 1f);
        card.addView(status);

        LinearLayout baudRow = Ui.row(activity);
        TextView baudLabel = Ui.text(activity, "BAUD", 11, Ui.DIM);
        baudLabel.setLetterSpacing(0.14f);
        baudRow.addView(baudLabel);

        final List<String> rates = new ArrayList<>();
        int selectedRate = 0;
        for (int i = 0; i < MONITOR_BAUDS.length; ++i) {
            rates.add(String.valueOf(MONITOR_BAUDS[i]));
            if (MONITOR_BAUDS[i] == monitorBaud) selectedRate = i;
        }
        Spinner spinner = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, rates);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selectedRate);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                setMonitorBaud(MONITOR_BAUDS[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Keep the current rate.
            }
        });
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        spinnerParams.leftMargin = Ui.dp(activity, 10);
        baudRow.addView(spinner, spinnerParams);

        LinearLayout.LayoutParams baudParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        baudParams.topMargin = Ui.dp(activity, 10);
        card.addView(baudRow, baudParams);

        Button start = Ui.action(activity, monitorOpen ? "STOP MONITOR" : "START MONITOR",
                !monitorOpen);
        start.setOnClickListener(v -> toggleMonitor());
        Ui.space(activity, start, 10);
        card.addView(start);

        Button restart = Ui.action(activity, "RESTART + WATCH", false);
        restart.setEnabled(monitorOpen);
        restart.setOnClickListener(v -> requestMonitorReset());
        Ui.space(activity, restart, 8);
        card.addView(restart);

        card.addView(Ui.section(activity, "OUTPUT"));

        monitorView = Ui.text(activity, monitorText.length() == 0
                ? "Nothing yet. START opens the port; a board that is already running "
                + "stays quiet until it prints."
                : monitorTranscript(), 10, monitorText.length() == 0 ? Ui.DIM : Ui.TEXT);
        monitorView.setTextIsSelectable(true);
        monitorView.setLineSpacing(Ui.dp(activity, 2), 1f);

        final ScrollView box = new ScrollView(activity);
        box.addView(monitorView);
        monitorBox = box;
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(activity, 240));
        boxParams.topMargin = Ui.dp(activity, 4);
        card.addView(box, boxParams);
        if (monitorOpen) box.post(() -> box.fullScroll(View.FOCUS_DOWN));

        LinearLayout inputRow = Ui.row(activity);
        final boolean typing = monitorInput.hasFocus();
        inputRow.addView(monitorInput, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button send = Ui.action(activity, "SEND", true);
        send.setEnabled(monitorOpen);
        send.setOnClickListener(v -> sendMonitorLine(monitorInput.getText().toString()));
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        sendParams.leftMargin = Ui.dp(activity, 8);
        inputRow.addView(send, sendParams);

        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        inputParams.topMargin = Ui.dp(activity, 8);
        card.addView(inputRow, inputParams);
        if (typing) {
            inputRow.post(() -> {
                monitorInput.requestFocus();
                monitorInput.setSelection(monitorInput.getText().length());
            });
        }

        LinearLayout actions = Ui.row(activity);
        actions.setPadding(0, Ui.dp(activity, 8), 0, 0);
        Button copy = Ui.action(activity, "COPY", false);
        copy.setOnClickListener(v -> Actions.copyOrSay(activity, "serial log", monitorTranscript()));
        actions.addView(copy);

        Button clear = Ui.action(activity, "CLEAR", false);
        clear.setTextColor(Ui.DIM);
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        clearParams.leftMargin = Ui.dp(activity, 8);
        clear.setLayoutParams(clearParams);
        clear.setOnClickListener(v -> {
            synchronized (monitorText) {
                monitorText.setLength(0);
            }
            scheduleMonitorUpdate();
        });
        actions.addView(clear);

        Button ending = Ui.action(activity, "EOL: " + monitorLineEndingLabel(), false);
        ending.setTextColor(Ui.DIM);
        LinearLayout.LayoutParams endingParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        endingParams.leftMargin = Ui.dp(activity, 8);
        ending.setLayoutParams(endingParams);
        ending.setOnClickListener(v -> toggleLineEnding());
        actions.addView(ending);
        card.addView(actions);

        Ui.space(activity, card, 10);
        return card;
    }

    /** START/STOP. Asks for the CH340 first; the permission callback lands in openMonitor. */
    private void toggleMonitor() {
        if (monitorOpen) {
            stopMonitor("Monitor stopped. The cable is free to flash.");
            return;
        }
        if (monitorThread != null) {
            // The old reader is still closing the port; give it a few read timeouts.
            if (++startRetries > 12) {
                startRetries = 0;
                setUsbStatus("The port did not close. Unplug the board and plug it back in.",
                        Ui.PINK);
                return;
            }
            setUsbStatus("Waiting for the port to close…", Ui.ACCENT);
            postDelayed(this::toggleMonitor, 250);
            return;
        }
        startRetries = 0;

        final UsbManager manager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            setUsbStatus("This phone has no USB host support.", Ui.PINK);
            return;
        }
        final UsbDevice device = UsbSerialCh340.find(manager);
        if (device == null) {
            setUsbStatus("No CH340 on the USB bus. Check the OTG adapter and the cable.", Ui.PINK);
            return;
        }
        if (!manager.hasPermission(device)) {
            permissionPurpose = PURPOSE_MONITOR;
            setUsbStatus("Asking Android for the CH340…", Ui.ACCENT);
            requestUsbPermission(manager, device);
            return;
        }
        openMonitor(manager, device);
    }

    private void openMonitor(UsbManager manager, UsbDevice device) {
        if (monitorOpen || monitorThread != null) return;
        monitorOpen = true;
        monitorRunning = true;
        monitorResetRequested = false;
        synchronized (monitorOutbox) {
            monitorOutbox.clear();
        }
        appendMonitor("\n== MONITOR " + describe(device) + " · " + monitorBaud + " baud ==\n");
        setUsbStatus("Monitoring " + describe(device) + " at " + monitorBaud + " baud.",
                Ui.ACCENT);
        final Thread thread = new Thread(() -> runMonitor(manager, device), "usb-monitor");
        monitorThread = thread;
        thread.start();
    }

    /**
     * The reader. It owns the port for the whole session, so a baud change and the
     * reset button are flags it acts on between reads rather than calls from the UI
     * thread into a connection that is mid-transfer.
     */
    private void runMonitor(UsbManager manager, UsbDevice device) {
        final UsbSerialCh340 port = new UsbSerialCh340(manager, device);
        String ending = null;
        int activeBaud = monitorBaud;
        try {
            port.open();
            if (activeBaud != 115200) port.changeBaudRate(activeBaud);
            appendMonitor("port open\n");
            final byte[] chunk = new byte[1024];
            final byte[] hold = new byte[4];
            int held = 0;
            int quiet = 0;
            while (monitorRunning) {
                if (monitorResetRequested) {
                    monitorResetRequested = false;
                    appendMonitor("\n== reset: IO0 high, EN pulsed ==\n");
                    try {
                        new EspRom(port).hardReset();
                    } catch (Exception e) {
                        appendMonitor("reset failed: "
                                + (e.getMessage() == null ? e.toString() : e.getMessage()) + "\n");
                    }
                }
                if (monitorBaud != activeBaud) {
                    activeBaud = monitorBaud;
                    held = 0;
                    try {
                        port.changeBaudRate(activeBaud);
                        appendMonitor("\n== " + activeBaud + " baud ==\n");
                    } catch (Exception e) {
                        appendMonitor("could not change to " + activeBaud + " baud: "
                                + (e.getMessage() == null ? e.toString() : e.getMessage()) + "\n");
                    }
                }
                sendQueued(port);
                final int read = port.read(chunk, 200);
                if (read > 0) {
                    quiet = 0;
                    held = decodeInto(chunk, read, hold, held);
                } else if (++quiet % 15 == 0 && !stillAttached(manager, device)) {
                    ending = "Board unplugged.";
                    break;
                }
            }
        } catch (Exception e) {
            ending = "Monitor failed: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            port.close();
            appendMonitor("port closed\n");
        }
        final String reason = ending;
        monitorThread = null;
        activity.runOnUiThread(() -> {
            monitorOpen = false;
            if (reason != null) {
                setUsbStatus(reason, Ui.PINK);
            } else {
                setUsbStatus("Monitor stopped. The cable is free to flash.", Ui.DIM);
            }
        });
    }

    /** Stops the reader; it closes the port itself, within one read timeout. */
    private void stopMonitor(String message) {
        if (!monitorOpen) {
            if (message != null) setUsbStatus(message, Ui.DIM);
            return;
        }
        monitorRunning = false;
        monitorOpen = false;
        if (message != null) {
            setUsbStatus(message, Ui.DIM);
        } else {
            signature = "";
            refresh();
        }
    }

    /** Pulses the board's reset so its boot banner lands in the stream. */
    private void requestMonitorReset() {
        if (!monitorOpen) {
            setUsbStatus("Start the monitor first.", Ui.DIM);
            return;
        }
        monitorResetRequested = true;
        setUsbStatus("Resetting the board so its boot text is visible…", Ui.ACCENT);
    }

    /** Queues one typed line; the reader thread writes it between reads. */
    private void sendMonitorLine(String typed) {
        if (!monitorOpen) {
            setUsbStatus("Start the monitor first.", Ui.DIM);
            return;
        }
        final String line = typed + monitorLineEnding;
        final boolean full;
        synchronized (monitorOutbox) {
            full = monitorOutbox.size() >= 16;
            if (!full) monitorOutbox.addLast(line);
        }
        if (full) {
            setUsbStatus("Sixteen lines are already queued; wait for the board.", Ui.PINK);
            return;
        }
        if (!typed.isEmpty()) monitorInput.setText("");
    }

    /** Drains the outbox. Only the reader thread writes: it owns the connection. */
    private void sendQueued(UsbSerialCh340 port) {
        while (true) {
            final String line;
            synchronized (monitorOutbox) {
                line = monitorOutbox.pollFirst();
            }
            if (line == null) return;
            try {
                port.write(line.getBytes(StandardCharsets.UTF_8));
                final String body = line.replace("\r", "").replace("\n", "");
                appendMonitor("→ " + (body.isEmpty() ? "(empty line)" : body) + "\n");
            } catch (Exception e) {
                appendMonitor("send failed: "
                        + (e.getMessage() == null ? e.toString() : e.getMessage()) + "\n");
            }
        }
    }

    /** CRLF is a terminal's Enter; LF is for a sketch that splits on the newline alone. */
    private void toggleLineEnding() {
        monitorLineEnding = "\r\n".equals(monitorLineEnding) ? "\n" : "\r\n";
        prefs().edit().putString(KEY_MONITOR_EOL, monitorLineEnding).apply();
        signature = "";
        refresh();
    }

    private String monitorLineEndingLabel() {
        return "\r\n".equals(monitorLineEnding) ? "CRLF" : "LF";
    }

    private void setMonitorBaud(int baud) {
        if (baud == monitorBaud) return;
        monitorBaud = baud;
        prefs().edit().putInt(KEY_MONITOR_BAUD, baud).apply();
        if (monitorOpen) appendMonitor("\n== switching to " + baud + " baud ==\n");
        signature = "";
        refresh();
    }

    /**
     * One read as text, holding back a character split across reads so a multi-byte
     * glyph does not arrive as a replacement box. Returns the bytes held back.
     */
    private int decodeInto(byte[] chunk, int count, byte[] hold, int held) {
        final byte[] all = new byte[held + count];
        System.arraycopy(hold, 0, all, 0, held);
        System.arraycopy(chunk, 0, all, held, count);

        int keep = 0;
        for (int back = 1; back <= Math.min(3, all.length); ++back) {
            final int b = all[all.length - back] & 0xFF;
            if ((b & 0xC0) == 0x80) continue;                 // continuation byte
            final int need = ((b & 0xE0) == 0xC0) ? 2
                    : ((b & 0xF0) == 0xE0) ? 3
                    : ((b & 0xF8) == 0xF0) ? 4 : 1;
            if (back < need) keep = back;                     // lead byte, short sequence
            break;
        }

        final int usable = all.length - keep;
        appendMonitor(clean(new String(all, 0, usable, StandardCharsets.UTF_8)));
        for (int i = usable; i < all.length; ++i) hold[i - usable] = all[i];
        return keep;
    }

    /** ROM chatter is not a terminal: keep printable text and newlines, drop the rest. */
    private static String clean(String raw) {
        final StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); ++i) {
            final char c = raw.charAt(i);
            if (c == '\r') {
                if (i + 1 >= raw.length() || raw.charAt(i + 1) != '\n') out.append('\n');
                continue;
            }
            if (c == '\n' || c == '\t') {
                out.append(c);
                continue;
            }
            if (c < 0x20 || c == 0x7F) continue;
            out.append(c);
        }
        return out.toString();
    }

    private static boolean stillAttached(UsbManager manager, UsbDevice device) {
        if (manager == null) return false;
        for (UsbDevice candidate : manager.getDeviceList().values()) {
            if (candidate.getDeviceId() == device.getDeviceId()) return true;
        }
        return false;
    }

    private void appendMonitor(String text) {
        synchronized (monitorText) {
            monitorText.append(text);
            if (monitorText.length() > MONITOR_CHARS_MAX) {
                final int floor = monitorText.length() - MONITOR_CHARS_MAX;
                final int cut = monitorText.indexOf("\n", floor);
                monitorText.delete(0, cut < 0 ? floor : cut + 1);
            }
        }
        scheduleMonitorUpdate();
    }

    private void scheduleMonitorUpdate() {
        if (monitorRenderPending) return;
        monitorRenderPending = true;
        activity.runOnUiThread(() -> postDelayed(this::updateMonitorView, 120));
    }

    /** One write per burst: a line of firmware log is not a reason to rebuild the tab. */
    private void updateMonitorView() {
        monitorRenderPending = false;
        if (!monitorMode || monitorView == null) return;
        final String text = monitorTranscript();
        monitorView.setText(text.isEmpty()
                ? "Nothing yet. START opens the port; a board that is already running "
                + "stays quiet until it prints."
                : text);
        monitorView.setTextColor(text.isEmpty() ? Ui.DIM : Ui.TEXT);
        if (monitorBox != null) {
            final ScrollView box = monitorBox;
            box.post(() -> box.fullScroll(View.FOCUS_DOWN));
        }
    }

    private String monitorTranscript() {
        synchronized (monitorText) {
            return monitorText.toString();
        }
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The flash paths own the same port; refuse rather than race the reader. */
    private boolean monitorBusy() {
        if (monitorThread == null) return false;
        setUsbStatus("Waiting for the monitor to release the port…", Ui.ACCENT);
        return true;
    }

    /** Target-board pull-down. One board today; the list is the extension point. */
    private LinearLayout boardPicker() {
        LinearLayout card = Ui.card(activity);

        TextView tag = Ui.text(activity, "TARGET", 11, Ui.VIOLET);
        tag.setLetterSpacing(0.14f);
        card.addView(tag);

        final List<BoardCatalog.Board> boards = BoardCatalog.all();
        final List<String> labels = new ArrayList<>();
        for (BoardCatalog.Board board : boards) labels.add(board.spinnerLabel());

        final TextView note = Ui.text(activity, BoardCatalog.selected(activity).note, 11, Ui.DIM);
        note.setPadding(0, Ui.dp(activity, 6), 0, 0);
        note.setLineSpacing(Ui.dp(activity, 2), 1f);

        Spinner spinner = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(BoardCatalog.selectedIndex(activity));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                BoardCatalog.select(activity, position);
                note.setText(boards.get(position).note);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Keep the current board.
            }
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(activity, 6);
        card.addView(spinner, params);
        card.addView(note);

        Ui.space(activity, card, 10);
        return card;
    }

    private LinearLayout usbCard() {
        LinearLayout card = Ui.card(activity);

        TextView tag = Ui.text(activity, "USB", 11, Ui.VIOLET);
        tag.setLetterSpacing(0.14f);
        card.addView(tag);

        TextView hint = Ui.text(activity,
                "PROBE reads the chip ID and MAC, then restarts the board into its firmware. "
                        + "RESTART BOARD boots the firmware that is already there.", 11, Ui.DIM);
        hint.setPadding(0, Ui.dp(activity, 6), 0, 0);
        hint.setLineSpacing(Ui.dp(activity, 2), 1f);
        card.addView(hint);

        TextView status = Ui.text(activity, usbStatus, 12, usbStatusColor);
        status.setPadding(0, Ui.dp(activity, 10), 0, 0);
        status.setLineSpacing(Ui.dp(activity, 2), 1f);
        card.addView(status);

        Button probe = Ui.action(activity, usbProbing ? "PROBING…" : "PROBE USB", true);
        probe.setEnabled(!usbProbing);
        probe.setOnClickListener(v -> detectUsb());
        Ui.space(activity, probe, 10);
        card.addView(probe);

        Button restart = Ui.action(activity, "RESTART BOARD", false);
        restart.setEnabled(!usbProbing && !usbFlashing);
        restart.setOnClickListener(v -> restartBoard());
        Ui.space(activity, restart, 8);
        card.addView(restart);

        card.addView(Ui.section(activity, "PROBE LOG"));

        final String log = logText();
        TextView logView = Ui.text(activity, log.isEmpty()
                ? "Nothing yet. PROBE and FLASH write each handshake step here, and a "
                + "quiet ROM reports the raw bytes it did send."
                : log, 11, log.isEmpty() ? Ui.DIM : Ui.TEXT);
        logView.setTextIsSelectable(true);
        logView.setLineSpacing(Ui.dp(activity, 2), 1f);
        card.addView(logView);

        LinearLayout logActions = Ui.row(activity);
        logActions.setPadding(0, Ui.dp(activity, 8), 0, 0);
        Button copyLog = Ui.action(activity, "COPY LOG", false);
        copyLog.setOnClickListener(v -> Actions.copyOrSay(activity, "probe log", logText()));
        logActions.addView(copyLog);

        Button clearLog = Ui.action(activity, "CLEAR", false);
        clearLog.setTextColor(Ui.DIM);
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        clearParams.leftMargin = Ui.dp(activity, 8);
        clearLog.setLayoutParams(clearParams);
        clearLog.setOnClickListener(v -> {
            synchronized (usbLog) {
                usbLog.clear();
            }
            ++logVersion;
            signature = "";
            refresh();
        });
        logActions.addView(clearLog);
        card.addView(logActions);

        Ui.space(activity, card, 10);
        return card;
    }

    /** One handshake line from the USB thread; the view is rebuilt on the main one. */
    private void trace(String text) {
        activity.runOnUiThread(() -> {
            synchronized (usbLog) {
                usbLog.add(clock.format(new Date()) + "  " + text);
                while (usbLog.size() > LOG_MAX) usbLog.remove(0);
            }
            ++logVersion;
            signature = "";
            refresh();
        });
    }

    private String logText() {
        final StringBuilder all = new StringBuilder();
        synchronized (usbLog) {
            for (String line : usbLog) {
                if (all.length() > 0) all.append('\n');
                all.append(line);
            }
        }
        return all.toString();
    }

    private void detectUsb() {
        final UsbManager manager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
        if (manager == null) {
            setUsbStatus("This phone has no USB host support.", Ui.PINK);
            return;
        }
        final UsbDevice device = UsbSerialCh340.find(manager);
        if (device == null) {
            setUsbStatus("No CH340 on the USB bus. Check the OTG adapter and the cable.", Ui.PINK);
            return;
        }
        if (!manager.hasPermission(device)) {
            setUsbStatus("Asking Android for the CH340…", Ui.ACCENT);
            requestUsbPermission(manager, device);
            return;
        }
        probeUsb(device);
    }

    private void requestUsbPermission(UsbManager manager, UsbDevice device) {
        registerUsbPermission();
        final Intent intent = new Intent(ACTION_USB_PERMISSION)
                .setPackage(activity.getPackageName());
        final int flags = PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        final PendingIntent pending = PendingIntent.getBroadcast(activity, 0, intent, flags);
        manager.requestPermission(device, pending);
    }

    private void registerUsbPermission() {
        final IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(usbPermissionReceiver, filter,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(usbPermissionReceiver, filter);
        }
    }

    private void unregisterUsbPermission() {
        try {
            activity.unregisterReceiver(usbPermissionReceiver);
        } catch (Exception ignored) {
            // Not registered, or already gone with the activity.
        }
    }

    /** Off the UI thread: USB I/O and the bootloader handshake both block. */
    private void probeUsb(UsbDevice device) {
        if (usbProbing || usbFlashing || monitorBusy()) return;
        usbProbing = true;
        final String label = describe(device);
        setUsbStatus("Probing " + label + "…", Ui.ACCENT);

        final UsbManager manager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
        new Thread(() -> {
            String message;
            int color;
            final UsbSerialCh340 port = new UsbSerialCh340(manager, device);
            final EspRom rom = new EspRom(port);
            rom.setTrace(BoardTab.this::trace);
            trace("== PROBE " + label + " · Android " + Build.VERSION.RELEASE
                    + " · " + device.getInterfaceCount() + " interface(s) ==");
            boolean opened = false;
            try {
                port.open();
                opened = true;
                trace("port opened, line coding and control lines set");
                final EspRom.Detected detected = rom.connect();
                message = detected.chip + " bootloader found · MAC " + detected.mac
                        + "\n" + label;
                if (detected.baud != 115200) {
                    message += "\nHandshake needed " + detected.baud + " baud.";
                }
                color = Ui.GOOD;
            } catch (Exception e) {
                final String reason = e.getMessage() == null ? e.toString() : e.getMessage();
                message = "Probe failed: " + reason;
                color = Ui.PINK;
                trace("failed: " + reason);
            }
            // The bootloader is a step of the probe, not a state to leave the board in.
            if (opened) {
                try {
                    rom.hardReset();
                    message += "\nBoard restarted into its firmware.";
                } catch (Exception e) {
                    message += "\nCould not restart it — unplug to boot it again.";
                    trace("reset failed: "
                            + (e.getMessage() == null ? e.toString() : e.getMessage()));
                }
            }
            port.close();
            trace("port closed");
            final String result = message;
            final int resultColor = color;
            activity.runOnUiThread(() -> {
                usbProbing = false;
                setUsbStatus(result, resultColor);
            });
        }, "usb-probe").start();
    }

    /** Boots what is in flash without entering the bootloader. */
    private void restartBoard() {
        if (usbProbing || usbFlashing || monitorBusy()) return;
        final UsbManager manager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
        final UsbDevice device = UsbSerialCh340.find(manager);
        if (manager == null || device == null) {
            setUsbStatus("No CH340 on the USB bus.", Ui.PINK);
            return;
        }
        setUsbStatus("Restarting the board…", Ui.ACCENT);
        new Thread(() -> {
            String message;
            int color;
            final UsbSerialCh340 port = new UsbSerialCh340(manager, device);
            final EspRom rom = new EspRom(port);
            rom.setTrace(BoardTab.this::trace);
            trace("== RESTART " + describe(device) + " ==");
            try {
                port.open();
                trace("port opened");
                rom.hardReset();
                message = "Board restarted.";
                color = Ui.GOOD;
            } catch (Exception e) {
                final String reason = e.getMessage() == null ? e.toString() : e.getMessage();
                message = "Restart failed: " + reason;
                color = Ui.PINK;
                trace("failed: " + reason);
            } finally {
                port.close();
                trace("port closed");
            }
            final String result = message;
            final int resultColor = color;
            activity.runOnUiThread(() -> setUsbStatus(result, resultColor));
        }, "usb-restart").start();
    }

    /** Confirms, then writes the image at 0x0 — the merged-image offset. */
    private void flashBinary(final FlashLibrary.Entry entry) {
        if (usbProbing || usbFlashing || monitorBusy()) return;
        final byte[] image = FlashLibrary.readBytes(activity, entry);
        if (image == null || image.length == 0) {
            Actions.toast(activity, "Could not read " + entry.name);
            return;
        }
        final UsbManager manager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
        final UsbDevice device = UsbSerialCh340.find(manager);
        if (device == null) {
            setUsbStatus("No CH340 on the USB bus.", Ui.PINK);
            return;
        }

        final int eraseKb = ((image.length + 4095) / 4096) * 4;
        new AlertDialog.Builder(activity)
                .setTitle("Flash " + entry.name)
                .setMessage(Ui.size(image.length) + " at 0x0, after erasing " + eraseKb
                        + " kB, then checked by MD5.\n\n0x0 expects a merged image. Do not "
                        + "unplug while it runs.")
                .setPositiveButton("FLASH",
                        (dialog, which) -> startFlash(device, entry.name, image))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startFlash(UsbDevice device, String name, byte[] image) {
        final UsbManager manager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
        usbFlashing = true;
        usbPercentShown = -1;
        setUsbStatus("Flashing " + name + "…", Ui.ACCENT);

        new Thread(() -> {
            String message;
            int color;
            final UsbSerialCh340 port = new UsbSerialCh340(manager, device);
            final EspRom rom = new EspRom(port);
            rom.setTrace(BoardTab.this::trace);
            trace("== FLASH " + name + " · " + Ui.size(image.length) + " ==");
            try {
                port.open();
                trace("port opened");
                rom.connect();
                rom.flash(0, image, this::reportProgress);
                message = name + " written and verified. The board rebooted into it.";
                color = Ui.GOOD;
            } catch (Exception e) {
                final String reason = e.getMessage() == null ? e.toString() : e.getMessage();
                message = "Flash failed: " + reason
                        + "\nTap RESTART BOARD to boot what is still there.";
                color = Ui.PINK;
                trace("failed: " + reason);
            } finally {
                port.close();
                trace("port closed");
            }
            final String result = message;
            final int resultColor = color;
            activity.runOnUiThread(() -> {
                usbFlashing = false;
                setUsbStatus(result, resultColor);
            });
        }, "usb-flash").start();
    }

    /** Called from the flash thread; throttled so the tab is not rebuilt per block. */
    private void reportProgress(int percent, String note) {
        if (percent / 5 == usbPercentShown) return;
        usbPercentShown = percent / 5;
        activity.runOnUiThread(() -> {
            usbStatus = percent + "%  ·  " + note;
            usbStatusColor = Ui.ACCENT;
            signature = "";
            refresh();
        });
    }

    private void setUsbStatus(String message, int color) {
        usbStatus = message;
        usbStatusColor = color;
        signature = "";
        refresh();
    }

    private static String describe(UsbDevice device) {
        return String.format(Locale.US, "CH340 %04x:%04x",
                device.getVendorId(), device.getProductId());
    }

    private LinearLayout entryCard(FlashLibrary.Entry entry) {
        LinearLayout card = Ui.card(activity);

        TextView name = Ui.text(activity, entry.name, 15, Ui.TEXT);
        card.addView(name);

        TextView size = Ui.text(activity, Ui.size(entry.size) + "  ·  "
                + (entry.textual() ? "text" : "binary"), 12, Ui.DIM);
        size.setPadding(0, Ui.dp(activity, 2), 0, 0);
        card.addView(size);

        LinearLayout actions = Ui.row(activity);
        actions.setPadding(0, Ui.dp(activity, 10), 0, 0);

        if (entry.textual()) {
            Button copy = Ui.action(activity, "COPY", true);
            copy.setOnClickListener(v -> Actions.copyOrSay(activity, entry.name,
                    FlashLibrary.read(activity, entry)));
            actions.addView(copy);
        }

        Button save = Ui.action(activity, "SAVE", !entry.textual());
        save.setOnClickListener(v -> Actions.save(activity, entry));
        actions.addView(save);

        if (!entry.textual()) {
            Button flash = Ui.action(activity, "FLASH", false);
            flash.setTextColor(Ui.ACCENT);
            flash.setOnClickListener(v -> flashBinary(entry));
            actions.addView(flash);
        }

        Button delete = Ui.action(activity, "DELETE", false);
        delete.setTextColor(Ui.PINK);
        delete.setOnClickListener(v -> {
            if (FlashLibrary.delete(activity, entry)) {
                Actions.toast(activity, "Deleted");
                signature = "";
                refresh();
            } else {
                Actions.toast(activity, "Could not delete");
            }
        });
        actions.addView(delete);

        card.addView(actions);
        Ui.space(activity, card, 10);
        return card;
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == VISIBLE) {
            refresh();
        } else if (monitorOpen) {
            // One owner of the USB handle: leaving the tab hands the cable back.
            stopMonitor("Monitor stopped: leaving the tab frees the cable.");
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        monitorRunning = false;
        monitorOpen = false;
        super.onDetachedFromWindow();
    }
}
