package dev.phonedesk.companion;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.util.Arrays;

/**
 * Minimal CH340 / CH341A USB-to-serial driver: claim the port, program the line
 * coding and toggle DTR/RTS so the ESP32 can be reset into its ROM bootloader.
 *
 * The register sequence follows usb-serial-for-android's Ch34xSerialDriver
 * (Apache-2.0, Copyright 2014 Andreas Butti). It is kept in-tree instead of as a
 * dependency because this app otherwise resolves nothing but the Android SDK.
 */
final class UsbSerialCh340 {

    static final int VID_QINHENG = 0x1A86;
    static final int PID_CH340 = 0x7523;
    static final int PID_CH341A = 0x5523;

    private static final int TIMEOUT_MS = 2000;
    private static final int REQ_OUT = UsbConstants.USB_TYPE_VENDOR | UsbConstants.USB_DIR_OUT;
    private static final int REQ_IN = UsbConstants.USB_TYPE_VENDOR | UsbConstants.USB_DIR_IN;

    private static final int LCR_ENABLE_RX = 0x80;
    private static final int LCR_ENABLE_TX = 0x40;
    private static final int LCR_CS8 = 0x03;
    private static final int SCL_DTR = 0x20;
    private static final int SCL_RTS = 0x40;

    private final UsbManager manager;
    private final UsbDevice device;

    private UsbDeviceConnection connection;
    private UsbEndpoint endpointIn;
    private UsbEndpoint endpointOut;
    private boolean dtr;
    private boolean rts;

    UsbSerialCh340(UsbManager manager, UsbDevice device) {
        this.manager = manager;
        this.device = device;
    }

    static boolean isSupported(UsbDevice device) {
        if (device == null || device.getVendorId() != VID_QINHENG) return false;
        final int productId = device.getProductId();
        return productId == PID_CH340 || productId == PID_CH341A;
    }

    /** First CH340-family device on the bus, or null when nothing is attached. */
    static UsbDevice find(UsbManager manager) {
        if (manager == null) return null;
        for (UsbDevice candidate : manager.getDeviceList().values()) {
            if (isSupported(candidate)) return candidate;
        }
        return null;
    }

    // ------------------------------------------------------------------ open ---

