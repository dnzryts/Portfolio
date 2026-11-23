package com.uliptracker;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal PDF text extractor for statements: xref tables/streams, object streams, Flate/ASCII filters,
 * Standard security handler (RC4 40–128, AES-128, AES-256 R5/R6), simple and Type0 fonts with
 * ToUnicode, and line reconstruction by position. Pure Java, no Android dependencies.
 */
public final class Pdf {

    public static class PasswordException extends Exception {
        public PasswordException(String m) { super(m); }
    }

    // ------------------------------------------------------------------ object model
    static final class Name {
        final String n;
        Name(String n) { this.n = n; }
        @Override public boolean equals(Object o) { return o instanceof Name && ((Name) o).n.equals(n); }
        @Override public int hashCode() { return n.hashCode(); }
        @Override public String toString() { return "/" + n; }
    }

    static final class Ref {
        final int num, gen;
        Ref(int n, int g) { num = n; gen = g; }
    }

    static final class Str {
        byte[] b;
        Str(byte[] b) { this.b = b; }
    }

    static final class Stream {
        final Map<String, Object> dict;
        byte[] raw;
        int num, gen;
        boolean decrypted;
        Stream(Map<String, Object> d, byte[] r) { dict = d; raw = r; }
    }

    static final class Op {
        final String op;
        Op(String o) { op = o; }
    }

    private final byte[] buf;
    private final Map<Integer, Integer> offsets = new HashMap<>();
    private final Map<Integer, int[]> inObjStm = new HashMap<>();      // num -> [stmNum, index]
    private final Map<Integer, Object> cache = new HashMap<>();
    private Map<String, Object> trailer;
    private byte[] fileKey;           // null = not encrypted
    private int cryptV, cryptR;
    private boolean aesStreams, aesV3;
    private int encryptObj = -1;

    // ------------------------------------------------------------------ public API
    /** Extracts text as lines, page by page. Throws PasswordException if the password is wrong/needed. */
    public static List<String> extractLines(byte[] data, String password) throws Exception {
        Pdf p = new Pdf(data);
        p.open(password == null ? "" : password);
        return p.lines();
    }

    private Pdf(byte[] b) { buf = b; }

    private void open(String password) throws Exception {
        try { readXref(); } catch (Exception e) { reconstruct(); }
        if (trailer == null || !trailer.containsKey("Root")) reconstruct();
        Object enc = trailer.get("Encrypt");
        if (enc != null) {
            if (enc instanceof Ref) encryptObj = ((Ref) enc).num;
            setupCrypt(dict(resolve(enc)), password);
        }
    }

    // ------------------------------------------------------------------ lexer
    private int pos;

    private static boolean ws(int c) { return c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32; }

    private static boolean delim(int c) { return c == '(' || c == ')' || c == '<' || c == '>' || c == '[' || c == ']' || c == '{' || c == '}' || c == '/' || c == '%'; }

    private void skipWs(byte[] b) {
        while (pos < b.length) {
            int c = b[pos] & 0xff;
            if (ws(c)) pos++;
            else if (c == '%') { while (pos < b.length && b[pos] != '\n' && b[pos] != '\r') pos++; }
            else break;
        }
    }

    private String token(byte[] b) {
        skipWs(b);
        int s = pos;
        while (pos < b.length && !ws(b[pos] & 0xff) && !delim(b[pos] & 0xff)) pos++;
        return new String(b, s, pos - s, StandardCharsets.ISO_8859_1);
    }

