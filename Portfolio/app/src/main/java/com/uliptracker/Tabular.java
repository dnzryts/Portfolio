package com.uliptracker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Reads broker exports (CSV or XLSX): stock holdings, mutual fund holdings, or a tradebook /
 * order history with dated buys and sells. Detects columns by their header names. Pure Java.
 */
public final class Tabular {
    private Tabular() { }

    public static class Result {
        public String kind = "";        // "Stock holdings", "Mutual fund holdings", "Trades"
        public String platform = "";    // guessed from the header layout
        public final List<Cas.Item> items = new ArrayList<>();
        public String error;
    }

    // ------------------------------------------------------------------ reading
    public static List<List<String>> rows(byte[] data) throws Exception {
        if (data.length > 1 && data[0] == 'P' && data[1] == 'K') return xlsx(data);
        String text = new String(data, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        return csv(text);
    }

    static List<List<String>> csv(String text) {
        String first = text.split("\\r?\\n", 2)[0];
        char sep = ',';
        if (count(first, '\t') > count(first, ',')) sep = '\t';
        else if (count(first, ';') > count(first, ',')) sep = ';';
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (q) {
                if (c == '"') { if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; } else q = false; }
                else cell.append(c);
            } else if (c == '"') q = true;
            else if (c == sep) { row.add(cell.toString().trim()); cell.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString().trim()); cell.setLength(0);
                out.add(row); row = new ArrayList<>();
            } else cell.append(c);
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString().trim()); out.add(row); }
        return out;
    }

    private static int count(String s, char c) { int n = 0; for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++; return n; }

    static List<List<String>> xlsx(byte[] data) throws Exception {
        Map<String, byte[]> files = new HashMap<>();
        ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(data));
        ZipEntry e;
        while ((e = z.getNextEntry()) != null) {
            String n = e.getName();
            if (n.equals("xl/sharedStrings.xml") || n.startsWith("xl/worksheets/sheet") || n.equals("xl/workbook.xml")) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                byte[] b = new byte[16384];
                int r;
                while ((r = z.read(b)) > 0) o.write(b, 0, r);
                files.put(n, o.toByteArray());
            }
        }
        z.close();
        List<String> shared = new ArrayList<>();
        if (files.containsKey("xl/sharedStrings.xml")) {
            String s = new String(files.get("xl/sharedStrings.xml"), StandardCharsets.UTF_8);
            Matcher si = Pattern.compile("<si>(.*?)</si>", Pattern.DOTALL).matcher(s);
            Pattern t = Pattern.compile("<t[^>]*>(.*?)</t>", Pattern.DOTALL);
            while (si.find()) {
                StringBuilder sb = new StringBuilder();
                Matcher tm = t.matcher(si.group(1));
                while (tm.find()) sb.append(xml(tm.group(1)));
                shared.add(sb.toString());
            }
        }
        // the first sheet that has data
        String sheetName = null;
        for (int i = 1; i <= 20 && sheetName == null; i++) if (files.containsKey("xl/worksheets/sheet" + i + ".xml")) sheetName = "xl/worksheets/sheet" + i + ".xml";
        if (sheetName == null) throw new Exception("No worksheet found");
        String sx = new String(files.get(sheetName), StandardCharsets.UTF_8);
        List<List<String>> out = new ArrayList<>();
        Matcher rm = Pattern.compile("<row[^>]*>(.*?)</row>", Pattern.DOTALL).matcher(sx);
        Pattern cp = Pattern.compile("<c\\s([^>]*?)(?:/>|>(.*?)</c>)", Pattern.DOTALL);
        Pattern vp = Pattern.compile("<v>(.*?)</v>", Pattern.DOTALL), isp = Pattern.compile("<t[^>]*>(.*?)</t>", Pattern.DOTALL);
        while (rm.find()) {
            List<String> row = new ArrayList<>();
            Matcher cm = cp.matcher(rm.group(1));
            while (cm.find()) {
                String attrs = cm.group(1), body = cm.group(2) == null ? "" : cm.group(2);
                Matcher ref = Pattern.compile("r=\"([A-Z]+)\\d+\"").matcher(attrs);
                int colIdx = row.size();
                if (ref.find()) colIdx = colIndex(ref.group(1));
                while (row.size() < colIdx) row.add("");
                String type = "";
                Matcher tt = Pattern.compile("t=\"(\\w+)\"").matcher(attrs);
                if (tt.find()) type = tt.group(1);
                String val = "";
                Matcher v = vp.matcher(body);
                if (type.equals("inlineStr")) { Matcher it = isp.matcher(body); StringBuilder sb = new StringBuilder(); while (it.find()) sb.append(xml(it.group(1))); val = sb.toString(); }
                else if (v.find()) {
                    val = xml(v.group(1));
                    if (type.equals("s")) { try { val = shared.get(Integer.parseInt(val.trim())); } catch (Exception ignored) { } }
                }
                row.add(val.trim());
            }
            out.add(row);
        }
        return out;
    }

    private static int colIndex(String letters) {
        int n = 0;
        for (char c : letters.toCharArray()) n = n * 26 + (c - 'A' + 1);
        return n - 1;
    }

    private static String xml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    // ------------------------------------------------------------------ interpreting
    private static final String[] NAME = {"instrument", "symbol", "tradingsymbol", "stock name", "stock", "scrip", "company", "security", "scheme name", "scheme", "fund name", "fund", "name"};
    private static final String[] ISIN = {"isin"};
    private static final String[] QTY = {"qty", "quantity", "shares", "units", "balance units", "no of units", "holding"};
    private static final String[] AVG = {"avg. cost", "avg cost", "average cost", "average buy price", "avg price", "avg. price", "average price", "buy avg", "buy average", "cost price"};
    private static final String[] INVESTED = {"invested value", "invested amount", "buy value", "investment", "cost value", "total cost", "invested", "amount invested"};
    private static final String[] PRICE = {"price", "trade price", "rate", "nav", "average price"};
    private static final String[] DATE = {"trade_date", "trade date", "date", "execution date", "order execution time", "execution date and time", "transaction date", "order date"};
    private static final String[] TYPE = {"trade_type", "trade type", "type", "transaction type", "buy/sell", "side", "action", "order type"};
    private static final String[] VALUE = {"value", "amount", "trade value", "total"};
    private static final String[] STATUS = {"order status", "status"};
    private static final String[] EXCHANGE = {"exchange"};

    /** Lower-case, punctuation-free form of a header ("Avg. cost" -> "avg cost", "trade_date" -> "trade date"). */
    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replace(' ', ' ').replaceAll("[._/()\\-]+", " ").replaceAll("\\s+", " ").trim();
    }

    private static int col(List<String> header, String[] keys) {
        for (String k : keys) for (int i = 0; i < header.size(); i++) if (norm(header.get(i)).equals(norm(k))) return i;
        for (String k : keys) for (int i = 0; i < header.size(); i++) {
            String nk = norm(k);
            if (nk.length() >= 3 && (" " + norm(header.get(i)) + " ").contains(" " + nk + " ")) return i;
        }
        return -1;
    }

    public static Result interpret(List<List<String>> rows) {
        Result r = new Result();
        // header = the row with the most recognised column names among the first 40
        int hi = -1, best = 0;
        for (int i = 0; i < Math.min(40, rows.size()); i++) {
            List<String> h = rows.get(i);
            int score = 0;
            for (String[] keys : new String[][]{NAME, ISIN, QTY, AVG, INVESTED, DATE, TYPE}) if (col(h, keys) >= 0) score++;
            if (score > best) { best = score; hi = i; }
        }
        if (hi < 0 || best < 2) { r.error = "Couldn't find a header row with columns like Symbol / Quantity / Price."; return r; }
        List<String> h = rows.get(hi);
        String joined = String.join("|", h).toLowerCase(Locale.ROOT);
        int cName = col(h, NAME), cIsin = col(h, ISIN), cQty = col(h, QTY), cAvg = col(h, AVG), cInv = col(h, INVESTED);
        int cDate = col(h, DATE), cType = col(h, TYPE), cPrice = col(h, PRICE), cVal = col(h, VALUE), cStatus = col(h, STATUS);
        int cSym = indexOfExact(h, "symbol");
        if (cSym < 0) cSym = indexOfExact(h, "tradingsymbol");

        // platform guess
        if (joined.contains("trade_type") || joined.contains("tradingsymbol") || (joined.contains("instrument") && joined.contains("avg. cost"))) r.platform = "Zerodha";
        else if (joined.contains("stock name") || joined.contains("average buy price") || joined.contains("execution date and time")) r.platform = "Groww";
        else if (joined.contains("scrip")) r.platform = "";

        boolean mf = joined.contains("scheme") || joined.contains("folio") || (joined.contains("units") && !joined.contains("qty"));
        boolean trades = cDate >= 0 && cType >= 0 && cQty >= 0;
        Map<String, Cas.Item> byKey = new LinkedHashMap<>();
        for (int i = hi + 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            String name = get(row, cName), isin = get(row, cIsin).toUpperCase(Locale.ROOT), sym = cSym >= 0 ? get(row, cSym) : "";
            if (name.isEmpty() && isin.isEmpty() && sym.isEmpty()) continue;
            if (name.toLowerCase(Locale.ROOT).startsWith("total")) continue;
            double qty = num(get(row, cQty));
            if (trades) {
                String st = get(row, cStatus).toLowerCase(Locale.ROOT);
                if (!st.isEmpty() && !(st.contains("exec") || st.contains("complete") || st.contains("success") || st.contains("traded"))) continue;
                long day = date(get(row, cDate));
                String tp = get(row, cType).toLowerCase(Locale.ROOT);
                if (day <= 0 || qty <= 0 || !(tp.startsWith("b") || tp.startsWith("s") || tp.contains("purchase") || tp.contains("redeem"))) continue;
                boolean buy = tp.startsWith("b") || tp.contains("purchase");
                double price = num(get(row, cPrice)), value = num(get(row, cVal));
                if (price <= 0 && value > 0) price = value / qty;
                String key = !isin.isEmpty() ? isin : (sym.isEmpty() ? name : sym).toUpperCase(Locale.ROOT);
                Cas.Item it = byKey.get(key);
                if (it == null) {
                    it = new Cas.Item();
                    it.type = mf ? Store.MF : Store.STOCK;
                    it.name = !name.isEmpty() ? name : sym; it.isin = isin; it.symbol = sym.isEmpty() && !mf ? name : sym;
                    byKey.put(key, it);
                }
                Ledger.Txn t = new Ledger.Txn();
                t.day = day; t.kind = buy ? Ledger.BUY : Ledger.SELL; t.units = qty; t.price = price;
                t.amount = value > 0 && Math.abs(value - qty * price) < qty * price * 0.05 + 1 ? value : qty * price;
                it.txns.add(t);
            } else {
                if (qty <= 0) continue;
                Cas.Item it = new Cas.Item();
                it.type = mf ? Store.MF : Store.STOCK;
                it.name = name.isEmpty() ? sym : name;
                it.isin = isin;
                it.symbol = sym.isEmpty() ? (mf ? "" : name) : sym;
                it.units = qty;
                double avg = num(get(row, cAvg)), inv = num(get(row, cInv));
                it.cost = inv > 0 ? inv : avg * qty;
                byKey.put(it.name + "|" + i, it);
            }
        }
        for (Cas.Item it : byKey.values()) {
            if (trades) {
                Ledger.sort(it.txns);
                Ledger.Book b = Ledger.replay(it.txns, Long.MAX_VALUE);
                it.units = b.units; it.cost = b.cost; it.historyOk = true;
                if (it.units < 0.0005) { it.include = false; it.note = "Fully sold (realised " + Math.round(b.realised) + ")"; }
            }
            r.items.add(it);
        }
        r.kind = trades ? "Trades" : mf ? "Mutual fund holdings" : "Stock holdings";
        if (r.items.isEmpty()) r.error = "No holdings or trades found in the file.";
        return r;
    }

    private static int indexOfExact(List<String> h, String k) {
        for (int i = 0; i < h.size(); i++) if (h.get(i).trim().equalsIgnoreCase(k)) return i;
        return -1;
    }

    private static String get(List<String> row, int c) { return c >= 0 && c < row.size() ? row.get(c).trim() : ""; }

    static double num(String s) {
        if (s == null) return 0;
        String t = s.replace(",", "").replace("₹", "").replace("Rs.", "").replace("INR", "").trim();
        boolean neg = t.startsWith("(") || t.startsWith("-");
        t = t.replaceAll("[^0-9.]", "");
        if (t.isEmpty()) return 0;
        try { double v = Double.parseDouble(t); return neg ? -v : v; } catch (Exception e) { return 0; }
    }

    private static final String[] DATE_FORMATS = {"yyyy-MM-dd", "dd-MM-yyyy", "dd/MM/yyyy", "d-MMM-yyyy", "dd-MMM-yyyy", "d MMM yyyy",
            "dd MMM yyyy", "MM/dd/yyyy", "yyyy/MM/dd", "dd.MM.yyyy", "d/M/yyyy", "dd-MMM-yy"};

    static long date(String s) {
        if (s == null || s.isEmpty()) return -1;
        String t = s.trim();
        if (t.matches("\\d{5}(\\.\\d+)?")) {   // Excel serial date
            double d = Double.parseDouble(t);
            return LocalDate.of(1899, 12, 30).plusDays((long) d).toEpochDay();
        }
        String datePart = t.replace('T', ' ').split(" (?=\\d{1,2}:)")[0].trim();
        for (String f : DATE_FORMATS) {
            try { return LocalDate.parse(datePart, DateTimeFormatter.ofPattern(f, Locale.ENGLISH)).toEpochDay(); } catch (Exception ignored) { }
        }
        try { return LocalDateTime.parse(t.replace(' ', 'T')).toLocalDate().toEpochDay(); } catch (Exception ignored) { }
        return -1;
    }
}
