package dev.phonedesk.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Board tab: the OTG link and the images that go on the board.
 *
 * Everything that talks to the ESP32 lives here — picking the target, probing the
 * ROM bootloader, restarting it, and writing an image — so the project shelf stays
 * about projects.
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
                .append('l').append(logVersion);
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

        body.addView(Ui.text(activity, "BOARD", 20, Ui.TEXT));
        TextView subtitle = Ui.text(activity,
                "Connect over OTG, then flash an image.", 12, Ui.DIM);
        subtitle.setPadding(0, Ui.dp(activity, 4), 0, 0);
        body.addView(subtitle);

        body.addView(boardPicker());
        body.addView(usbCard());

        body.addView(Ui.section(activity, "IMAGES"));
        Button importButton = Ui.action(activity, "IMPORT A FILE", false);
        importButton.setOnClickListener(v -> startImport());
        Ui.space(activity, importButton, 6);
        body.addView(importButton);

        int imported = 0;
        for (FlashLibrary.Entry entry : entries) {
            if (entry.bundled) continue;
            ++imported;
            body.addView(entryCard(entry));
        }
        if (imported == 0) {
            body.addView(Ui.text(activity,
                    "No images yet. Import a .bin to flash it.", 12, Ui.DIM));
        }

        TextView hint = Ui.text(activity,
                "FLASH writes at 0x0, so use a merged image. An app-only .bin belongs at "
                        + "0x10000 and will not boot from 0x0.", 11, Ui.DIM);
        hint.setPadding(0, Ui.dp(activity, 14), 0, 0);
        hint.setLineSpacing(Ui.dp(activity, 2), 1f);
        body.addView(hint);
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
        if (usbProbing || usbFlashing) return;
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
        if (usbProbing || usbFlashing) return;
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
        if (usbProbing || usbFlashing) return;
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
        if (visibility == VISIBLE) refresh();
    }
}
