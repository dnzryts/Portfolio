"""Package ULIP Tracker using the helpers from the Dial Clock packager."""
import sys, os, io, math
sys.path.insert(0, "/home/claude/app")
import pack as P
from PIL import Image, ImageDraw

P.PKG = "com.uliptracker"
P.ATTR_IDS["windowSoftInputMode"] = 0x0101022B
E, T_STR, T_INT, T_BOOL, T_REF = P.E, P.T_STR, P.T_INT, P.T_BOOL, P.T_REF
P.ATTR_IDS.update({"mimeType": 0x01010026, "launchMode": 0x0101001D, "id": 0x010100D0, "layout_width": 0x010100F4, "layout_height": 0x010100F5, "scaleType": 0x0101011D,
                   "minWidth": 0x0101013F, "minHeight": 0x01010140, "updatePeriodMillis": 0x01010250,
                   "initialLayout": 0x01010251, "resizeMode": 0x01010363, "widgetCategory": 0x010103C4,
                   "previewImage": 0x010102DA, "minResizeWidth": 0x01010395, "minResizeHeight": 0x01010396,
                   "contentDescription": 0x01010273})
T_DIM, T_HEX = 0x05, 0x11


def dp(v):
    return (v << 8) | 1          # TYPE_DIMENSION, COMPLEX_UNIT_DIP, radix 23p0


def widget_layout_def():
  return E("ImageView", [("a", "id", T_REF, IMG),
                                ("a", "layout_width", T_INT, 0xFFFFFFFF), ("a", "layout_height", T_INT, 0xFFFFFFFF),
                                ("a", "scaleType", T_INT, 1), ("a", "contentDescription", T_STR, "Portfolio summary")])
def widget_info_def():
  return E("appwidget-provider", [("a", "minWidth", T_DIM, dp(250)), ("a", "minHeight", T_DIM, dp(110)),
                                       ("a", "minResizeWidth", T_DIM, dp(180)), ("a", "minResizeHeight", T_DIM, dp(90)),
                                       ("a", "updatePeriodMillis", T_INT, 1800000), ("a", "initialLayout", T_REF, LAYOUT),
                                       ("a", "resizeMode", T_HEX, 3), ("a", "widgetCategory", T_HEX, 1),
                                       ("a", "previewImage", T_REF, ICON)])


def build_arsc(res):
    """res: list of (type, key, value); value is a file path (str) or False (id entry).
    Types are numbered in order of first appearance; entries within a type in list order."""
    import struct
    files = [v for _, _, v in res if isinstance(v, str)]
    gpool = P.string_pool(files)
    types = []
    for t, _, _ in res:
        if t not in types: types.append(t)
    keys = [k for _, k, _ in res]
    tpool = P.string_pool(types)
    kpool = P.string_pool(keys)
    config = struct.pack("<I", 64) + b"\x00" * 60
    hdr = 20 + len(config)
    chunks = b""
    for ti, t in enumerate(types):
        tid = ti + 1
        ents = [(i, r) for i, r in enumerate(res) if r[0] == t]
        n = len(ents)
        chunks += struct.pack("<HHIBBHI", 0x0202, 16, 16 + 4 * n, tid, 0, 0, n) + b"\x00" * (4 * n)
        body = b""
        offs = []
        for i, (_, key, v) in ents:
            offs.append(len(body))
            val = struct.pack("<HBBI", 8, 0, T_STR, files.index(v)) if isinstance(v, str) else struct.pack("<HBBI", 8, 0, 0x12, 0)
            body += struct.pack("<HHI", 8, 0, keys.index(key)) + val
        es = hdr + 4 * n
        chunks += struct.pack("<HHIBBHII", 0x0201, hdr, es + len(body), tid, 0, 0, n, es) + config + \
            b"".join(struct.pack("<I", o) for o in offs) + body
    name = P.PKG.encode("utf-16-le").ljust(256, b"\x00")
    body = tpool + kpool + chunks
    pkg = struct.pack("<HHII", 0x0200, 288, 288 + len(body), 0x7F) + name + \
        struct.pack("<IIIII", 288, len(types), 288 + len(tpool), len(keys), 0) + body
    payload = gpool + pkg
    return struct.pack("<HHII", 0x0002, 12, 12 + len(payload), 1) + payload


