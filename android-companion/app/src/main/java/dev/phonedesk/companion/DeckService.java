package dev.phonedesk.companion;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

/**
 * Keeps a BLE link to a CYD "Phone Deck", pushes time, battery and
 * notifications into its Phone Link service, and exposes a snapshot of what it
 * sees so the Decks tab can monitor it.
 *
 * Runs in the foreground so the scan and connection survive the screen turning
 * off. GATT operations are queued because Android allows only one outstanding
 * request at a time.
 */
public class DeckService extends Service {

    private static final String TAG = "PhoneDeck";

    // Must match the UUIDs in phone_deck_cyd24R.ino.
    static final UUID SERVICE_UUID = UUID.fromString("6f2d0001-9a3c-4b57-9d21-6a1e0c4f8b10");
    static final UUID TIME_UUID = UUID.fromString("6f2d0002-9a3c-4b57-9d21-6a1e0c4f8b10");
    static final UUID BATTERY_UUID = UUID.fromString("6f2d0003-9a3c-4b57-9d21-6a1e0c4f8b10");
    static final UUID NOTICE_UUID = UUID.fromString("6f2d0004-9a3c-4b57-9d21-6a1e0c4f8b10");
    /** Read-only: the identity document. Missing on a deck flashed before it existed. */
    static final UUID IDENTITY_UUID = UUID.fromString("6f2d0005-9a3c-4b57-9d21-6a1e0c4f8b10");
    /** What the phone is playing, mirrored. Also optional: an older flash has no such line. */
    static final UUID NOW_UUID = UUID.fromString("6f2d0006-9a3c-4b57-9d21-6a1e0c4f8b10");
    /**
     * Deck to phone: one notify per transport tap, {@code <serial>|<action>}.
     * Optional like the mirror — a deck flashed before its chips asked the phone
     * has no such characteristic, and such a deck still links.
     */
    static final UUID COMMAND_UUID = UUID.fromString("6f2d0007-9a3c-4b57-9d21-6a1e0c4f8b10");
    /** The client-characteristic-configuration descriptor every notify carries. */
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /**
     * The deck's identity document is ~150 bytes, so the default 23-byte ATT MTU
     * would return it truncated. 185 is the smallest size both ends are required
     * to support above the default.
     */
    private static final int MTU_BYTES = 185;

    private static final String CHANNEL_ID = "deck_link";
    private static final int FOREGROUND_ID = 7;

    private static final long TICK_MS = 30_000L;
    private static final long SCAN_TIMEOUT_MS = 15_000L;
    private static final long RSSI_POLL_MS = 10_000L;
    private static final long NEARBY_TTL_MS = 30_000L;
    private static final int NEARBY_MAX = 12;
    private static final int NOTICE_LIMIT_BYTES = 120;

    private static volatile DeckService instance;
    private static volatile String status = "Stopped";

    /** A deck seen advertising nearby. */
    public static final class Nearby {
        public final String mac;
        public final String name;
        public final int rssi;
        public final long seenMs;

        Nearby(String mac, String name, int rssi, long seenMs) {
            this.mac = mac;
            this.name = name;
            this.rssi = rssi;
            this.seenMs = seenMs;
        }
    }

    /** Immutable-enough copy of the link state for the UI to read each second. */
    public static final class Snapshot {
        public boolean running;
        public boolean linked;
        public String status = "Stopped";
        public String deviceName = "";
        public String deviceMac = "";
        /** {@code phone_deck_cyd24R-A1B2C3}, or "" until the deck names itself. */
        public String deviceId = "";
        public String deviceTag = "";
        public int rssi;
        public int writes;
        public int notices;
        public long connectedAtMs;
        public long lastWriteMs;
        public List<Nearby> nearby = new ArrayList<>();
    }

