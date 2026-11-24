package com.uliptracker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * Price sources:
 *  - Mutual funds: api.mfapi.in (free, AMFI data, full NAV history)
 *  - NSE/BSE stocks: Yahoo Finance chart endpoint (free, unofficial; symbols end in .NS or .BO)
 */
public final class Net {
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter MF_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final int KEEP_DAYS = 365 * 5 + 10;

    private Net() { }

    static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
        c.setRequestProperty("Accept", "application/json,*/*");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return new String(bo.toByteArray(), StandardCharsets.UTF_8);
        } finally { c.disconnect(); }
    }

    static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    // ---------------------------------------------------------------- mutual funds
    /** Returns [schemeCode, schemeName] pairs. */
    public static List<String[]> mfSearch(String q) throws Exception {
        JSONArray arr = new JSONArray(get("https://api.mfapi.in/mf/search?q=" + enc(q)));
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            out.add(new String[]{String.valueOf(o.get("schemeCode")), o.getString("schemeName")});
        }
        return out;
    }

    /** Fills prices with NAV history; returns scheme name. */
    public static String mfHistory(String code, TreeMap<Long, Double> into) throws Exception {
        return parseMf(get("https://api.mfapi.in/mf/" + enc(code)), code, into);
    }

    /** NAV history plus [scheme name, category]. */
    public static String[] mfHistoryMeta(String code, TreeMap<Long, Double> into) throws Exception {
        String body = get("https://api.mfapi.in/mf/" + enc(code));
        String name = parseMf(body, code, into);
        JSONObject meta = new JSONObject(body).optJSONObject("meta");
        return new String[]{name, meta == null ? "" : meta.optString("scheme_category", "")};
    }

    /** NAV history plus [scheme name, ISIN growth, ISIN reinvest] for matching statement ISINs. */
    public static String[] mfInfo(String code, TreeMap<Long, Double> into) throws Exception {
        String body = get("https://api.mfapi.in/mf/" + enc(code));
        String name = parseMf(body, code, into);
        JSONObject meta = new JSONObject(body).optJSONObject("meta");
        String g = meta == null ? "" : meta.optString("isin_growth", "");
        String r = meta == null ? "" : meta.optString("isin_div_reinvestment", "");
        return new String[]{name, g == null ? "" : g, r == null ? "" : r};
    }

    static String parseMf(String body, String code, TreeMap<Long, Double> into) throws Exception {
        JSONObject o = new JSONObject(body);
        JSONArray data = o.getJSONArray("data");
        if (data.length() == 0) throw new IOException("No NAV data for scheme " + code);
        long cutoff = LocalDate.now().toEpochDay() - KEEP_DAYS;
        for (int i = 0; i < data.length(); i++) {
            JSONObject d = data.getJSONObject(i);
            try {
                long day = LocalDate.parse(d.getString("date"), MF_DATE).toEpochDay();
                double nav = Double.parseDouble(d.getString("nav"));
                if (day >= cutoff && nav > 0) into.put(day, nav);
            } catch (Exception ignored) { }
        }
        JSONObject meta = o.optJSONObject("meta");
        return meta == null ? null : meta.optString("scheme_name", null);
    }

    // ---------------------------------------------------------------- stocks
    /** Returns [symbol, name, exchange label] for equities and ETFs on any exchange, NSE/BSE first. */
    public static List<String[]> stockSearch(String q) throws Exception { return search(q, false); }

    /** Market indices (Nifty, Sensex, sector indices...). */
    public static List<String[]> indexSearch(String q) throws Exception { return search(q, true); }

    private static List<String[]> search(String q, boolean indices) throws Exception {
        String url = "https://query2.finance.yahoo.com/v1/finance/search?q=" + enc(q)
                + "&quotesCount=20&newsCount=0&listsCount=0";
        JSONObject o = new JSONObject(get(url));
        JSONArray quotes = o.optJSONArray("quotes");
        List<String[]> out = new ArrayList<>();
        if (quotes == null) return out;
        for (int i = 0; i < quotes.length(); i++) {
            JSONObject x = quotes.getJSONObject(i);
            String sym = x.optString("symbol", "");
            String name = x.optString("longname", x.optString("shortname", sym));
            if (name.isEmpty()) name = sym;
            if (indices) {
                if (!"INDEX".equalsIgnoreCase(x.optString("quoteType", ""))) continue;
                String ex = x.optString("exchange", "");
                out.add(new String[]{sym, name, ex.equals("NSI") ? "NSE" : ex.equals("BSE") ? "BSE" : ex});
                continue;
            }
            String qt = x.optString("quoteType", "");
            if (!"EQUITY".equalsIgnoreCase(qt) && !"ETF".equalsIgnoreCase(qt)) continue;
            boolean in = sym.endsWith(".NS") || sym.endsWith(".BO");
            String ex = in ? (sym.endsWith(".NS") ? "NSE" : "BSE") : x.optString("exchDisp", x.optString("exchange", ""));
            out.add(new String[]{sym, name, ex});
        }
        // Indian listings first, then other exchanges (SGX, NASDAQ, LSE…)
        List<String[]> sorted = new ArrayList<>();
        for (String[] r : out) if (r[0].endsWith(".NS") || r[0].endsWith(".BO")) sorted.add(r);
        for (String[] r : out) if (!(r[0].endsWith(".NS") || r[0].endsWith(".BO"))) sorted.add(r);
        return sorted;
    }

    /** Fills prices with daily closes (5 years) plus today's live price; returns company name. */
    public static String stockHistory(String symbol, TreeMap<Long, Double> into) throws Exception {
        String path = "/v8/finance/chart/" + enc(symbol) + "?range=5y&interval=1d&includePrePost=false";
        String body;
        try {
            body = get("https://query1.finance.yahoo.com" + path);
        } catch (IOException e) {
            body = get("https://query2.finance.yahoo.com" + path);
        }
        return parseChart(body, symbol, into);
    }

    /** Trading currency (ISO) of the last chart parsed on this thread, e.g. "SGD". */
    public static final ThreadLocal<String> LAST_CCY = new ThreadLocal<>();

    static String parseChart(String body, String symbol, TreeMap<Long, Double> into) throws Exception {
        JSONObject chart = new JSONObject(body).getJSONObject("chart");
        JSONArray results = chart.optJSONArray("result");
        if (results == null || results.length() == 0) {
            JSONObject err = chart.optJSONObject("error");
            throw new IOException(err != null ? err.optString("description", "Symbol not found") : "Symbol not found");
        }
        JSONObject r = results.getJSONObject(0);
        JSONObject meta0 = r.optJSONObject("meta");
        java.time.ZoneId zone = IST;                     // dates in the exchange's own time zone
        if (meta0 != null) {
            try { zone = java.time.ZoneId.of(meta0.optString("exchangeTimezoneName", "Asia/Kolkata")); } catch (Exception ignored) { }
            LAST_CCY.set(meta0.optString("currency", "").toUpperCase(java.util.Locale.ROOT));
        }
        JSONArray ts = r.optJSONArray("timestamp");
        JSONObject quote = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0);
        JSONArray close = quote.optJSONArray("close");
        if (ts != null && close != null) {
            for (int i = 0; i < ts.length() && i < close.length(); i++) {
                if (close.isNull(i)) continue;
                long day = Instant.ofEpochSecond(ts.getLong(i)).atZone(zone).toLocalDate().toEpochDay();
                double v = close.getDouble(i);
                if (v > 0) into.put(day, v);
            }
        }
        JSONObject meta = r.getJSONObject("meta");
        double live = meta.optDouble("regularMarketPrice", 0);
        long liveTime = meta.optLong("regularMarketTime", 0);
        if (live > 0 && liveTime > 0) {
            into.put(Instant.ofEpochSecond(liveTime).atZone(zone).toLocalDate().toEpochDay(), live);
        }
        if (into.isEmpty()) throw new IOException("No prices for " + symbol);
        String name = meta.optString("longName", "");
        if (name.isEmpty()) name = meta.optString("shortName", "");
        return name.isEmpty() ? null : name;
    }
}