RES = [("drawable", "icon", "res/drawable/icon.png"),
       ("drawable", "ic_fg", "res/drawable/ic_fg.png"),
       ("drawable", "ic_bg", "res/drawable/ic_bg.png"),
       ("drawable", "ic_mono", "res/drawable/ic_mono.png"),
       ("mipmap", "ic_stat", "res/mipmap/ic_stat.png"),
       ("mipmap", "ic_launcher", "res/mipmap/ic_launcher.xml"),
       ("layout", "widget", "res/layout/widget.xml"),
       ("xml", "widget_info", "res/xml/widget_info.xml"),
       ("id", "img", False)]


def rid(t, k):
    types = []
    for x, _, _ in RES:
        if x not in types: types.append(x)
    ents = [r for r in RES if r[0] == t]
    return 0x7F000000 | ((types.index(t) + 1) << 16) | [r[1] for r in ents].index(k)


ICON, STAT, LAYOUT, WINFO, IMG, LAUNCHER = (rid("drawable", "icon"), rid("mipmap", "ic_stat"), rid("layout", "widget"),
                                            rid("xml", "widget_info"), rid("id", "img"), rid("mipmap", "ic_launcher"))
P.ATTR_IDS.update({"drawable": 0x01010199, "roundIcon": 0x0101052C})


manifest = E("manifest", [(None, "package", T_STR, P.PKG),
                          ("a", "versionCode", T_INT, 40), ("a", "versionName", T_STR, "9.1.3")], [
    E("uses-sdk", [("a", "minSdkVersion", T_INT, 26), ("a", "targetSdkVersion", T_INT, 34)]),
    E("uses-permission", [("a", "name", T_STR, "android.permission.INTERNET")]),
    E("uses-permission", [("a", "name", T_STR, "android.permission.ACCESS_NETWORK_STATE")]),
    E("uses-permission", [("a", "name", T_STR, "android.permission.USE_BIOMETRIC")]),
    E("uses-permission", [("a", "name", T_STR, "android.permission.POST_NOTIFICATIONS")]),
    E("uses-permission", [("a", "name", T_STR, "android.permission.RECEIVE_BOOT_COMPLETED")]),
    E("uses-permission", [("a", "name", T_STR, "android.permission.BLUETOOTH")]),
    E("uses-permission", [("a", "name", T_STR, "android.permission.BLUETOOTH_CONNECT")]),
    E("application", [("a", "theme", T_REF, 0x0103022E),  # Theme.Material.NoActionBar
                      ("a", "label", T_STR, "Portfolio"), ("a", "icon", T_REF, LAUNCHER), ("a", "roundIcon", T_REF, LAUNCHER)], [
        E("activity", [("a", "name", T_STR, P.PKG + ".MainActivity"), ("a", "exported", T_BOOL, True),
                       ("a", "launchMode", T_INT, 2),     # singleTask: shared files open in the running app
                       ("a", "windowSoftInputMode", T_INT, 0x10)], [
            E("intent-filter", [], [
                E("action", [("a", "name", T_STR, "android.intent.action.MAIN")]),
                E("category", [("a", "name", T_STR, "android.intent.category.LAUNCHER")])]),
            E("intent-filter", [], [
                E("action", [("a", "name", T_STR, "android.intent.action.SEND")]),
                E("action", [("a", "name", T_STR, "android.intent.action.VIEW")]),
                E("category", [("a", "name", T_STR, "android.intent.category.DEFAULT")])] +
              [E("data", [("a", "mimeType", T_STR, m)]) for m in (
                  "application/pdf", "text/csv", "text/comma-separated-values",
                  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.ms-excel",
                  "application/octet-stream")])]),
        E("service", [("a", "name", T_STR, P.PKG + ".AlertJob"), ("a", "exported", T_BOOL, False),
                      ("a", "permission", T_STR, "android.permission.BIND_JOB_SERVICE")]),
        E("receiver", [("a", "name", T_STR, P.PKG + ".Widget"), ("a", "exported", T_BOOL, True),
                       ("a", "label", T_STR, "Portfolio")], [
            E("intent-filter", [], [E("action", [("a", "name", T_STR, "android.appwidget.action.APPWIDGET_UPDATE")])]),
            E("meta-data", [("a", "name", T_STR, "android.appwidget.provider"), ("a", "resource", T_REF, WINFO)])]),
    ]),
])



def stat_icon(size=96):
    """White-on-transparent status bar icon (rising line)."""
    s = 4; S = size * s
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    pts = [(0.12, 0.74), (0.36, 0.50), (0.54, 0.62), (0.86, 0.26)]
    pts = [(x * S, y * S) for x, y in pts]
    d.line(pts, fill=(255, 255, 255, 255), width=int(S * 0.09), joint="curve")
    d.polygon([(S * 0.66, S * 0.20), (S * 0.92, S * 0.18), (S * 0.90, S * 0.44)], fill=(255, 255, 255, 255))
    im = im.resize((size, size), Image.LANCZOS)
    b = io.BytesIO(); im.save(b, "PNG", optimize=True); return b.getvalue()