    /** Parses one object at pos in b. Content-stream mode returns operators as Op. */
    private Object parse(byte[] b, boolean content) {
        skipWs(b);
        if (pos >= b.length) return null;
        int c = b[pos] & 0xff;
        if (c == '<' && pos + 1 < b.length && b[pos + 1] == '<') {
            pos += 2;
            Map<String, Object> d = new HashMap<>();
            while (true) {
                skipWs(b);
                if (pos >= b.length) break;
                if (b[pos] == '>' && pos + 1 < b.length && b[pos + 1] == '>') { pos += 2; break; }
                Object k = parse(b, content);
                if (!(k instanceof Name)) { if (k == null) break; continue; }
                Object v = parse(b, content);
                d.put(((Name) k).n, v);
            }
            return d;
        }
        if (c == '<') {
            pos++;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            int hi = -1;
            while (pos < b.length && b[pos] != '>') {
                int h = Character.digit(b[pos++], 16);
                if (h < 0) continue;
                if (hi < 0) hi = h; else { o.write(hi * 16 + h); hi = -1; }
            }
            if (hi >= 0) o.write(hi * 16);
            pos++;
            return new Str(o.toByteArray());
        }
        if (c == '[') {
            pos++;
            List<Object> l = new ArrayList<>();
            while (true) {
                skipWs(b);
                if (pos >= b.length) break;
                if (b[pos] == ']') { pos++; break; }
                Object o = parse(b, content);
                if (o == null && pos >= b.length) break;
                l.add(o);
            }
            return l;
        }
        if (c == '(') {
            pos++;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            int depth = 1;
            while (pos < b.length) {
                int ch = b[pos++] & 0xff;
                if (ch == '\\') {
                    if (pos >= b.length) break;
                    int e = b[pos++] & 0xff;
                    switch (e) {
                        case 'n': o.write('\n'); break;
                        case 'r': o.write('\r'); break;
                        case 't': o.write('\t'); break;
                        case 'b': o.write('\b'); break;
                        case 'f': o.write('\f'); break;
                        case '\r': if (pos < b.length && b[pos] == '\n') pos++; break;
                        case '\n': break;
                        default:
                            if (e >= '0' && e <= '7') {
                                int v = e - '0';
                                for (int k = 0; k < 2 && pos < b.length && b[pos] >= '0' && b[pos] <= '7'; k++) v = v * 8 + (b[pos++] - '0');
                                o.write(v & 0xff);
                            } else o.write(e);
                    }
                } else if (ch == '(') { depth++; o.write(ch); }
                else if (ch == ')') { if (--depth == 0) break; o.write(ch); }
                else o.write(ch);
            }
            return new Str(o.toByteArray());
        }
        if (c == '/') {
            pos++;
            int s = pos;
            while (pos < b.length && !ws(b[pos] & 0xff) && !delim(b[pos] & 0xff)) pos++;
            String n = new String(b, s, pos - s, StandardCharsets.ISO_8859_1);
            if (n.indexOf('#') >= 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < n.length(); i++) {
                    if (n.charAt(i) == '#' && i + 2 < n.length()) { sb.append((char) Integer.parseInt(n.substring(i + 1, i + 3), 16)); i += 2; }
                    else sb.append(n.charAt(i));
                }
                n = sb.toString();
            }
            return new Name(n);
        }
        if (c == ']' || c == ')' || c == '>' || c == '}' || c == '{') { pos++; return content ? new Op(String.valueOf((char) c)) : null; }
        String t = token(b);
        if (t.isEmpty()) { pos++; return null; }
        char f = t.charAt(0);
        if ((f >= '0' && f <= '9') || f == '-' || f == '+' || f == '.') {
            Number n = number(t);
            if (!content && n instanceof Long) {
                int save = pos;
                String t2 = token(b);
                if (!t2.isEmpty() && t2.chars().allMatch(Character::isDigit)) {
                    String t3 = token(b);
                    if (t3.equals("R")) return new Ref(n.intValue(), Integer.parseInt(t2));
                }
                pos = save;
            }
            return n;
        }
        if (t.equals("true")) return Boolean.TRUE;
        if (t.equals("false")) return Boolean.FALSE;
        if (t.equals("null")) return null;
        return content ? new Op(t) : new Op(t);
    }

    private static Number number(String t) {
        try {
            if (t.indexOf('.') < 0) return Long.parseLong(t.startsWith("+") ? t.substring(1) : t);
            return Double.parseDouble(t);
        } catch (Exception e) {
            try { return Double.parseDouble(t.replaceAll("[^0-9.\\-]", "")); } catch (Exception e2) { return 0L; }
        }
    }

    // ------------------------------------------------------------------ xref
    private void readXref() throws Exception {
        int sx = lastIndexOf("startxref".getBytes(StandardCharsets.ISO_8859_1), buf.length);
        if (sx < 0) throw new Exception("no startxref");
        pos = sx + 9;
        int off = Integer.parseInt(token(buf));
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        while (off > 0 && off < buf.length && seen.add(off)) {
            pos = off;
            skipWs(buf);
            Map<String, Object> t;
            if (startsWith(buf, pos, "xref")) {
                pos += 4;
                while (true) {
                    skipWs(buf);
                    if (startsWith(buf, pos, "trailer")) { pos += 7; break; }
                    int start = Integer.parseInt(token(buf));
                    int count = Integer.parseInt(token(buf));
                    for (int i = 0; i < count; i++) {
                        String o = token(buf), g = token(buf), k = token(buf);
                        int num = start + i;
                        if (k.equals("n") && !offsets.containsKey(num) && !inObjStm.containsKey(num)) {
                            int v = Integer.parseInt(o);
                            if (v > 0) offsets.put(num, v);
                        } else if (k.equals("f") && !offsets.containsKey(num)) {
                            offsets.put(num, -1);
                        }
                    }
                }
                Object td = parse(buf, false);
                t = dict(td);
                if (t.containsKey("XRefStm")) readXrefStream(((Number) t.get("XRefStm")).intValue());
            } else {
                t = readXrefStream(off);
            }
            if (trailer == null) trailer = t;
            else for (Map.Entry<String, Object> e : t.entrySet()) if (!trailer.containsKey(e.getKey())) trailer.put(e.getKey(), e.getValue());
            Object prev = t.get("Prev");
            off = prev instanceof Number ? ((Number) prev).intValue() : -1;
        }
        // drop "free" markers
        List<Integer> free = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : offsets.entrySet()) if (e.getValue() < 0) free.add(e.getKey());
        for (Integer k : free) offsets.remove(k);
    }

    private Map<String, Object> readXrefStream(int off) throws Exception {
        pos = off;
        token(buf); token(buf); token(buf);   // n g obj
        Object d = parse(buf, false);
        skipWs(buf);
        Stream s = readStreamBody(dict(d), -1, -1);
        byte[] data = decode(s);
        List<Object> w = list(s.dict.get("W"));
        int w0 = num(w.get(0)), w1 = num(w.get(1)), w2 = num(w.get(2));
        List<Object> index = s.dict.containsKey("Index") ? list(s.dict.get("Index"))
                : Arrays.<Object>asList(0L, s.dict.get("Size"));
        int p = 0;
        for (int i = 0; i + 1 < index.size(); i += 2) {
            int start = num(index.get(i)), count = num(index.get(i + 1));
            for (int j = 0; j < count && p + w0 + w1 + w2 <= data.length; j++) {
                long type = w0 == 0 ? 1 : be(data, p, w0);
                long f1 = be(data, p + w0, w1), f2 = be(data, p + w0 + w1, w2);
                p += w0 + w1 + w2;
                int num = start + j;
                if (offsets.containsKey(num) || inObjStm.containsKey(num)) continue;
                if (type == 1) offsets.put(num, (int) f1);
                else if (type == 2) inObjStm.put(num, new int[]{(int) f1, (int) f2});
                else offsets.put(num, -1);
            }
        }
        return s.dict;
    }

    private static long be(byte[] d, int p, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) v = (v << 8) | (d[p + i] & 0xff);
        return v;
    }

    /** Fallback when the xref is broken: scan for "n g obj". */
    private void reconstruct() {
        offsets.clear(); inObjStm.clear(); cache.clear();
        Matcher m = Pattern.compile("(?<![0-9])(\\d{1,7})\\s+(\\d{1,5})\\s+obj\\b").matcher(new String(buf, StandardCharsets.ISO_8859_1));
        Map<String, Object> t = new HashMap<>();
        while (m.find()) {
            int num = Integer.parseInt(m.group(1));
            offsets.put(num, m.start());
        }
        for (Integer num : new ArrayList<>(offsets.keySet())) {
            try {
                Object o = getObject(num);
                Map<String, Object> d = o instanceof Stream ? ((Stream) o).dict : o instanceof Map ? dict(o) : null;
                if (d == null) continue;
                if (o instanceof Stream && "ObjStm".equals(nameOf(d.get("Type")))) {
                    Stream s = (Stream) o;
                    byte[] data = decode(s);
                    int n = num(d.get("N"));
                    int save = pos;
                    pos = 0;
                    for (int i = 0; i < n; i++) {
                        int on = Integer.parseInt(token(data)); token(data);
                        if (!offsets.containsKey(on)) inObjStm.put(on, new int[]{num, i});
                    }
                    pos = save;
                }
                if ("Catalog".equals(nameOf(d.get("Type")))) t.put("Root", new Ref(num, 0));
                if (d.containsKey("Root")) for (Map.Entry<String, Object> e : d.entrySet()) if (!t.containsKey(e.getKey())) t.put(e.getKey(), e.getValue());
            } catch (Throwable ignored) { }
        }
        int ti = lastIndexOf("trailer".getBytes(StandardCharsets.ISO_8859_1), buf.length);
        if (ti >= 0) {
            pos = ti + 7;
            try { for (Map.Entry<String, Object> e : dict(parse(buf, false)).entrySet()) t.put(e.getKey(), e.getValue()); } catch (Throwable ignored) { }
        }
        trailer = t;
    }

    // ------------------------------------------------------------------ objects
    private Object getObject(int num) throws Exception {
        if (cache.containsKey(num)) return cache.get(num);
        int savedPos = pos;
        try { return loadObject(num); } finally { pos = savedPos; }
    }

    private Object loadObject(int num) throws Exception {
        Object result = null;
        Integer off = offsets.get(num);
        if (off != null && off >= 0) {
            pos = off;
            int n = Integer.parseInt(token(buf));
            int g = Integer.parseInt(token(buf));
            token(buf);   // obj
            Object o = parse(buf, false);
            skipWs(buf);
            if (o instanceof Map && startsWith(buf, pos, "stream")) {
                Stream s = readStreamBody(dict(o), n, g);
                result = s;
            } else result = o;
        } else if (inObjStm.containsKey(num)) {
            int[] loc = inObjStm.get(num);
            Object so = getObject(loc[0]);
            if (so instanceof Stream) {
                Stream s = (Stream) so;
                byte[] data = decode(s);
                int n = num(s.dict.get("N")), first = num(s.dict.get("First"));
                int save = pos;
                pos = 0;
                int objOff = -1;
                for (int i = 0; i < n; i++) {
                    int on = Integer.parseInt(token(data));
                    int oo = Integer.parseInt(token(data));
                    if (on == num) objOff = oo;
                }
                if (objOff >= 0) { pos = first + objOff; result = parse(data, false); }
                pos = save;
            }
        }
        cache.put(num, result);
        return result;
    }

    private Stream readStreamBody(Map<String, Object> d, int num, int gen) {
        pos += 6;   // "stream"
        if (pos < buf.length && buf[pos] == '\r') pos++;
        if (pos < buf.length && buf[pos] == '\n') pos++;
        int start = pos;
        int len = -1;
        Object l = d.get("Length");
        if (l instanceof Number) len = ((Number) l).intValue();
        else if (l instanceof Ref) {
            int save = pos;
            try { Object v = getObject(((Ref) l).num); if (v instanceof Number) len = ((Number) v).intValue(); } catch (Exception ignored) { }
            pos = save;
        }
        boolean ok = len >= 0 && start + len <= buf.length;
        if (ok) {
            int e = start + len;
            while (e < buf.length && ws(buf[e] & 0xff)) e++;
            ok = startsWith(buf, e, "endstream");
        }
        if (!ok) {
            int e = indexOf("endstream".getBytes(StandardCharsets.ISO_8859_1), start);
            if (e < 0) e = buf.length;
            int end = e;
            if (end > start && buf[end - 1] == '\n') end--;
            if (end > start && buf[end - 1] == '\r') end--;
            len = end - start;
        }
        Stream s = new Stream(d, Arrays.copyOfRange(buf, start, start + len));
        s.num = num; s.gen = gen;
        pos = start + len;
        return s;
    }

    private Object resolve(Object o) throws Exception {
        int guard = 0;
        while (o instanceof Ref && guard++ < 32) o = getObject(((Ref) o).num);
        return o;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dict(Object o) {
        if (o instanceof Map) return (Map<String, Object>) o;
        if (o instanceof Stream) return ((Stream) o).dict;
        return new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        if (o instanceof List) return (List<Object>) o;
        List<Object> l = new ArrayList<>();
        if (o != null) l.add(o);
        return l;
    }

    private static int num(Object o) { return o instanceof Number ? ((Number) o).intValue() : 0; }

    private static String nameOf(Object o) { return o instanceof Name ? ((Name) o).n : null; }

    // ------------------------------------------------------------------ filters
    private byte[] decode(Stream s) throws Exception {
        byte[] data = s.raw;
        if (fileKey != null && !s.decrypted && s.num >= 0 && s.num != encryptObj
                && !"XRef".equals(nameOf(s.dict.get("Type")))) {
            data = decryptBytes(data, s.num, s.gen);
        }
        List<Object> filters = list(resolve(s.dict.get("Filter")));
        List<Object> parms = list(resolve(s.dict.get("DecodeParms")));
        for (int i = 0; i < filters.size(); i++) {
            String f = nameOf(resolve(filters.get(i)));
            Map<String, Object> dp = i < parms.size() ? dict(resolve(parms.get(i))) : new HashMap<String, Object>();
            if ("FlateDecode".equals(f) || "Fl".equals(f)) data = predict(inflate(data), dp);
            else if ("ASCIIHexDecode".equals(f) || "AHx".equals(f)) data = asciiHex(data);
            else if ("ASCII85Decode".equals(f) || "A85".equals(f)) data = ascii85(data);
            else if ("Crypt".equals(f)) { /* identity */ }
            else if (f != null) throw new Exception("Unsupported filter " + f);
        }
        return data;
    }

    private static byte[] inflate(byte[] d) throws Exception {
        Inflater inf = new Inflater();
        inf.setInput(d);
        ByteArrayOutputStream o = new ByteArrayOutputStream(d.length * 3);
        byte[] b = new byte[16384];
        try {
            while (!inf.finished()) {
                int n = inf.inflate(b);
                if (n == 0) { if (inf.needsInput() || inf.needsDictionary()) break; }
                o.write(b, 0, n);
            }
        } catch (java.util.zip.DataFormatException e) {
            if (o.size() == 0) {   // try raw deflate
                Inflater raw = new Inflater(true);
                raw.setInput(d);
                while (!raw.finished()) { int n = raw.inflate(b); if (n == 0) break; o.write(b, 0, n); }
            }
        } finally { inf.end(); }
        return o.toByteArray();
    }

    private static byte[] predict(byte[] d, Map<String, Object> p) {
        int pred = num(p.get("Predictor"));
        if (pred < 10) return d;
        int colors = p.containsKey("Colors") ? num(p.get("Colors")) : 1;
        int bpc = p.containsKey("BitsPerComponent") ? num(p.get("BitsPerComponent")) : 8;
        int cols = p.containsKey("Columns") ? num(p.get("Columns")) : 1;
        int bpp = Math.max(1, colors * bpc / 8), row = (cols * colors * bpc + 7) / 8;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] prev = new byte[row];
        for (int r = 0; r * (row + 1) < d.length; r++) {
            int base = r * (row + 1);
            int type = d[base] & 0xff;
            byte[] cur = new byte[row];
            for (int i = 0; i < row && base + 1 + i < d.length; i++) {
                int x = d[base + 1 + i] & 0xff;
                int a = i >= bpp ? cur[i - bpp] & 0xff : 0, up = prev[i] & 0xff, c = i >= bpp ? prev[i - bpp] & 0xff : 0;
                int v;
                switch (type) {
                    case 1: v = x + a; break;
                    case 2: v = x + up; break;
                    case 3: v = x + ((a + up) >> 1); break;
                    case 4: { int pp = a + up - c, pa = Math.abs(pp - a), pb = Math.abs(pp - up), pc = Math.abs(pp - c);
                        v = x + (pa <= pb && pa <= pc ? a : pb <= pc ? up : c); break; }
                    default: v = x;
                }
                cur[i] = (byte) v;
            }
            o.write(cur, 0, row);
            prev = cur;
        }
        return o.toByteArray();
    }

    private static byte[] asciiHex(byte[] d) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int hi = -1;
        for (byte x : d) {
            if (x == '>') break;
            int h = Character.digit(x, 16);
            if (h < 0) continue;
            if (hi < 0) hi = h; else { o.write(hi * 16 + h); hi = -1; }
        }
        if (hi >= 0) o.write(hi * 16);
        return o.toByteArray();
    }

    private static byte[] ascii85(byte[] d) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        long acc = 0;
        int n = 0;
        for (int i = 0; i < d.length; i++) {
            int c = d[i] & 0xff;
            if (c == '~') break;
            if (ws(c)) continue;
            if (c == 'z' && n == 0) { o.write(0); o.write(0); o.write(0); o.write(0); continue; }
            acc = acc * 85 + (c - 33);
            if (++n == 5) {
                for (int k = 3; k >= 0; k--) o.write((int) (acc >> (8 * k)) & 0xff);
                acc = 0; n = 0;
            }
        }
        if (n > 0) {
            for (int k = n; k < 5; k++) acc = acc * 85 + 84;
            for (int k = 3; k >= 5 - n; k--) o.write((int) (acc >> (8 * k)) & 0xff);
        }
        return o.toByteArray();
    }

    // ------------------------------------------------------------------ encryption
    private static final byte[] PAD = {
            0x28, (byte) 0xBF, 0x4E, 0x5E, 0x4E, 0x75, (byte) 0x8A, 0x41, 0x64, 0x00, 0x4E, 0x56, (byte) 0xFF, (byte) 0xFA, 0x01, 0x08,
            0x2E, 0x2E, 0x00, (byte) 0xB6, (byte) 0xD0, 0x68, 0x3E, (byte) 0x80, 0x2F, 0x0C, (byte) 0xA9, (byte) 0xFE, 0x64, 0x53, 0x69, 0x7A};

    private void setupCrypt(Map<String, Object> e, String password) throws Exception {
        if (!"Standard".equals(nameOf(e.get("Filter")))) throw new Exception("Unsupported PDF security");
        cryptV = num(e.get("V"));
        cryptR = num(e.get("R"));
        byte[] O = ((Str) resolve(e.get("O"))).b, U = ((Str) resolve(e.get("U"))).b;
        if (cryptV >= 4) {
            Map<String, Object> cf = dict(resolve(e.get("CF")));
            String stmf = nameOf(e.get("StmF"));
            if (stmf != null && !"Identity".equals(stmf)) {
                String cfm = nameOf(dict(resolve(cf.get(stmf))).get("CFM"));
                aesStreams = "AESV2".equals(cfm) || "AESV3".equals(cfm);
                aesV3 = "AESV3".equals(cfm);
            }
        }
        if (cryptR >= 5) {
            byte[] OE = ((Str) resolve(e.get("OE"))).b, UE = ((Str) resolve(e.get("UE"))).b;
            for (String pw : candidates(password)) {
                byte[] p = pw.getBytes(StandardCharsets.UTF_8);
                if (p.length > 127) p = Arrays.copyOf(p, 127);
                if (Arrays.equals(hash2B(p, Arrays.copyOfRange(U, 32, 40), new byte[0]), Arrays.copyOf(U, 32))) {
                    fileKey = aesNoIv(hash2B(p, Arrays.copyOfRange(U, 40, 48), new byte[0]), UE);
                    aesStreams = true; aesV3 = true;
                    return;
                }
                byte[] u48 = Arrays.copyOf(U, 48);
                if (Arrays.equals(hash2B(p, Arrays.copyOfRange(O, 32, 40), u48), Arrays.copyOf(O, 32))) {
                    fileKey = aesNoIv(hash2B(p, Arrays.copyOfRange(O, 40, 48), u48), OE);
                    aesStreams = true; aesV3 = true;
                    return;
                }
            }
            throw new PasswordException(password.isEmpty() ? "Password needed" : "Wrong password");
        }
        int P = num(e.get("P"));
        int len = e.containsKey("Length") ? num(e.get("Length")) / 8 : 5;
        if (cryptR == 2) len = 5;
        len = Math.max(5, Math.min(16, len));
        byte[] id0 = new byte[0];
        List<Object> ids = list(resolve(trailer.get("ID")));
        if (!ids.isEmpty() && resolve(ids.get(0)) instanceof Str) id0 = ((Str) resolve(ids.get(0))).b;
        boolean encMeta = !Boolean.FALSE.equals(e.get("EncryptMetadata"));
        for (String pw : candidates(password)) {
            byte[] p = pw.getBytes(StandardCharsets.ISO_8859_1);
            byte[] key = userKey(p, O, P, id0, len, encMeta);
            if (checkUser(key, U, id0)) { fileKey = key; return; }
            // maybe it's the owner password: recover the user password from O
            byte[] ok = md5(padded(p));
            if (cryptR >= 3) for (int i = 0; i < 50; i++) ok = md5(Arrays.copyOf(ok, len));
            ok = Arrays.copyOf(ok, len);
            byte[] up = O.clone();
            if (cryptR == 2) up = rc4(ok, up);
            else for (int i = 19; i >= 0; i--) { byte[] k = ok.clone(); for (int j = 0; j < k.length; j++) k[j] ^= i; up = rc4(k, up); }
            key = userKey(unpad(up), O, P, id0, len, encMeta);
            if (checkUser(key, U, id0)) { fileKey = key; return; }
        }
        throw new PasswordException(password.isEmpty() ? "Password needed" : "Wrong password");
    }

    /** Try the password as typed, upper-case (PAN), and empty. */
    private static List<String> candidates(String pw) {
        List<String> l = new ArrayList<>();
        l.add(pw);
        if (!pw.toUpperCase().equals(pw)) l.add(pw.toUpperCase());
        if (!pw.toLowerCase().equals(pw)) l.add(pw.toLowerCase());
        if (!pw.isEmpty()) l.add("");
        return l;
    }

    private static byte[] padded(byte[] p) {
        byte[] r = new byte[32];
        int n = Math.min(32, p.length);
        System.arraycopy(p, 0, r, 0, n);
        System.arraycopy(PAD, 0, r, n, 32 - n);
        return r;
    }

    private static byte[] unpad(byte[] p) {
        for (int n = 0; n <= 32 && n <= p.length; n++) {
            boolean match = true;
            for (int i = n; i < 32 && i < p.length; i++) if (p[i] != PAD[i - n]) { match = false; break; }
            if (match) return Arrays.copyOf(p, n);
        }
        return p;
    }

    private byte[] userKey(byte[] pw, byte[] O, int P, byte[] id0, int len, boolean encMeta) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(padded(pw));
        md.update(O, 0, Math.min(32, O.length));
        md.update(new byte[]{(byte) P, (byte) (P >> 8), (byte) (P >> 16), (byte) (P >> 24)});
        md.update(id0);
        if (cryptR >= 4 && !encMeta) md.update(new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff});
        byte[] h = md.digest();
        if (cryptR >= 3) for (int i = 0; i < 50; i++) h = md5(Arrays.copyOf(h, len));
        return Arrays.copyOf(h, len);
    }

    private boolean checkUser(byte[] key, byte[] U, byte[] id0) throws Exception {
        if (cryptR == 2) return Arrays.equals(rc4(key, PAD), Arrays.copyOf(U, 32));
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(PAD);
        md.update(id0);
        byte[] x = rc4(key, md.digest());
        for (int i = 1; i <= 19; i++) { byte[] k = key.clone(); for (int j = 0; j < k.length; j++) k[j] ^= i; x = rc4(k, x); }
        return Arrays.equals(Arrays.copyOf(x, 16), Arrays.copyOf(U, 16));
    }

    private byte[] hash2B(byte[] pw, byte[] salt, byte[] udata) throws Exception {
        byte[] k = sha("SHA-256", cat(pw, salt, udata));
        if (cryptR == 5) return k;
        int i = 0;
        while (true) {
            byte[] one = cat(pw, k, udata);
            byte[] k1 = new byte[one.length * 64];
            for (int j = 0; j < 64; j++) System.arraycopy(one, 0, k1, j * one.length, one.length);
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Arrays.copyOf(k, 16), "AES"), new IvParameterSpec(Arrays.copyOfRange(k, 16, 32)));
            byte[] e = c.doFinal(k1);
            int sum = 0;
            for (int j = 0; j < 16; j++) sum += e[j] & 0xff;
            int m = sum % 3;
            k = sha(m == 0 ? "SHA-256" : m == 1 ? "SHA-384" : "SHA-512", e);
            i++;
            if (i >= 64 && (e[e.length - 1] & 0xff) <= i - 32) break;
        }
        return Arrays.copyOf(k, 32);
    }

    private static byte[] aesNoIv(byte[] key, byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
        return c.doFinal(Arrays.copyOf(data, 32));
    }

    private byte[] decryptBytes(byte[] data, int num, int gen) throws Exception {
        byte[] key;
        if (aesV3) key = fileKey;
        else {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(fileKey);
            md.update(new byte[]{(byte) num, (byte) (num >> 8), (byte) (num >> 16), (byte) gen, (byte) (gen >> 8)});
            if (aesStreams) md.update(new byte[]{0x73, 0x41, 0x6C, 0x54});
            key = Arrays.copyOf(md.digest(), Math.min(fileKey.length + 5, 16));
        }
        if (!aesStreams) return rc4(key, data);
        if (data.length < 32) return new byte[0];
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(Arrays.copyOf(data, 16)));
        int n = (data.length - 16) / 16 * 16;
        byte[] out = c.doFinal(data, 16, n);
        int padN = out.length > 0 ? out[out.length - 1] & 0xff : 0;
        if (padN >= 1 && padN <= 16) out = Arrays.copyOf(out, out.length - padN);
        return out;
    }

    private static byte[] rc4(byte[] key, byte[] data) {
        int[] s = new int[256];
        for (int i = 0; i < 256; i++) s[i] = i;
        for (int i = 0, j = 0; i < 256; i++) {
            j = (j + s[i] + (key[i % key.length] & 0xff)) & 0xff;
            int t = s[i]; s[i] = s[j]; s[j] = t;
        }
        byte[] out = new byte[data.length];
        for (int k = 0, i = 0, j = 0; k < data.length; k++) {
            i = (i + 1) & 0xff;
            j = (j + s[i]) & 0xff;
            int t = s[i]; s[i] = s[j]; s[j] = t;
            out[k] = (byte) (data[k] ^ s[(s[i] + s[j]) & 0xff]);
        }
        return out;
    }

    private static byte[] md5(byte[] b) throws Exception { return MessageDigest.getInstance("MD5").digest(b); }

    private static byte[] sha(String alg, byte[] b) throws Exception { return MessageDigest.getInstance(alg).digest(b); }

    private static byte[] cat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] r = new byte[n];
        int o = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, r, o, p.length); o += p.length; }
        return r;
    }

    // ------------------------------------------------------------------ fonts
    static final class Font {
        Map<Integer, String> toUni;
        int codeBytes = 1;
        String[] simple;       // 256 entries for simple fonts
    }

    private final Map<Object, Font> fontCache = new HashMap<>();
    private static final Charset CP1252 = Charset.forName("windows-1252");

    private Font font(Object fref) throws Exception {
        Object key = fref instanceof Ref ? (Object) ((Ref) fref).num : fref;
        if (key != null && fontCache.containsKey(key)) return fontCache.get(key);
        Map<String, Object> fd = dict(resolve(fref));
        Font f = new Font();
        String sub = nameOf(fd.get("Subtype"));
        Object tu = resolve(fd.get("ToUnicode"));
        if (tu instanceof Stream) f.toUni = cmap(new String(decode((Stream) tu), StandardCharsets.ISO_8859_1), f);
        if ("Type0".equals(sub)) {
            f.codeBytes = 2;
            if (f.toUni != null && f.toUni.size() > 0 && cmapOneByte) f.codeBytes = 1;
        } else {
            f.simple = new String[256];
            byte[] all = new byte[256];
            for (int i = 0; i < 256; i++) all[i] = (byte) i;
            String base = new String(all, CP1252);
            for (int i = 0; i < 256; i++) f.simple[i] = String.valueOf(base.charAt(i));
            Object enc = resolve(fd.get("Encoding"));
            if (enc instanceof Map) {
                List<Object> diffs = list(resolve(dict(enc).get("Differences")));
                int code = 0;
                for (Object o : diffs) {
                    o = resolve(o);
                    if (o instanceof Number) code = ((Number) o).intValue();
                    else if (o instanceof Name && code < 256) { String g = glyph(((Name) o).n); if (g != null) f.simple[code] = g; code++; }
                }
            }
        }
        if (key != null) fontCache.put(key, f);
        return f;
    }

    private boolean cmapOneByte;

    private Map<Integer, String> cmap(String s, Font f) {
        Map<Integer, String> m = new HashMap<>();
        cmapOneByte = false;
        Matcher cs = Pattern.compile("begincodespacerange\\s*<([0-9A-Fa-f]+)>").matcher(s);
        if (cs.find() && cs.group(1).length() <= 2) cmapOneByte = true;
        Matcher bc = Pattern.compile("beginbfchar(.*?)endbfchar", Pattern.DOTALL).matcher(s);
        Pattern pair = Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]*)>");
        while (bc.find()) {
            Matcher p = pair.matcher(bc.group(1));
            while (p.find()) m.put(Integer.parseInt(p.group(1), 16), utf16(p.group(2)));
        }
        Matcher br = Pattern.compile("beginbfrange(.*?)endbfrange", Pattern.DOTALL).matcher(s);
        Pattern rng = Pattern.compile("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*(<[0-9A-Fa-f]*>|\\[[^\\]]*\\])");
        while (br.find()) {
            Matcher p = rng.matcher(br.group(1));
            while (p.find()) {
                int lo = Integer.parseInt(p.group(1), 16), hi = Integer.parseInt(p.group(2), 16);
                String dst = p.group(3);
                if (dst.startsWith("[")) {
                    Matcher h = Pattern.compile("<([0-9A-Fa-f]*)>").matcher(dst);
                    int c = lo;
                    while (h.find() && c <= hi) m.put(c++, utf16(h.group(1)));
                } else {
                    String base = dst.substring(1, dst.length() - 1);
                    if (base.isEmpty()) continue;
                    long v = Long.parseLong(base.length() > 8 ? base.substring(base.length() - 8) : base, 16);
                    String prefix = base.length() > 4 ? utf16(base.substring(0, base.length() - 4)) : "";
                    for (int c = lo; c <= hi && c - lo < 65536; c++) {
                        int last = (int) ((v & 0xFFFF) + (c - lo));
                        m.put(c, prefix + new String(Character.toChars(Math.min(last, 0x10FFFF))));
                    }
                }
            }
        }
        return m;
    }

    private static String utf16(String hex) {
        if (hex.isEmpty()) return "";
        byte[] b = new byte[hex.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        if (b.length == 1) return String.valueOf((char) (b[0] & 0xff));
        return new String(b, StandardCharsets.UTF_16BE);
    }

    private static final Map<String, String> GLYPHS = new HashMap<>();
    static {
        String[] g = {"space", " ", "period", ".", "comma", ",", "hyphen", "-", "minus", "-", "colon", ":", "semicolon", ";",
                "slash", "/", "backslash", "\\", "parenleft", "(", "parenright", ")", "bracketleft", "[", "bracketright", "]",
                "ampersand", "&", "percent", "%", "at", "@", "underscore", "_", "quotesingle", "'", "quotedbl", "\"",
                "quoteright", "'", "quoteleft", "'", "plus", "+", "equal", "=", "asterisk", "*", "numbersign", "#",
                "dollar", "$", "less", "<", "greater", ">", "question", "?", "exclam", "!", "endash", "-", "emdash", "-",
                "bar", "|", "zero", "0", "one", "1", "two", "2", "three", "3", "four", "4", "five", "5", "six", "6",
                "seven", "7", "eight", "8", "nine", "9", "rupee", "₹", "rupeeindian", "₹", "bullet", "•"};
        for (int i = 0; i < g.length; i += 2) GLYPHS.put(g[i], g[i + 1]);
    }

    private static String glyph(String n) {
        if (GLYPHS.containsKey(n)) return GLYPHS.get(n);
        if (n.length() == 1) return n;
        if (n.startsWith("uni") && n.length() >= 7) {
            try { return new String(Character.toChars(Integer.parseInt(n.substring(3, 7), 16))); } catch (Exception ignored) { }
        }
        if (n.startsWith("u") && n.length() >= 5) {
            try { return new String(Character.toChars(Integer.parseInt(n.substring(1), 16))); } catch (Exception ignored) { }
        }
        return null;
    }

    private static String decodeText(Font f, byte[] b) {
        StringBuilder sb = new StringBuilder();
        if (f == null) return new String(b, CP1252);
        if (f.codeBytes == 2) {
            for (int i = 0; i + 1 < b.length; i += 2) {
                int c = ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff);
                String s = f.toUni != null ? f.toUni.get(c) : null;
                sb.append(s != null ? s : "?");
            }
        } else {
            for (byte x : b) {
                int c = x & 0xff;
                String s = f.toUni != null ? f.toUni.get(c) : null;
                if (s == null && f.simple != null) s = f.simple[c];
                sb.append(s != null ? s : "?");
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ pages & text
    static final class Chunk {
        float x, y, end, size;
        String s;
        int page;
        boolean placed;   // explicitly positioned (Td/Tm/new line)
    }

    private List<String> lines() throws Exception {
        Map<String, Object> root = dict(resolve(trailer.get("Root")));
        List<Map<String, Object>> pages = new ArrayList<>();
        collectPages(resolve(root.get("Pages")), pages, null, 0);
        List<String> out = new ArrayList<>();
        int pn = 0;
        for (Map<String, Object> pg : pages) {
            List<Chunk> chunks = new ArrayList<>();
            Map<String, Object> res = dict(resolve(pg.get("Resources")));
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            for (Object c : list(resolve(pg.get("Contents")))) {
                Object s = resolve(c);
                if (s instanceof Stream) { content.write(decode((Stream) s)); content.write('\n'); }
            }
            run(content.toByteArray(), res, new float[]{1, 0, 0, 1, 0, 0}, chunks, pn, 0);
            out.addAll(assemble(chunks));
            out.add("");
            pn++;
        }
        return out;
    }

    private void collectPages(Object node, List<Map<String, Object>> out, Object inheritedRes, int depth) throws Exception {
        if (depth > 50) return;
        Map<String, Object> d = dict(node);
        Object res = d.containsKey("Resources") ? d.get("Resources") : inheritedRes;
        if (d.containsKey("Kids")) {
            for (Object k : list(resolve(d.get("Kids")))) collectPages(resolve(k), out, res, depth + 1);
        } else {
            Map<String, Object> page = new HashMap<>(d);
            if (!page.containsKey("Resources") && res != null) page.put("Resources", res);
            out.add(page);
        }
    }

    private static float[] mul(float[] a, float[] b) {
        return new float[]{a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3],
                a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3],
                a[4] * b[0] + a[5] * b[2] + b[4], a[4] * b[1] + a[5] * b[3] + b[5]};
    }

    private static float f(Object o) { return o instanceof Number ? ((Number) o).floatValue() : 0; }

    /** Interprets a content stream, collecting positioned text. */
    private void run(byte[] cs, Map<String, Object> res, float[] ctm0, List<Chunk> out, int page, int depth) throws Exception {
        if (depth > 6) return;
        Map<String, Object> fonts = dict(resolve(res.get("Font")));
        Map<String, Object> xobj = dict(resolve(res.get("XObject")));
        List<float[]> stack = new ArrayList<>();
        float[] ctm = ctm0.clone();
        float[] tm = {1, 0, 0, 1, 0, 0}, tlm = tm.clone();
        float fs = 10, lead = 0, th = 1;
        Font font = null;
        boolean placed = true;
        List<Object> ops = new ArrayList<>();
        int save = pos;
        pos = 0;
        while (pos < cs.length) {
            Object o = parse(cs, true);
            if (o == null) { if (pos >= cs.length) break; continue; }
            if (!(o instanceof Op)) { ops.add(o); continue; }
            String op = ((Op) o).op;
            try {
                switch (op) {
                    case "q": stack.add(ctm.clone()); break;
                    case "Q": if (!stack.isEmpty()) ctm = stack.remove(stack.size() - 1); break;
                    case "cm": if (ops.size() >= 6) {
                        int n = ops.size();
                        ctm = mul(new float[]{f(ops.get(n - 6)), f(ops.get(n - 5)), f(ops.get(n - 4)), f(ops.get(n - 3)), f(ops.get(n - 2)), f(ops.get(n - 1))}, ctm);
                    } break;
                    case "BT": tm = new float[]{1, 0, 0, 1, 0, 0}; tlm = tm.clone(); placed = true; break;
                    case "Tf": if (ops.size() >= 2) {
                        Object fn = ops.get(ops.size() - 2);
                        fs = f(ops.get(ops.size() - 1));
                        if (fn instanceof Name) font = font(fonts.get(((Name) fn).n));
                    } break;
                    case "TL": if (!ops.isEmpty()) lead = f(ops.get(ops.size() - 1)); break;
                    case "Tz": if (!ops.isEmpty()) th = f(ops.get(ops.size() - 1)) / 100f; break;
                    case "Td": case "TD": if (ops.size() >= 2) {
                        float tx = f(ops.get(ops.size() - 2)), ty = f(ops.get(ops.size() - 1));
                        if (op.equals("TD")) lead = -ty;
                        tlm = mul(new float[]{1, 0, 0, 1, tx, ty}, tlm); tm = tlm.clone(); placed = true;
                    } break;
                    case "Tm": if (ops.size() >= 6) {
                        int n = ops.size();
                        tlm = new float[]{f(ops.get(n - 6)), f(ops.get(n - 5)), f(ops.get(n - 4)), f(ops.get(n - 3)), f(ops.get(n - 2)), f(ops.get(n - 1))};
                        tm = tlm.clone(); placed = true;
                    } break;
                    case "T*": tlm = mul(new float[]{1, 0, 0, 1, 0, -lead}, tlm); tm = tlm.clone(); placed = true; break;
                    case "Tj": case "'": case "\"": case "TJ": {
                        if (op.equals("'") || op.equals("\"")) { tlm = mul(new float[]{1, 0, 0, 1, 0, -lead}, tlm); tm = tlm.clone(); placed = true; }
                        Object arg = ops.isEmpty() ? null : ops.get(ops.size() - 1);
                        StringBuilder sb = new StringBuilder();
                        int chars = 0;
                        if (arg instanceof Str) { String t = decodeText(font, ((Str) arg).b); sb.append(t); chars += t.length(); }
                        else if (arg instanceof List) {
                            for (Object e : (List<?>) arg) {
                                if (e instanceof Str) { String t = decodeText(font, ((Str) e).b); sb.append(t); chars += t.length(); }
                                else if (e instanceof Number && ((Number) e).floatValue() < -180) { sb.append(' '); chars++; }
                            }
                        }
                        if (sb.length() > 0) {
                            float[] m = mul(tm, ctm);
                            float scale = (float) Math.sqrt(m[0] * m[0] + m[1] * m[1]);
                            Chunk c = new Chunk();
                            c.x = m[4]; c.y = m[5];
                            c.size = Math.max(1, fs * scale);
                            float w = chars * fs * 0.5f * th;
                            c.end = c.x + w * scale;
                            c.s = sb.toString();
                            c.page = page;
                            c.placed = placed;
                            out.add(c);
                            tm = mul(new float[]{1, 0, 0, 1, w, 0}, tm);
                        }
                        placed = false;
                    } break;
                    case "Do": if (!ops.isEmpty() && ops.get(ops.size() - 1) instanceof Name) {
                        Object xo = resolve(xobj.get(((Name) ops.get(ops.size() - 1)).n));
                        if (xo instanceof Stream && "Form".equals(nameOf(((Stream) xo).dict.get("Subtype")))) {
                            Stream st = (Stream) xo;
                            float[] fm = {1, 0, 0, 1, 0, 0};
                            List<Object> ma = list(resolve(st.dict.get("Matrix")));
                            if (ma.size() == 6) for (int i = 0; i < 6; i++) fm[i] = f(ma.get(i));
                            Map<String, Object> r2 = st.dict.containsKey("Resources") ? dict(resolve(st.dict.get("Resources"))) : res;
                            int p0 = pos;
                            run(decode(st), r2, mul(fm, ctm), out, page, depth + 1);
                            pos = p0;
                        }
                    } break;
                    case "BI": {   // inline image: skip to EI
                        int e = pos;
                        while (e + 2 < cs.length && !(ws(cs[e] & 0xff) && cs[e + 1] == 'E' && cs[e + 2] == 'I' && (e + 3 >= cs.length || ws(cs[e + 3] & 0xff)))) e++;
                        pos = Math.min(cs.length, e + 3);
                    } break;
                    default: break;
                }
            } catch (Exception ignored) { }
            ops.clear();
        }
        pos = save;
    }

    /** Groups chunks into lines (top to bottom, left to right), inserting spaces at gaps. */
    private static List<String> assemble(List<Chunk> chunks) {
        List<String> lines = new ArrayList<>();
        if (chunks.isEmpty()) return lines;
        List<Chunk> l = new ArrayList<>(chunks);
        Collections.sort(l, new Comparator<Chunk>() {
            @Override public int compare(Chunk a, Chunk b) {
                float tol = Math.max(1.5f, Math.min(a.size, b.size) * 0.35f);
                if (Math.abs(a.y - b.y) > tol) return Float.compare(b.y, a.y);
                return Float.compare(a.x, b.x);
            }
        });
        StringBuilder cur = new StringBuilder();
        Chunk prev = null;
        float lineY = 0;
        for (Chunk c : l) {
            float tol = Math.max(1.5f, c.size * 0.35f);
            if (prev == null || Math.abs(c.y - lineY) > tol) {
                if (prev != null) lines.add(cur.toString().replaceAll("\\s+", " ").trim());
                cur.setLength(0);
                lineY = c.y;
            } else {
                float gap = c.x - prev.end;
                boolean space = prev.s.length() > 1 && c.placed ? gap > -prev.size * 2 || c.x > prev.x : gap > prev.size * 0.12f;
                if (space && cur.length() > 0 && cur.charAt(cur.length() - 1) != ' ' && !c.s.startsWith(" ")) cur.append(' ');
            }
            cur.append(c.s);
            prev = c;
        }
        if (cur.length() > 0) lines.add(cur.toString().replaceAll("\\s+", " ").trim());
        return lines;
    }

    // ------------------------------------------------------------------ byte helpers
    private static boolean startsWith(byte[] b, int p, String s) {
        if (p + s.length() > b.length) return false;
        for (int i = 0; i < s.length(); i++) if (b[p + i] != s.charAt(i)) return false;
        return true;
    }

    private int lastIndexOf(byte[] pat, int from) {
        for (int i = Math.min(from, buf.length) - pat.length; i >= 0; i--) {
            boolean ok = true;
            for (int j = 0; j < pat.length; j++) if (buf[i + j] != pat[j]) { ok = false; break; }
            if (ok) return i;
        }
        return -1;
    }

    private int indexOf(byte[] pat, int from) {
        for (int i = from; i <= buf.length - pat.length; i++) {
            boolean ok = true;
            for (int j = 0; j < pat.length; j++) if (buf[i + j] != pat[j]) { ok = false; break; }
            if (ok) return i;
        }
        return -1;
    }
}