    void open() throws IOException {
        connection = manager.openDevice(device);
        if (connection == null) {
            throw new IOException("Android refused to open the port (permission missing)");
        }
        for (int i = 0; i < device.getInterfaceCount(); ++i) {
            if (!connection.claimInterface(device.getInterface(i), true)) {
                throw new IOException("Could not claim USB interface " + i);
            }
        }
        final UsbInterface data = device.getInterface(device.getInterfaceCount() - 1);
        for (int i = 0; i < data.getEndpointCount(); ++i) {
            final UsbEndpoint endpoint = data.getEndpoint(i);
            if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) endpointIn = endpoint;
            else endpointOut = endpoint;
        }
        if (endpointIn == null || endpointOut == null) {
            throw new IOException("CH340 has no bulk endpoints");
        }
        initialize();
        setBaudRate(115200);
        setParameters();
    }

    void close() {
        if (connection == null) return;
        try {
            for (int i = 0; i < device.getInterfaceCount(); ++i) {
                connection.releaseInterface(device.getInterface(i));
            }
        } catch (Exception ignored) {
            // The device may already be gone; nothing useful to do.
        }
        try {
            connection.close();
        } catch (Exception ignored) {
            // Same.
        }
        connection = null;
    }

    /**
     * Bring-up writes from the CH340 reference driver. The read-back checks it
     * performs are sanity checks only, so failures to match are ignored here:
     * the writes are what put the chip into a usable state.
     */
    private void initialize() throws IOException {
        controlOut(0xA1, 0, 0);
        setBaudRate(9600);
        controlOut(0x9A, 0x2518, LCR_ENABLE_RX | LCR_ENABLE_TX | LCR_CS8);
        if (controlOut(0xA1, 0x501F, 0xD90A) < 0) {
            throw new IOException("CH340 init failed");
        }
        // Hardware flow control off. Left on, the bridge withholds data because
        // CTS is never asserted — which looks exactly like a board whose control
        // lines work (DTR/RTS reset it fine) but which receives nothing.
        if (controlOut(0x9A, 0x2727, 0x0000) < 0) {
            throw new IOException("CH340 rejected the flow-control setting");
        }
        setBaudRate(9600);
        setControlLines();
    }

    /** 8 data bits, no parity, 1 stop bit. */
    private void setParameters() throws IOException {
        if (controlOut(0x9A, 0x2518, LCR_ENABLE_RX | LCR_ENABLE_TX | LCR_CS8) < 0) {
            throw new IOException("CH340 rejected the line coding");
        }
    }

    /**
     * Reprogram the line rate on an open port, e.g. when a handshake at one rate
     * goes unanswered and the next candidate should be tried.
     */
    void changeBaudRate(int baudRate) throws IOException {
        if (connection == null) throw new IOException("Port is closed");
        setBaudRate(baudRate);
        setParameters();
        flushInput();
    }

    private void setBaudRate(int baudRate) throws IOException {
        long factor;
        long divisor;
        if (baudRate == 921600) {
            divisor = 7;
            factor = 0xF300;
        } else if (baudRate == 307200) {
            divisor = 7;
            factor = 0xD900;
        } else {
            final long baudBaseFactor = 1532620800L;
            final int baudBaseDivMax = 3;
            factor = baudBaseFactor / baudRate;
            divisor = baudBaseDivMax;
            while ((factor > 0xFFF0) && divisor > 0) {
                factor >>= 3;
                divisor--;
            }
            if (factor > 0xFFF0) {
                throw new IOException("CH340 has no divisor for " + baudRate + " baud");
            }
            factor = 0x10000 - factor;
        }
        divisor |= 0x0080;  // CH341A would otherwise wait for a full buffer

        final int value1 = (int) ((factor & 0xFF00) | divisor);
        final int value2 = (int) (factor & 0xFF);
        if (controlOut(0x9A, 0x1312, value1) < 0 || controlOut(0x9A, 0x0F2C, value2) < 0) {
            throw new IOException("CH340 rejected " + baudRate + " baud");
        }
    }

    /**
     * DTR drives the ESP32's IO0 and RTS its EN pin through the board's reset
     * transistors, which is how esptool enters the ROM bootloader.
     */
    void setDtrRts(boolean dtrOn, boolean rtsOn) throws IOException {
        dtr = dtrOn;
        rts = rtsOn;
        setControlLines();
    }

    /**
     * One line at a time. esptool changes DTR and RTS with separate calls and this
     * board only ever answered a pulse that moved RTS alone, so the app now writes
     * them the same way instead of moving both bits in one transfer.
     */
    void setDtr(boolean on) throws IOException {
        dtr = on;
        setControlLines();
    }

    void setRts(boolean on) throws IOException {
        rts = on;
        setControlLines();
    }

    private void setControlLines() throws IOException {
        final int mask = ~((dtr ? SCL_DTR : 0) | (rts ? SCL_RTS : 0));
        if (controlOut(0xA4, mask, 0) < 0) {
            throw new IOException("CH340 rejected the control lines");
        }
    }

    private int controlOut(int request, int value, int index) {
        if (connection == null) return -1;
        return connection.controlTransfer(REQ_OUT, request, value, index, null, 0, TIMEOUT_MS);
    }

    @SuppressWarnings("unused")
    private int controlIn(int request, int value, int index, byte[] buffer) {
        if (connection == null) return -1;
        return connection.controlTransfer(REQ_IN, request, value, index, buffer, buffer.length,
                TIMEOUT_MS);
    }

    // --------------------------------------------------------------- traffic ---

    void write(byte[] data) throws IOException {
        if (connection == null) throw new IOException("Port is closed");
        int offset = 0;
        while (offset < data.length) {
            final int chunk = Math.min(data.length - offset, 4096);
            final byte[] slice = (offset == 0 && chunk == data.length)
                    ? data
                    : Arrays.copyOfRange(data, offset, offset + chunk);
            final int sent = connection.bulkTransfer(endpointOut, slice, chunk, TIMEOUT_MS);
            if (sent <= 0) throw new IOException("USB write failed");
            offset += sent;
        }
    }

    /** Bytes read, or -1 when the timeout expired with nothing to read. */
    int read(byte[] buffer, int timeoutMs) {
        if (connection == null) return -1;
        try {
            return connection.bulkTransfer(endpointIn, buffer, buffer.length, timeoutMs);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Throws away stale input, e.g. boot log bytes from before the reset. */
    void flushInput() {
        final byte[] scratch = new byte[256];
        for (int i = 0; i < 32; ++i) {
            if (read(scratch, 20) <= 0) return;
        }
    }
}
