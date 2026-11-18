package dev.phonedesk.companion;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/** An in-memory classic ESP32 ROM, not a USB or electrical emulator. Wire layout
 * follows esptool v4.8.1's ESP32 target and uncompressed no-stub write path. */
final class MockCyd {
    private static final int ROM_BLOCK = 1024;
    private static final int SECTOR = 4096;
    final UsbSerialCh340 port = new UsbSerialCh340();
    final byte[] flash = new byte[SECTOR * 4];
    boolean loader;
    boolean rejectWrites;
    boolean loseNextAck;
    boolean corruptDigest;
    boolean shortBeginStatus;
    boolean rejectDigest;
    int bootCount;
    int eraseCount;
    int writes;
    int verifiedBytes;
    int finishCount;
    private boolean inReset;
    private boolean synced;
    private boolean attached;
    private int base;
    private int blocks;
    private int next;

    MockCyd(boolean inLoader) {
        loader = inLoader;
        Arrays.fill(flash, (byte) 0x42);
        port.answer((p, op, checksum, payload) -> command(op, checksum, payload));
        port.lineObserver = (dtr, rts) -> {
            if (inReset && !rts) {
                loader = dtr; // Model the common wiring: DTR is IO0, RTS is EN.
                synced = false;
                attached = false;
                if (!loader) bootCount++;
                if (port.baud == 115200) {
                    String mode = loader ? "boot:0x3 (DOWNLOAD_BOOT)\nwaiting for download\n"
                            : "boot:0x13 (SPI_FAST_FLASH_BOOT)\n";
                    port.inject(("rst:0x1 (POWERON_RESET)," + mode)
                            .getBytes(StandardCharsets.US_ASCII));
                }
            }
            inReset = rts;
        };
    }

    private void command(int op, int checksum, byte[] data) {
        if (!loader || inReset || port.baud != 115200) return;
        ByteBuffer fields = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (op == 0x08) {
            byte[] sync = new byte[36];
            Arrays.fill(sync, (byte) 0x55);
            ByteBuffer.wrap(sync).putInt(0x07071220);
            if (Arrays.equals(sync, data)) {
                synced = true;
                reply(op, 0, new byte[0], false);
            }
            return;
        }
        if (!synced) return;
        switch (op) {
            case 0x0a:
                int value = switch (fields.getInt()) {
                    case 0x40001000 -> 0x00f01d83;
                    case 0x3ff5a004 -> 0xc4123456;
                    case 0x3ff5a008 -> 0x240a;
                    default -> 0;
                };
                reply(op, value, new byte[0], false);
                break;
            case 0x0d:
                attached = Arrays.equals(data, new byte[8]);
                reply(op, 0, new byte[0], !attached);
                break;
            case 0x02:
                int erase = fields.getInt();
                int count = fields.getInt();
                int size = fields.getInt();
                int address = fields.getInt();
                if (!attached || data.length != 16 || size != ROM_BLOCK || address < 0
                        || address % SECTOR != 0 || erase <= 0 || erase % SECTOR != 0
                        || erase > flash.length - address || count <= 0
                        || count > erase / ROM_BLOCK) {
                    reply(op, 0, new byte[0], true);
                    break;
                }
                if (shortBeginStatus) {
                    frame(op, 0, new byte[]{0, 0, 0});
                    break;
                }
                base = address;
                blocks = count;
                next = 0;
                eraseCount++;
                Arrays.fill(flash, base, base + erase, (byte) 0xff);
                reply(op, 0, new byte[0], false);
                break;
            case 0x03:
                int length = fields.getInt();
                int sequence = fields.getInt();
                byte[] block = Arrays.copyOfRange(data, 16, data.length);
                int actual = 0xef;
                for (byte b : block) actual ^= b & 0xff;
                if (rejectWrites || length != ROM_BLOCK || length != block.length
                        || sequence < 0 || sequence >= blocks || sequence > next
                        || sequence < next - 1 || actual != checksum) {
                    reply(op, 0, new byte[0], true);
                    break;
                }
                System.arraycopy(block, 0, flash, base + sequence * ROM_BLOCK, ROM_BLOCK);
                writes++;
                if (sequence == next) next++;
                if (loseNextAck) loseNextAck = false;
                else reply(op, 0, new byte[0], false);
                break;
            case 0x13:
                int start = fields.getInt();
                int bytes = fields.getInt();
                if (blocks == 0 || next != blocks || start != base || bytes < 1
                        || bytes > blocks * ROM_BLOCK) {
                    reply(op, 0, new byte[0], true);
                    break;
                }
                verifiedBytes = bytes;
                byte[] digest = md5(Arrays.copyOfRange(flash, start, start + bytes));
                if (corruptDigest) digest[0] ^= 1;
                reply(op, 0, HexFormat.of().formatHex(digest).getBytes(StandardCharsets.US_ASCII),
                        rejectDigest);
                break;
            case 0x04:
                finishCount++;
                loader = false; // A reboot command need not deliver a reply.
                synced = false;
                break;
            default:
                reply(op, 0, new byte[0], true);
        }
    }

    private static byte[] md5(byte[] bytes) {
        try {
            return MessageDigest.getInstance("MD5").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private void reply(int op, int value, byte[] data, boolean error) {
        byte[] body = Arrays.copyOf(data, data.length + 4);
        if (error) {
            body[data.length] = 1;
            body[data.length + 1] = 5;
        }
        frame(op, value, body);
    }

    private void frame(int op, int value, byte[] body) {
        byte[] packet = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN)
                .put((byte) 1).put((byte) op).putShort((short) body.length)
                .putInt(value).put(body).array();
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        wire.write(0xc0);
        for (byte b : packet) {
            int v = b & 0xff;
            if (v == 0xc0 || v == 0xdb) {
                wire.write(0xdb);
                wire.write(v == 0xc0 ? 0xdc : 0xdd);
            } else wire.write(v);
        }
        wire.write(0xc0);
        port.inject(wire.toByteArray());
    }
}
