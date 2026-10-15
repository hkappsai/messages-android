package co.hkapps.messages;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;

import java.util.Locale;

/** "Send location": the phone's position as a maps link anyone can open. */
final class Locations {

    interface Callback {
        /** link is null when the location couldn't be found. */
        void done(String link);
    }

    private Locations() {}

    static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    static String link(Location l) {
        return String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", l.getLatitude(), l.getLongitude());
    }

    @SuppressWarnings({"MissingPermission", "deprecation"})
    static void find(Context c, Callback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        LocationManager lm = c.getSystemService(LocationManager.class);
        if (lm == null || !allowed(c)) {
            callback.done(null);
            return;
        }
        // A fresh fix if one comes quickly, else the last known position.
        Location best = null;
        for (String p : lm.getProviders(true)) {
            Location l = lm.getLastKnownLocation(p);
            if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
        }
        Location fallback = best;
        String provider = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER
                : lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : null;
        if (provider == null) {
            callback.done(fallback == null ? null : link(fallback));
            return;
        }
        boolean[] done = {false};
        Runnable timeout = () -> {
            if (done[0]) return;
            done[0] = true;
            callback.done(fallback == null ? null : link(fallback));
        };
        main.postDelayed(timeout, 8000);
        if (Build.VERSION.SDK_INT >= 30) {
            lm.getCurrentLocation(provider, new CancellationSignal(), c.getMainExecutor(), l -> {
                if (done[0]) return;
                done[0] = true;
                main.removeCallbacks(timeout);
                callback.done(l != null ? link(l) : fallback == null ? null : link(fallback));
            });
        } else {
            lm.requestSingleUpdate(provider, new android.location.LocationListener() {
                @Override
                public void onLocationChanged(Location l) {
                    if (done[0]) return;
                    done[0] = true;
                    main.removeCallbacks(timeout);
                    callback.done(link(l));
                }

                @Override public void onStatusChanged(String p, int s, android.os.Bundle b) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
            }, Looper.getMainLooper());
        }
    }
}
