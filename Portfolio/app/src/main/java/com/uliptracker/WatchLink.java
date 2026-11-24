package com.uliptracker;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Phone ⇄ watch sync over the Bluetooth link the two already share (secure RFCOMM between paired devices).
 * No Google Play services needed. The phone listens while its app is open; the watch connects, says hello,
 * and gets the portfolio as gzipped JSON once the phone has approved it.
 *
 * Wire format: watch → "PORTFOLIO-SYNC/1\n"; phone → status byte (0 ok, 1 denied, 2 busy),
 * then int length + gzip bytes when ok; watch → one ack byte.
 */
public final class WatchLink {

    public static final UUID SERVICE = UUID.fromString("6f1c2a4e-8d3b-4c55-9a71-2b9e6d0c4f13");
    private static final String HELLO = "PORTFOLIO-SYNC/1";
    private static final int MAX = 8 * 1024 * 1024;

    private WatchLink() { }

    public interface Phone {
        /** May block (e.g. while the user answers an approval prompt). */
        boolean allow(BluetoothDevice watch);
        /** The portfolio to send, already gzipped. */
        byte[] payload() throws Exception;
        void sent(BluetoothDevice watch, int bytes);
    }

    // ------------------------------------------------------------------ phone side
    public static final class Server {
        private volatile BluetoothServerSocket socket;
        private volatile boolean running;

        public boolean running() { return running; }

        public synchronized void start(final BluetoothAdapter adapter, final Phone phone) throws IOException {
            if (running) return;
            final BluetoothServerSocket ss = adapter.listenUsingRfcommWithServiceRecord("Portfolio sync", SERVICE);
            socket = ss;
            running = true;
            Thread t = new Thread(new Runnable() {
                @Override public void run() {
                    while (running && socket == ss) {
                        BluetoothSocket s = null;
                        try {
                            s = ss.accept();
                            serve(s, phone);
                        } catch (Throwable e) {
                            if (!running || socket != ss) break;
                        } finally {
                            if (s != null) try { s.close(); } catch (IOException ignored) { }
                        }
                    }
                }
            }, "watch-sync");
            t.setDaemon(true);
            t.start();
        }

        public synchronized void stop() {
            running = false;
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
            socket = null;
        }

        private static void serve(BluetoothSocket s, Phone phone) throws Exception {
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());
            if (!HELLO.equals(readLine(in, 64))) return;
            if (!phone.allow(s.getRemoteDevice())) { out.writeByte(1); out.flush(); in.read(); return; }
            byte[] data = phone.payload();
            out.writeByte(0);
            out.writeInt(data.length);
            out.write(data);
            out.flush();
            in.read();      // wait for the watch to finish reading before closing
            phone.sent(s.getRemoteDevice(), data.length);
        }
    }

    // ------------------------------------------------------------------ watch side
    /** Thrown when the phone said no. */
    public static final class Denied extends IOException { Denied() { super("Not allowed on the phone"); } }

    /** Tries each paired phone in turn; returns the (gunzipped) portfolio JSON. */
    public static String fetch(BluetoothAdapter adapter) throws IOException {
        List<BluetoothDevice> phones = new ArrayList<>(), others = new ArrayList<>();
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            BluetoothClass c = d.getBluetoothClass();
            if (c != null && c.getMajorDeviceClass() == BluetoothClass.Device.Major.PHONE) phones.add(d); else others.add(d);
        }
        phones.addAll(others);
        if (phones.isEmpty()) throw new IOException("No paired phone");
        IOException last = null;
        for (BluetoothDevice d : phones) {
            BluetoothSocket s = null;
            try {
                s = d.createRfcommSocketToServiceRecord(SERVICE);
                try { adapter.cancelDiscovery(); } catch (Throwable ignored) { }
                s.connect();
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                DataInputStream in = new DataInputStream(s.getInputStream());
                out.write((HELLO + "\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                int status = in.read();
                if (status == 1) { out.write(1); throw new Denied(); }
                if (status != 0) throw new IOException("Phone is busy, try again");
                int len = in.readInt();
                if (len <= 0 || len > MAX) throw new IOException("Bad data from phone");
                byte[] b = new byte[len];
                in.readFully(b);
                out.write(1);
                out.flush();
                return gunzip(b);
            } catch (Denied e) {
                throw e;
            } catch (IOException e) {
                last = e;
            } finally {
                if (s != null) try { s.close(); } catch (IOException ignored) { }
            }
        }
        throw last != null ? new IOException("Couldn't reach the phone. Open Portfolio on your phone and try again.") : new IOException("No paired phone");
    }

    // ------------------------------------------------------------------ helpers
    private static String readLine(InputStream in, int max) throws IOException {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < max; i++) {
            int c = in.read();
            if (c < 0 || c == '\n') break;
            b.append((char) c);
        }
        return b.toString();
    }

    public static byte[] gzip(String s) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        GZIPOutputStream g = new GZIPOutputStream(bo);
        g.write(s.getBytes(StandardCharsets.UTF_8));
        g.close();
        return bo.toByteArray();
    }

    public static String gunzip(byte[] b) throws IOException {
        GZIPInputStream g = new GZIPInputStream(new ByteArrayInputStream(b));
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = g.read(buf)) > 0) {
            bo.write(buf, 0, n);
            if (bo.size() > 4 * MAX) throw new IOException("Too much data");
        }
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Portfolio JSON trimmed for the watch: prices from the last `days` days only. */
    public static org.json.JSONObject trimmed(org.json.JSONObject o, int days) throws org.json.JSONException {
        String cutoff = java.time.LocalDate.now().minusDays(days).toString();
        for (String k : new String[]{"assets", "indices"}) {
            org.json.JSONArray arr = o.optJSONArray(k);
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject a = arr.getJSONObject(i);
                org.json.JSONArray h = a.optJSONArray("prices");
                if (h == null) continue;
                org.json.JSONArray keep = new org.json.JSONArray();
                for (int j = 0; j < h.length(); j++) {
                    org.json.JSONArray p = h.getJSONArray(j);
                    if (p.getString(0).compareTo(cutoff) >= 0) keep.put(p);
                }
                if (keep.length() < 2 && h.length() >= 2) {       // keep the last two so the day's change still shows
                    keep = new org.json.JSONArray();
                    keep.put(h.getJSONArray(h.length() - 2));
                    keep.put(h.getJSONArray(h.length() - 1));
                } else if (keep.length() == 0 && h.length() > 0) keep.put(h.getJSONArray(h.length() - 1));
                a.put("prices", keep);
            }
        }
        return o;
    }
}
