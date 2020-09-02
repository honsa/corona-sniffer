package org.example.coronasniffer;

import android.location.Location;

import com.bosphere.filelogger.FL;

import org.altbeacon.beacon.Beacon;

import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

class BeaconStats {
    // RPI window is 10 minutes. use 11 to avoid any clock sync issues
    private static final long PRUNE_AGE_SECONDS = 60 * 11;
    // count devices seen during this window as "recent", for showing the number of
    // currently active devices
    private static final long RECENT_AGE_SECONDS = 30;

    private static final String TAG = BeaconStats.class.getSimpleName();
    private static final SimpleDateFormat ISO8601 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.UK);

    static class Entry {
        String rpi;
        String aem;
        int maxRssi;
        int nScans;
        double meanRssi;

        final FirstOrLast first;
        FirstOrLast last;

        static class FirstOrLast {
            Date seen;
            Location location;

            @Override
            public String toString() {
                Map<String, String> json = new TreeMap<>();
                if (location != null) {
                    json.put("latitude", "" + location.getLatitude());
                    json.put("longitude", "" + location.getLongitude());
                    json.put("accuracy", "" + Math.round(location.getAccuracy()));
                }
                json.put("seen", '"' + ISO8601.format(seen) + '"');
                return toJson(json);
            }
        }

        Entry(Beacon b, Location location) {
            // NOTE: AltBeacon has rather hacky logic in toString
            rpi = b.getId1().toHexString().replace("0x", "");
            aem = String.format("%08x", b.getDataFields().get(0));
            nScans = 1;
            meanRssi = b.getRunningAverageRssi();
            maxRssi = Math.max(b.getRssi(), (int) Math.round(meanRssi));
            first = new FirstOrLast();
            first.location = location;
            first.seen = new Date();
            last = first;
            FL.v(TAG, toString());
        }

        void update(Entry next) {
            maxRssi = Math.max(maxRssi, next.maxRssi);
            // some sort of mixture of running means, close enough
            meanRssi = (meanRssi * nScans + next.meanRssi * next.nScans) / (nScans + next.nScans);
            nScans += next.nScans;
            last = next.last;
        }

        @Override
        public String toString() {
            Map<String, String> json = new TreeMap<>();
            json.put("rpi", '"' + rpi + '"');
            json.put("aem", '"' + aem + '"');
            json.put("maxRssi", "" + maxRssi);
            json.put("nScans", "" + nScans);
            json.put("meanRssi", "" + String.format("%.4g", meanRssi));
            json.put("first", first.toString());
            json.put("last", last.toString());
            return toJson(json);
        }

        private static String toJson(Map<String, String> map) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, String> e : map.entrySet()) {
                if (first) first = false;
                else sb.append(',');
                sb.append('"');
                sb.append(e.getKey());
                sb.append("\":");
                sb.append(e.getValue());
            }
            sb.append('}');
            return sb.toString();
        }

        void log() {
            FL.i(TAG, toString());
        }

        long ageSeconds() {
            return (new Date().getTime() - last.seen.getTime()) / 1000;
        }
    }

    private Map<String, Entry> entries = new HashMap<>();
    private Location lastLocation = null;

    Entry add(Collection<Beacon> beacons) {
        FL.v(TAG,"beacon batch size %d, map size %d", beacons.size(), entries.size());

        double maxRssi = -1000;
        Entry maxElem = null;

        for (Beacon b : beacons) {
            Entry entry = new Entry(b, lastLocation);
            Entry prev = entries.get(entry.rpi);

            if (entry.maxRssi > maxRssi) {
                maxElem = entry;
                maxRssi = entry.maxRssi;
            }

            if (prev == null) {
                FL.d(TAG, "new device " + entry);
                entries.put(entry.rpi, entry);
            } else {
                prev.update(entry);
            }
        }

        prune();
        return maxElem;
    }

    void flush() {
        for (Entry e : entries.values()) e.log();
        entries.clear();
    }

    void onLocationChanged(Location location) {
        lastLocation = location;
    }

    private void prune() {
        Iterator<Map.Entry<String, Entry>> itr = entries.entrySet().iterator();
        while (itr.hasNext()) {
            Entry e = itr.next().getValue();
            if (e.ageSeconds() > PRUNE_AGE_SECONDS) {
                e.log();
                itr.remove();
            }
        }
    }

    int getNearbyDeviceCount() {
        int count = 0;
        for (Entry e : entries.values()) {
            if (e.ageSeconds() < RECENT_AGE_SECONDS) count++;
        }
        return count;
    }
}
