package dev.phonedesk.companion;

/**
 * What a failed GATT operation should try next, as a decision with no Android
 * types in it.
 *
 * <p>The numbers are the platform's own — {@code BluetoothGatt.GATT_SUCCESS},
 * {@code GATT_INSUFFICIENT_AUTHENTICATION}, {@code GATT_ERROR} and
 * {@code BluetoothDevice.BOND_*} — pinned here because the host suite compiles a
 * handful of pure files and no Android classes at all. {@code DeckService} passes
 * the platform values straight in, so the two sides move together.
 */
final class BondAdvice {
    static final int SUCCESS = 0;
    static final int INSUFFICIENT_AUTHENTICATION = 5;
    static final int ERROR = 133;
    static final int BOND_NONE = 10;
    static final int BOND_BONDING = 11;
    static final int BOND_BONDED = 12;

    static final String NONE = "none";
    static final String PAIR = "pair";
    static final String FORGET = "forget";

    private BondAdvice() {
    }

    /**
     * {@code forget} when a bond the stack keeps refusing has to go, {@code pair}
     * when a write was refused for want of one, {@code none} otherwise.
     */
    static String forFailure(int statusCode, int bondState) {
        if (statusCode == INSUFFICIENT_AUTHENTICATION) {
            return bondState == BOND_BONDED ? NONE : PAIR;
        }
        if (statusCode != SUCCESS && bondState == BOND_BONDED) {
            return FORGET;
        }
        return NONE;
    }
}
