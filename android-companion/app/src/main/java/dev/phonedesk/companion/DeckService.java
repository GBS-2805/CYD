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

    // Must match the UUIDs in cyd_phone_deck.ino.
    static final UUID SERVICE_UUID = UUID.fromString("6f2d0001-9a3c-4b57-9d21-6a1e0c4f8b10");
    static final UUID TIME_UUID = UUID.fromString("6f2d0002-9a3c-4b57-9d21-6a1e0c4f8b10");
    static final UUID BATTERY_UUID = UUID.fromString("6f2d0003-9a3c-4b57-9d21-6a1e0c4f8b10");
    static final UUID NOTICE_UUID = UUID.fromString("6f2d0004-9a3c-4b57-9d21-6a1e0c4f8b10");

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

    /** User picked a deck in the monitor; remember it and link to it now. */
    public static void connectTo(String mac) {
        DeckService svc = instance;
        if (svc != null) svc.connectManually(mac);
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

    private static final class Write {
        final BluetoothGattCharacteristic characteristic;
        final byte[] data;

        Write(BluetoothGattCharacteristic characteristic, byte[] data) {
            this.characteristic = characteristic;
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

    private boolean scanning;
    private boolean connecting;
    private boolean writing;

    private volatile String preferredMac = "";
    private volatile String linkedMac = "";
    private volatile String linkedName = "";
    private volatile int linkedRssi;
    private volatile long connectedAtMs;
    private volatile long lastWriteMs;
    private volatile int writesSent;
    private volatile int noticesForwarded;

    private String lastNotice = "";
    private long lastNoticeMs;

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
                setStatus("Discovering services");
                try {
                    g.discoverServices();
                } catch (SecurityException e) {
                    setStatus("Bluetooth permission needed");
                    releaseGatt();
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                releaseGatt();
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
            if (timeChar == null || batteryChar == null || noticeChar == null) {
                setStatus("Deck characteristics missing");
                releaseGatt();
                scheduleScan(4000);
                return;
            }
            linkedMac = g.getDevice().getAddress();
            linkedName = safeName(g.getDevice());
            linkedRssi = 0;
            connectedAtMs = System.currentTimeMillis();
            if (preferredMac == null || preferredMac.isEmpty()) {
                preferredMac = linkedMac;
                DeckStore.setPreferred(DeckService.this, linkedMac);
            }
            DeckStore.remember(DeckService.this, linkedMac, linkedName);
            setStatus("Linked to " + linkedName);
            try {
                g.readRemoteRssi();
            } catch (SecurityException ignored) {
                // Optional telemetry only.
            }
            pushTime();
            pushBattery();
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt g, int rssi, int statusCode) {
            if (statusCode == BluetoothGatt.GATT_SUCCESS) linkedRssi = rssi;
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch,
                                          int statusCode) {
            handler.removeCallbacks(writeTimeout);
            if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                ++writesSent;
                lastWriteMs = System.currentTimeMillis();
            }
            writing = false;
            writeNext();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
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

    private boolean looksLikeDeck(ScanResult result) {
        ScanRecord record = result.getScanRecord();
        if (record != null && record.getServiceUuids() != null) {
            for (ParcelUuid uuid : record.getServiceUuids()) {
                if (SERVICE_UUID.equals(uuid.getUuid())) return true;
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
        return name.toLowerCase().contains("phone deck");
    }

    // --------------------------------------------------------------- gatt ---

    private void connectManually(String mac) {
        if (mac == null || mac.isEmpty()) return;
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

    private void releaseGatt() {
        handler.removeCallbacks(writeTimeout);
        BluetoothGatt old = gatt;
        gatt = null;
        timeChar = null;
        batteryChar = null;
        noticeChar = null;
        linkedMac = "";
        linkedName = "";
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
        writes.add(new Write(characteristic, data));
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