def _logo(draw, S, cx, cy, sc, ring=(255, 255, 255, 255), line=(76, 215, 135, 255)):
    """Ring (300°) with a rising growth line and an end dot. sc = logo half-size in px."""
    import math
    w = int(sc * 0.11)
    rr = sc * 0.56
    draw.arc([cx - rr, cy - rr, cx + rr, cy + rr], start=120, end=420, fill=ring, width=w)
    pts = [(-0.42, 0.30), (-0.12, 0.02), (0.08, 0.16), (0.42, -0.30)]
    pts = [(cx + x * sc, cy + y * sc) for x, y in pts]
    draw.line(pts, fill=line, width=w, joint="curve")
    for x, y in (pts[0],):
        draw.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill=line)
    x, y = pts[-1]
    r = sc * 0.10
    draw.ellipse([x - r, y - r, x + r, y + r], fill=line)


def _gradient(S, c1=(47, 111, 224), c2=(124, 92, 224)):
    g = Image.new("RGBA", (S, S))
    px = g.load()
    for yy in range(S):
        for xx in range(S):
            t = (xx + yy) / (2 * S - 2)
            px[xx, yy] = tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3)) + (255,)
    return g


def icon(size=192):
    """Legacy/round icon: rounded-square gradient badge with the logo."""
    s = 2; S = size * s
    bg = _gradient(S)
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=S * 0.26, fill=255)
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    im.paste(bg, (0, 0), mask)
    _logo(ImageDraw.Draw(im), S, S / 2, S / 2, S * 0.36)
    im = im.resize((size, size), Image.LANCZOS)
    b = io.BytesIO(); im.save(b, "PNG", optimize=True); return b.getvalue()


def adaptive_layers(px=432):
    """108dp adaptive icon layers at xxxhdpi: background gradient, foreground logo in the 66dp safe zone, monochrome."""
    S = px * 2
    bg = _gradient(S).resize((px, px), Image.LANCZOS)
    fg = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    _logo(ImageDraw.Draw(fg), S, S / 2, S / 2, S * 0.31)
    fg = fg.resize((px, px), Image.LANCZOS)
    mono = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    _logo(ImageDraw.Draw(mono), S, S / 2, S / 2, S * 0.31, ring=(255, 255, 255, 255), line=(255, 255, 255, 255))
    mono = mono.resize((px, px), Image.LANCZOS)
    out = []
    for im in (fg, bg, mono):
        b = io.BytesIO(); im.save(b, "PNG", optimize=True); out.append(b.getvalue())
    return out


if __name__ == "__main__":
    dex, out = sys.argv[1], sys.argv[2]
    fg, bgl, mono = adaptive_layers()
    adaptive = E("adaptive-icon", [], [
        E("background", [("a", "drawable", T_REF, rid("drawable", "ic_bg"))]),
        E("foreground", [("a", "drawable", T_REF, rid("drawable", "ic_fg"))]),
        E("monochrome", [("a", "drawable", T_REF, rid("drawable", "ic_mono"))])])
    data = {"res/drawable/icon.png": icon(), "res/drawable/ic_fg.png": fg, "res/drawable/ic_bg.png": bgl,
            "res/drawable/ic_mono.png": mono, "res/mipmap/ic_stat.png": stat_icon(),
            "res/mipmap/ic_launcher.xml": P.build_axml(adaptive),
            "res/layout/widget.xml": P.build_axml(widget_layout_def()),
            "res/xml/widget_info.xml": P.build_axml(widget_info_def())}
    files = [("AndroidManifest.xml", P.build_axml(manifest), False),
             ("classes.dex", open(dex, "rb").read(), False),
             ("resources.arsc", build_arsc(RES), True)]
    for path, blob in data.items():
        files.append((path, blob, path.endswith(".png")))
    apk = P.build_zip(files)
    key, cert = P.load_or_make_key(os.path.join(os.path.dirname(os.path.abspath(__file__)), "signing"))
    open(out, "wb").write(P.sign_v2(apk, key, cert))
    print("wrote", out, os.path.getsize(out))
    if len(sys.argv) > 3:   # export icon PNGs for the Android Studio project
        d = sys.argv[3]
        for path, blob in data.items():
            if path.endswith(".png"):
                os.makedirs(os.path.join(d, os.path.dirname(path)), exist_ok=True)
                open(os.path.join(d, path), "wb").write(blob)