    public static String getStatus() {
        return status;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public static Snapshot snapshot() {
        DeckService svc = instance;
        return svc == null ? new Snapshot() : svc.buildSnapshot();
    }

    public static void forwardNotification(String app, String title, String body) {
        final DeckService svc = instance;
        if (svc != null) svc.enqueueNotice(app, title, body);
    }

    /** A now-playing line from {@link NowPlaying}; dropped when the deck has no characteristic. */
    public static void publishNowPlaying(String packet) {
        final DeckService svc = instance;
        if (svc != null) svc.enqueueNowPlaying(packet);
    }

    /** User picked a deck in the monitor; remember it and link to it now. */
    public static void connectTo(String mac) {
        DeckService svc = instance;
        if (svc != null) svc.connectManually(mac);
    }

    /**
     * User unlinked a deck: drop the link and the preference, and stop linking by
     * itself. The scan keeps running, so a LINK tap is always one row away.
     */
    public static void unlink(Context context, String mac) {
        DeckService svc = instance;
        if (svc != null) {
            svc.unlinkDeck(mac);
        } else if (mac != null && !mac.isEmpty()) {
            DeckStore.setPreferred(context, "");
        }
    }

    public static boolean notificationAccess(Context context) {
        String flat = Settings.Secure.getString(context.getContentResolver(),
                "enabled_notification_listeners");
        if (flat == null) return false;
        ComponentName mine = new ComponentName(context, DeckNotificationListener.class);
        for (String entry : flat.split(":")) {
            if (mine.equals(ComponentName.unflattenFromString(entry))) return true;
        }
        return false;
    }

    public static boolean bluetoothReady(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static void start(Context context) {
        if (!bluetoothReady(context)) return;
        Intent intent = new Intent(context, DeckService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, DeckService.class));
    }

    /**
     * One queued GATT operation: a characteristic value, or the descriptor write
     * that subscribes to one. Both share the queue because Android allows one
     * outstanding request at a time whichever kind it is.
     */
    private static final class Write {
        final BluetoothGattCharacteristic characteristic;
        final BluetoothGattDescriptor descriptor;
        final byte[] data;

        Write(BluetoothGattCharacteristic characteristic, BluetoothGattDescriptor descriptor,
              byte[] data) {
            this.characteristic = characteristic;
            this.descriptor = descriptor;
            this.data = data;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Queue<Write> writes = new ArrayDeque<>();
    private final Map<String, Nearby> nearby = new LinkedHashMap<>();

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic timeChar;
    private BluetoothGattCharacteristic batteryChar;
    private BluetoothGattCharacteristic noticeChar;
    private BluetoothGattCharacteristic identityChar;
    private BluetoothGattCharacteristic nowChar;
    private BluetoothGattCharacteristic commandChar;

    private boolean scanning;
    private boolean connecting;
    private boolean writing;

    /**
     * Set by UNLINK: keep the scan (so NEARBY stays honest) but never link on my
     * own until LINK is tapped. Without it, unlinking a deck in range links it
     * straight back in the next scan and the row the user just forgot returns.
     */
    private volatile boolean paused;

    private volatile String preferredMac = "";
    private volatile String linkedMac = "";
    private volatile String linkedName = "";
    private volatile String linkedId = "";
    private volatile String linkedTag = "";
    private volatile int linkedRssi;
    private volatile long connectedAtMs;
    private volatile long lastWriteMs;
    private volatile int writesSent;
    private volatile int noticesForwarded;

    private String lastNotice = "";
    private long lastNoticeMs;

    /** The line last written to a deck, so an unchanged second is not re-sent. */
    private volatile String lastNow = "";
    private NowPlaying nowPlaying;
    /** The last tap this link ran, so a repeated notify is not a second tap. */
    private long lastCommandSerial;

    private final Runnable scanTimeout = () -> {
        stopScan();
        setStatus("Deck not found, retrying");
        scheduleScan(3000);
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            pushTime();
            pushBattery();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private final Runnable rssiPoll = new Runnable() {
        @Override
        public void run() {
            if (gatt != null) {
                try {
                    gatt.readRemoteRssi();
                } catch (SecurityException ignored) {
                    // Permission revoked mid-link; the next scan will notice.
                }
            }
            handler.postDelayed(this, RSSI_POLL_MS);
        }
    };

    /** One bond attempt per link, and no bond removal more than once a minute. */
    private long lastBondForgetMs;
    private boolean bondAsked;

    private final Runnable writeTimeout = () -> {
        if (writing) {
            writing = false;
            writeNext();
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            handler.post(() -> handleScanResult(result));
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            if (results == null) return;
            for (ScanResult result : results) handler.post(() -> handleScanResult(result));
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            setStatus("Scan failed (" + errorCode + ")");
            scheduleScan(5000);
        }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int statusCode, int newState) {
            connecting = false;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                bondAsked = false;
                setStatus("Discovering services");
                try {
                    // Ask for the larger MTU before the services are up: at the
                    // default the identity read comes back cut short and names no
                    // widget, so the deck would be filed under its MAC.
                    g.requestMtu(MTU_BYTES);
                    g.discoverServices();
                } catch (SecurityException e) {
                    setStatus("Bluetooth permission needed");
                    releaseGatt();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                BluetoothDevice device = g.getDevice();
                boolean staleBond = BondAdvice.FORGET.equals(bondAdvice(statusCode, bondState(device)))
                        && System.currentTimeMillis() - lastBondForgetMs > 60000;
                releaseGatt();
                if (staleBond) {
                    // A bond left by an older build makes the stack refuse the link
                    // with 133, and rescanning never clears it. Android keeps
                    // removeBond() out of the public API, so this asks for it by
                    // reflection and reports whether it worked.
                    lastBondForgetMs = System.currentTimeMillis();
                    setStatus(forgetBond(device) ? "Stale pairing forgotten, retrying"
                                                 : "Stale pairing could not be removed");
                    scheduleScan(3000);
                    return;
                }
                setStatus("Disconnected, scanning");
                scheduleScan(2000);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int statusCode) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                releaseGatt();
                scheduleScan(2000);
                return;
            }
            BluetoothGattService service = g.getService(SERVICE_UUID);
            if (service == null) {
                setStatus("Deck service missing");
                releaseGatt();
                scheduleScan(4000);
                return;
            }
            timeChar = service.getCharacteristic(TIME_UUID);
            batteryChar = service.getCharacteristic(BATTERY_UUID);
            noticeChar = service.getCharacteristic(NOTICE_UUID);
            // Optional: a deck flashed before the document existed still links,
            // and is remembered under its address instead.
            identityChar = service.getCharacteristic(IDENTITY_UUID);
            nowChar = service.getCharacteristic(NOW_UUID);
            commandChar = service.getCharacteristic(COMMAND_UUID);
            if (timeChar == null || batteryChar == null || noticeChar == null) {
                setStatus("Deck characteristics missing");
                releaseGatt();
                scheduleScan(4000);
                return;
            }
            linkedMac = g.getDevice().getAddress();
            linkedName = safeName(g.getDevice());
            linkedId = "";
            linkedTag = "";
            linkedRssi = 0;
            connectedAtMs = System.currentTimeMillis();
            if (preferredMac == null || preferredMac.isEmpty()) {
                preferredMac = linkedMac;
                DeckStore.setPreferred(DeckService.this, linkedMac);
            }
            readIdentity();
            setStatus("Linked to " + linkedName);
            try {
                g.readRemoteRssi();
            } catch (SecurityException ignored) {
                // Optional telemetry only.
            }
            pushTime();
            pushBattery();
            // The mirror only runs while a deck that can take it is linked: it
            // costs a media-session read a second, and there is nothing to send
            // it to otherwise.
            if (nowChar != null) {
                if (nowPlaying == null) nowPlaying = new NowPlaying(DeckService.this);
                nowPlaying.start();
            }
            // The other direction: a tap on the deck's chips. Subscribed here
            // because a notify nothing listens for is dropped without a trace.
            if (commandChar != null) subscribeCommands();
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt g, int rssi, int statusCode) {
            if (statusCode == BluetoothGatt.GATT_SUCCESS) linkedRssi = rssi;
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch,
                                          int statusCode) {
            // A write refused for authentication is the one failure another queue
            // entry cannot fix: the link needs a bond first. Ask once, then let the
            // 1 Hz mirror re-send what this one dropped.
            if (BondAdvice.PAIR.equals(bondAdvice(statusCode, bondState(g.getDevice())))) {
                askForBond(g.getDevice());
            }
            handler.removeCallbacks(writeTimeout);
            if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                ++writesSent;
                lastWriteMs = System.currentTimeMillis();
            }
            writing = false;
            writeNext();
        }

        /** API 33 and later: the stack hands the value over instead of storing it. */
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch,
                                            byte[] value) {
            if (ch == null || !COMMAND_UUID.equals(ch.getUuid())) return;
            handleCommand(value);
        }

        /** API 32 and earlier, where the value lives on the characteristic. */
        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            if (ch == null || !COMMAND_UUID.equals(ch.getUuid())) return;
            handleCommand(ch.getValue());
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor,
                                      int statusCode) {
            // A descriptor write is a queued operation like any other, so the queue
            // only learns it finished here.
            handler.removeCallbacks(writeTimeout);
            writing = false;
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "commands: subscription refused (" + statusCode + ")");
            }
            writeNext();
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int statusCode) {
            // The first read can land before the MTU is agreed and come back
            // truncated, which names no widget. This is the second ask, once the
            // link carries the whole document.
            if (linkedId.isEmpty()) readIdentity();
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic ch,
                                         int statusCode) {
            if (ch == null || !IDENTITY_UUID.equals(ch.getUuid())) return;
            final byte[] raw = statusCode == BluetoothGatt.GATT_SUCCESS ? ch.getValue() : null;
            rememberIdentity(DeckIdentity.parse(
                    raw == null ? "" : new String(raw, StandardCharsets.UTF_8)));
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        paused = false;
        preferredMac = DeckStore.preferred(this);
        createChannel();
        startForeground(FOREGROUND_ID, buildForegroundNotification());
        setStatus("Starting");
        handler.post(tick);
        handler.postDelayed(rssiPoll, RSSI_POLL_MS);
        startScan();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startScan();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        stopScan();
        releaseGatt();
        status = "Stopped";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------ snapshot ---

    private Snapshot buildSnapshot() {
        Snapshot snapshot = new Snapshot();
        snapshot.running = true;
        snapshot.linked = gatt != null && timeChar != null;
        snapshot.status = status;
        snapshot.deviceName = linkedName;
        snapshot.deviceMac = linkedMac;
        snapshot.deviceId = linkedId;
        snapshot.deviceTag = linkedTag;
        snapshot.rssi = linkedRssi;
        snapshot.writes = writesSent;
        snapshot.notices = noticesForwarded;
        snapshot.connectedAtMs = connectedAtMs;
        snapshot.lastWriteMs = lastWriteMs;

        long now = System.currentTimeMillis();
        List<Nearby> list = new ArrayList<>();
        synchronized (nearby) {
            for (Map.Entry<String, Nearby> entry : nearby.entrySet()) {
                Nearby value = entry.getValue();
                if (now - value.seenMs <= NEARBY_TTL_MS) list.add(value);
            }
        }
        Collections.sort(list, new Comparator<Nearby>() {
            @Override
            public int compare(Nearby a, Nearby b) {
                return Integer.compare(b.rssi, a.rssi);
            }
        });
        snapshot.nearby = list;
        return snapshot;
    }

    private void recordNearby(ScanResult result) {
        String mac = result.getDevice().getAddress();
        String name = nameOf(result);
        synchronized (nearby) {
            nearby.remove(mac);
            nearby.put(mac, new Nearby(mac, name, result.getRssi(), System.currentTimeMillis()));
            while (nearby.size() > NEARBY_MAX) {
                String oldest = nearby.keySet().iterator().next();
                nearby.remove(oldest);
            }
        }
    }

    // ---------------------------------------------------------- permissions ---

    private boolean canScan() {
        return bluetoothReady(this);
    }

    private boolean canConnect() {
        return bluetoothReady(this);
    }

    // ----------------------------------------------------------------- scan ---

    private void startScan() {
        if (gatt != null || scanning || connecting) return;
        if (!canScan()) {
            setStatus("Bluetooth permission needed");
            return;
        }

        BluetoothManager manager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) {
            setStatus("No Bluetooth adapter");
            return;
        }
        if (!adapter.isEnabled()) {
            setStatus("Bluetooth is off");
            scheduleScan(3000);
            return;
        }

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            setStatus("Scanner unavailable");
            scheduleScan(3000);
            return;
        }

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        try {
            scanner.startScan(null, settings, scanCallback);
            scanning = true;
            setStatus("Scanning for decks");
            handler.removeCallbacks(scanTimeout);
            handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS);
        } catch (SecurityException e) {
            setStatus("Bluetooth permission needed");
        }
    }

    private void stopScan() {
        handler.removeCallbacks(scanTimeout);
        if (!scanning) return;
        scanning = false;
        try {
            if (scanner != null) scanner.stopScan(scanCallback);
        } catch (Exception ignored) {
            // Adapter turned off mid-scan.
        }
    }

    private void scheduleScan(long delayMs) {
        handler.postDelayed(() -> {
            if (gatt == null) startScan();
        }, delayMs);
    }

    private void handleScanResult(ScanResult result) {
        if (result == null) return;
        if (!looksLikeDeck(result)) return;
        recordNearby(result);
        if (paused) return;
        if (gatt != null || connecting) return;

        String mac = result.getDevice().getAddress();
        String wanted = preferredMac == null ? "" : preferredMac;
        if (!wanted.isEmpty()) {
            // A chosen deck wins; do not silently link to another board.
            if (mac.equalsIgnoreCase(wanted)) {
                stopScan();
                connect(result.getDevice());
            }
            return;
        }
        stopScan();
        connect(result.getDevice());
    }

    private String nameOf(ScanResult result) {
        String name = null;
        if (canConnect()) {
            try {
                name = result.getDevice().getName();
            } catch (SecurityException ignored) {
                // Fall through to the advertised name.
            }
        }
        ScanRecord record = result.getScanRecord();
        if ((name == null || name.isEmpty()) && record != null) name = record.getDeviceName();
        if (name == null || name.isEmpty()) {
            for (DeckStore.Known known : DeckStore.all(this)) {
                if (known.mac.equalsIgnoreCase(result.getDevice().getAddress())) return known.name;
            }
            name = "Phone Deck";
        }
        return name;
    }

    /**
     * A deck is any widget its descriptor claims: the Phone Link service UUID (kept
     * as the legacy fallback) or one of the name fragments it advertises.
     */
    private boolean looksLikeDeck(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        if (record != null && record.getServiceUuids() != null) {
            List<UUID> advertised = WidgetCatalog.services(this);
            for (ParcelUuid uuid : record.getServiceUuids()) {
                if (SERVICE_UUID.equals(uuid.getUuid())) return true;
                if (advertised.contains(uuid.getUuid())) return true;
            }
        }

        String name = null;
        if (canConnect()) {
            try {
                name = result.getDevice().getName();
            } catch (SecurityException ignored) {
                // Fall back to the advertised name below.
            }
        }
        if (name == null && record != null) name = record.getDeviceName();
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        if (lower.contains("phone deck")) return true;
        for (String fragment : WidgetCatalog.nameMatches(this)) {
            if (lower.contains(fragment)) return true;
        }
        return false;
    }

    // --------------------------------------------------------------- gatt ---

    private void connectManually(String mac) {
        if (mac == null || mac.isEmpty()) return;
        paused = false;
        preferredMac = mac;
        DeckStore.setPreferred(this, mac);

        if (!canConnect()) {
            setStatus("Bluetooth permission needed");
            return;
        }

        stopScan();
        releaseGatt();
        try {
            BluetoothManager manager =
                    (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter bluetooth = manager == null ? null : manager.getAdapter();
            if (bluetooth == null) {
                setStatus("No Bluetooth adapter");
                return;
            }
            connect(bluetooth.getRemoteDevice(mac));
        } catch (IllegalArgumentException e) {
            setStatus("Unknown deck address");
        } catch (SecurityException e) {
            setStatus("Bluetooth permission needed");
        }
    }

    /** Drops the link to one board, if that is the board the link is holding. */
    private void unlinkDeck(String mac) {
        if (mac == null || mac.isEmpty() || !mac.equalsIgnoreCase(linkedMac)) return;
        paused = true;
        preferredMac = "";
        DeckStore.setPreferred(this, "");
        stopScan();
        releaseGatt();
        setStatus("Unlinked \u2014 tap LINK to connect a deck");
    }

    /** The platform's values, decided in {@link BondAdvice}, which the host suite can run. */
    static String bondAdvice(int statusCode, int bondState) {
        return BondAdvice.forFailure(statusCode, bondState);
    }

    /** The bond state, or BOND_NONE when the permission to read it is missing. */
    private static int bondState(BluetoothDevice device) {
        if (device == null) return BluetoothDevice.BOND_NONE;
        try {
            return device.getBondState();
        } catch (SecurityException e) {
            return BluetoothDevice.BOND_NONE;
        }
    }

    /** Drops a bond this app did not make, so the deck can be reached again. */
    private boolean forgetBond(BluetoothDevice device) {
        if (device == null || !canConnect()) return false;
        try {
            Object done = device.getClass().getMethod("removeBond").invoke(device);
            return !(done instanceof Boolean) || Boolean.TRUE.equals(done);
        } catch (Exception e) {
            Log.i(TAG, "bond: could not remove it: " + e);
            return false;
        }
    }

    /** A refused write needs a bond; ask once per connection, never in a loop. */
    private void askForBond(BluetoothDevice device) {
        if (bondAsked || device == null || !canConnect()) return;
        bondAsked = true;
        try {
            if (bondState(device) == BluetoothDevice.BOND_BONDED) return;
            if (device.createBond()) setStatus("Pairing to write");
        } catch (SecurityException e) {
            setStatus("Bluetooth permission needed");
        }
    }

    private void connect(BluetoothDevice device) {
        if (!canConnect()) {
            setStatus("Bluetooth permission needed");
            return;
        }
        connecting = true;
        setStatus("Connecting to " + safeName(device));
        try {
            gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        } catch (SecurityException e) {
            connecting = false;
            gatt = null;
            setStatus("Bluetooth permission needed");
            scheduleScan(3000);
        }
    }

    private String safeName(BluetoothDevice device) {
        if (!canConnect()) return "Phone Deck";
        try {
            String name = device.getName();
            return name == null || name.isEmpty() ? "Phone Deck" : name;
        } catch (SecurityException e) {
            return "Phone Deck";
        }
    }

    /**
     * Asks the deck who it is. No characteristic (an older flash) or a failed read
     * still records it by address, so the deck appears in SAVED DECKS and is
     * upgraded to its id the next time it answers.
     */
    private void readIdentity() {
        if (gatt == null || identityChar == null) {
            rememberIdentity(null);
            return;
        }
        try {
            if (!gatt.readCharacteristic(identityChar)) rememberIdentity(null);
        } catch (SecurityException e) {
            rememberIdentity(null);
        }
    }

    private void rememberIdentity(DeckIdentity identity) {
        if (linkedMac.isEmpty()) return;
        linkedId = identity == null ? "" : identity.id;
        linkedTag = identity == null ? "" : identity.tag;
        DeckStore.remember(this, identity, linkedMac, linkedName);
        if (identity != null && identity.usable()) {
            final String label = identity.label.isEmpty() ? linkedName : identity.label;
            setStatus("Linked to " + label);
        }
    }

    private void releaseGatt() {
        handler.removeCallbacks(writeTimeout);
        BluetoothGatt old = gatt;
        gatt = null;
        timeChar = null;
        batteryChar = null;
        noticeChar = null;
        identityChar = null;
        nowChar = null;
        commandChar = null;
        // A rebooted deck starts its counter again at 1, so a new link forgets the
        // last one it ran rather than judging the first tap a repeat.
        lastCommandSerial = 0;
        lastNow = "";
        if (nowPlaying != null) nowPlaying.stop();
        linkedMac = "";
        linkedName = "";
        linkedId = "";
        linkedTag = "";
        linkedRssi = 0;
        connectedAtMs = 0;
        writes.clear();
        writing = false;
        if (old != null) {
            try {
                old.close();
            } catch (Exception ignored) {
                // Already gone.
            }
        }
    }

    // ------------------------------------------------------------- payloads ---

    private void pushTime() {
        if (timeChar == null) return;
        Calendar now = Calendar.getInstance();
        byte[] payload = new byte[] {
                (byte) (now.get(Calendar.YEAR) - 2000),
                (byte) (now.get(Calendar.MONTH) + 1),
                (byte) now.get(Calendar.DAY_OF_MONTH),
                (byte) now.get(Calendar.HOUR_OF_DAY),
                (byte) now.get(Calendar.MINUTE),
                (byte) now.get(Calendar.SECOND),
                (byte) now.get(Calendar.DAY_OF_WEEK)
        };
        enqueue(timeChar, payload);
    }

    private void pushBattery() {
        if (batteryChar == null) return;

        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = 0;
        int scale = 100;
        int state = BatteryManager.BATTERY_STATUS_UNKNOWN;
        if (battery != null) {
            level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            state = battery.getIntExtra(BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN);
        }

        int percent = scale > 0 ? Math.round(level * 100f / scale) : 0;
        percent = Math.max(0, Math.min(100, percent));
        boolean charging = state == BatteryManager.BATTERY_STATUS_CHARGING
                || state == BatteryManager.BATTERY_STATUS_FULL;

        enqueue(batteryChar, new byte[] { (byte) percent, (byte) (charging ? 1 : 0) });
    }

    private void enqueueNotice(String app, String title, String body) {
        String appText = clean(app);
        String titleText = clean(title);
        String bodyText = clean(body);
        if (titleText.isEmpty() && bodyText.isEmpty()) return;
        if (appText.isEmpty()) appText = "Phone";

        String signature = appText + '\u0001' + titleText + '\u0001' + bodyText;
        long now = System.currentTimeMillis();
        if (signature.equals(lastNotice) && now - lastNoticeMs < 5000) return;
        lastNotice = signature;
        lastNoticeMs = now;

        if (noticeChar == null) return;  // not linked yet; nothing to write to
        byte[] payload = clamp(
                (appText + '|' + titleText + '|' + bodyText).getBytes(StandardCharsets.UTF_8),
                NOTICE_LIMIT_BYTES);
        if (enqueue(noticeChar, payload)) ++noticesForwarded;
    }

    private void enqueueNowPlaying(String packet) {
        if (nowChar == null || packet == null || packet.isEmpty()) return;
        if (packet.equals(lastNow)) return;
        lastNow = packet;
        // NowPacket keeps the line inside one write already; the clamp is the
        // belt to that braces, because a broken line is worse than a short one.
        enqueue(nowChar, clamp(packet.getBytes(StandardCharsets.UTF_8), 150));
    }

    private static String clean(String value) {
        if (value == null) return "";
        String flat = value.replace('\n', ' ').replace('\r', ' ').replace('|', '/');
        flat = flat.replaceAll("\\s+", " ").trim();
        return flat;
    }

    private static byte[] clamp(byte[] raw, int maxBytes) {
        if (raw.length <= maxBytes) return raw;
        int end = maxBytes;
        while (end > 0 && (raw[end] & 0xC0) == 0x80) --end;
        byte[] cut = new byte[end];
        System.arraycopy(raw, 0, cut, 0, end);
        return cut;
    }

    // -------------------------------------------------------------- writes ---

    private boolean enqueue(BluetoothGattCharacteristic characteristic, byte[] data) {
        if (characteristic == null || gatt == null) return false;
        if (writes.size() > 8) writes.clear();
        writes.add(new Write(characteristic, null, data));
        writeNext();
        return true;
    }

    private boolean enqueue(BluetoothGattDescriptor descriptor, byte[] data) {
        if (descriptor == null || gatt == null) return false;
        if (writes.size() > 8) writes.clear();
        writes.add(new Write(null, descriptor, data));
        writeNext();
        return true;
    }

    private void writeNext() {
        if (writing || gatt == null) return;
        Write next = writes.poll();
        if (next == null) return;

        writing = true;
        handler.removeCallbacks(writeTimeout);
        handler.postDelayed(writeTimeout, 1500);

        try {
            if (next.descriptor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(next.descriptor, next.data);
                } else {
                    next.descriptor.setValue(next.data);
                    if (!gatt.writeDescriptor(next.descriptor)) {
                        handler.removeCallbacks(writeTimeout);
                        writing = false;
                        handler.postDelayed(this::writeNext, 60);
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(next.characteristic, next.data,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            } else {
                next.characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                next.characteristic.setValue(next.data);
                if (!gatt.writeCharacteristic(next.characteristic)) {
                    handler.removeCallbacks(writeTimeout);
                    writing = false;
                    handler.postDelayed(this::writeNext, 60);
                }
            }
        } catch (SecurityException e) {
            handler.removeCallbacks(writeTimeout);
            writing = false;
        }
    }

    // --------------------------------------------------------------- tapes ---

    /**
     * Turns the deck's chips into work. Two halves, and both are needed: the local
     * subscription so the stack accepts the notify, then the CCCD write that tells
     * the board to send one. The write goes through the shared queue, because a
     * descriptor write is still a GATT request.
     */
    private void subscribeCommands() {
        if (gatt == null || commandChar == null) return;
        try {
            if (!gatt.setCharacteristicNotification(commandChar, true)) {
                setStatus("Linked, taps are silent");
                return;
            }
        } catch (SecurityException e) {
            setStatus("Bluetooth permission needed");
            return;
        }
        final BluetoothGattDescriptor cccd = commandChar.getDescriptor(CCCD_UUID);
        if (cccd == null) return;   // no descriptor, no way for the board to be told
        enqueue(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
    }

    /** Parses one tap and hands it to the main thread, where the session is read. */
    private void handleCommand(byte[] raw) {
        final LinkCommand command = LinkCommand.parse(raw);
        if (command == null) {
            Log.i(TAG, "commands: ignored " + (raw == null ? 0 : raw.length) + " bytes");
            return;
        }
        if (!LinkCommand.fresh(command, lastCommandSerial)) return;
        lastCommandSerial = command.serial;
        handler.post(() -> runCommand(command));
    }

    private void runCommand(LinkCommand command) {
        if (nowPlaying == null) nowPlaying = new NowPlaying(this);
        if (nowPlaying.run(command.action)) {
            Log.i(TAG, "commands: " + command.action + " on the phone's player");
        } else {
            // The deck lit the chip when it asked; nothing here writes back, so the
            // status line is the only way a person learns the tap landed nowhere.
            setStatus("Tap arrived, nothing to play");
        }
    }

    // --------------------------------------------------------------- windows ---

    private void createChannel() {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Deck link",
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildForegroundNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Phone Deck bridge")
                .setContentText("Keeping the deck linked")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    private void setStatus(String text) {
        status = text;
        Log.i(TAG, text);
    }
}
