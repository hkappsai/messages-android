package co.hkapps.messages;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The phone's SIM cards, for phones with more than one. */
public final class Sims {

    public static final class Sim {
        public final int subId;
        public final int slot;
        public final String name;
        public final int color;

        Sim(int subId, int slot, String name, int color) {
            this.subId = subId;
            this.slot = slot;
            this.name = name;
            this.color = color;
        }

        /** "SIM 1" style label with the carrier name when known. */
        public String label() {
            String base = "SIM " + (slot + 1);
            return name == null || name.isEmpty() ? base : base + " · " + name;
        }
    }

    private Sims() {}

    static boolean canRead(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Active SIMs in slot order. Empty without permission. */
    public static List<Sim> active(Context c) {
        if (!canRead(c)) return Collections.emptyList();
        List<Sim> result = new ArrayList<>();
        try {
            SubscriptionManager sm = c.getSystemService(SubscriptionManager.class);
            List<SubscriptionInfo> subs = sm == null ? null : sm.getActiveSubscriptionInfoList();
            if (subs != null) {
                for (SubscriptionInfo info : subs) {
                    CharSequence name = info.getDisplayName() != null ? info.getDisplayName() : info.getCarrierName();
                    result.add(new Sim(info.getSubscriptionId(), info.getSimSlotIndex(),
                            name == null ? null : name.toString(), info.getIconTint()));
                }
            }
        } catch (SecurityException ignored) {
            // Permission taken away meanwhile.
        }
        Collections.sort(result, (a, b) -> Integer.compare(a.slot, b.slot));
        return result;
    }

    public static boolean multi(Context c) {
        return active(c).size() > 1;
    }

    public static Sim byId(Context c, int subId) {
        for (Sim s : active(c)) {
            if (s.subId == subId) return s;
        }
        return null;
    }

    /**
     * The SIM to send from in a chat: the chat's choice, else the SIM the last text arrived on,
     * else Android's default for SMS. -1 lets Android decide.
     */
    public static int forChat(Context c, long threadId, int lastIncomingSub) {
        int chosen = ChatPrefs.integer(c, threadId, Prefs.SIM);
        if (chosen >= 0 && byId(c, chosen) != null) return chosen;
        if (lastIncomingSub >= 0 && byId(c, lastIncomingSub) != null) return lastIncomingSub;
        int def = SubscriptionManager.getDefaultSmsSubscriptionId();
        return def >= 0 ? def : -1;
    }

    /** "SIM 2" for a message's subscription id, or null if unknown. */
    public static String labelFor(Context c, int subId) {
        Sim s = byId(c, subId);
        return s == null ? null : s.label();
    }
}
