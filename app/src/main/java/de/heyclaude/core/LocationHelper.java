package de.heyclaude.core;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Ermittelt den ungefaehren Standort des Handys (ohne Google-Bibliotheken). */
final class LocationHelper {

    private static final String TAG = "HeyClaude";
    private static final long FRESH_MS = 10 * 60 * 1000L;

    /** Ein ermittelter Ort. */
    static final class Place {
        double lat;
        double lon;
        float accuracy;
        long time;
        String street;
        String district;
        String city;
        String postalCode;
        String region;
        String country;
        String countryCode;

        /** z.B. "Altstadt, 80331 München, Bayern, Deutschland" */
        String describe() {
            StringBuilder sb = new StringBuilder();
            append(sb, street);
            append(sb, district);
            String c = ((postalCode == null ? "" : postalCode) + " " + (city == null ? "" : city)).trim();
            append(sb, c);
            append(sb, region);
            append(sb, country);
            return sb.toString();
        }

        private static void append(StringBuilder sb, String s) {
            if (s == null || s.trim().isEmpty()) {
                return;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(s.trim());
        }
    }

    private final Context ctx;
    private volatile Place cached;

    LocationHelper(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    static boolean hasPermission(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Blockiert hoechstens maxWaitMs. Nicht auf dem Haupt-Thread aufrufen. Kann null liefern. */
    Place get(long maxWaitMs) {
        if (!hasPermission(ctx)) {
            return null;
        }
        try {
            LocationManager lm = ctx.getSystemService(LocationManager.class);
            if (lm == null) {
                return cached;
            }
            if (Build.VERSION.SDK_INT >= 28 && !lm.isLocationEnabled()) {
                return cached;
            }
            Location best = lastKnown(lm);
            if (best == null || System.currentTimeMillis() - best.getTime() > FRESH_MS) {
                Location fresh = current(lm, maxWaitMs);
                if (fresh != null) {
                    best = fresh;
                }
            }
            if (best == null) {
                return cached;
            }
            Place old = cached;
            if (old != null && old.city != null) {
                float[] dist = new float[1];
                Location.distanceBetween(old.lat, old.lon, best.getLatitude(), best.getLongitude(), dist);
                if (dist[0] < 300) {
                    old.time = best.getTime();
                    old.accuracy = best.getAccuracy();
                    return old;
                }
            }
            Place p = new Place();
            p.lat = best.getLatitude();
            p.lon = best.getLongitude();
            p.accuracy = best.getAccuracy();
            p.time = best.getTime();
            geocode(p);
            cached = p;
            return p;
        } catch (SecurityException e) {
            Log.w(TAG, "Standort: keine Berechtigung", e);
            return cached;
        } catch (Exception e) {
            Log.w(TAG, "Standort", e);
            return cached;
        }
    }

    private Location lastKnown(LocationManager lm) {
        Location best = null;
        List<String> providers = lm.getProviders(true);
        if (providers == null) {
            return null;
        }
        for (String p : providers) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            } catch (SecurityException | IllegalArgumentException ignored) {
                // Provider nicht nutzbar
            }
        }
        return best;
    }

    private String pickProvider(LocationManager lm) {
        if (Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER)
                && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
            return LocationManager.FUSED_PROVIDER;
        }
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            return LocationManager.NETWORK_PROVIDER;
        }
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            return LocationManager.GPS_PROVIDER;
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private Location current(LocationManager lm, long maxWaitMs) {
        String provider = pickProvider(lm);
        if (provider == null) {
            return null;
        }
        final AtomicReference<Location> result = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                CancellationSignal cancel = new CancellationSignal();
                lm.getCurrentLocation(provider, cancel, Runnable::run, loc -> {
                    result.set(loc);
                    latch.countDown();
                });
                if (!latch.await(maxWaitMs, TimeUnit.MILLISECONDS)) {
                    cancel.cancel();
                }
            } else {
                LocationListener listener = new LocationListener() {
                    @Override
                    public void onLocationChanged(Location location) {
                        result.set(location);
                        latch.countDown();
                    }

                    @Override
                    public void onStatusChanged(String p, int status, Bundle extras) {
                    }

                    @Override
                    public void onProviderEnabled(String p) {
                    }

                    @Override
                    public void onProviderDisabled(String p) {
                    }
                };
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper());
                if (!latch.await(maxWaitMs, TimeUnit.MILLISECONDS)) {
                    lm.removeUpdates(listener);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "Standort-Abfrage", e);
        }
        return result.get();
    }

    @SuppressWarnings("deprecation")
    private void geocode(Place p) {
        try {
            if (!Geocoder.isPresent()) {
                return;
            }
            List<Address> list = new Geocoder(ctx, Locale.getDefault()).getFromLocation(p.lat, p.lon, 1);
            if (list == null || list.isEmpty()) {
                return;
            }
            Address a = list.get(0);
            p.street = a.getThoroughfare();
            p.district = a.getSubLocality();
            p.city = a.getLocality() != null ? a.getLocality() : a.getSubAdminArea();
            p.postalCode = a.getPostalCode();
            p.region = a.getAdminArea();
            p.country = a.getCountryName();
            p.countryCode = a.getCountryCode();
        } catch (Exception e) {
            Log.w(TAG, "Adresse nicht ermittelbar", e);
        }
    }
}
