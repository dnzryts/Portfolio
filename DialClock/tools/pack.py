"""Package classes.dex into a signed APK (v2 signature) without the Android SDK."""
import struct, hashlib, zipfile, io, os, sys, datetime
from PIL import Image, ImageDraw
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa, padding

PKG = "com.dialclock"
LABEL = "Dial Clock"
ICON_ID = 0x7F010000
WALL_ID = 0x7F020000

# ---------------------------------------------------------------- string pool
def string_pool(strings):
    data = b""
    offs = []
    for s in strings:
        offs.append(len(data))
        u = s.encode("utf-16-le")
        data += struct.pack("<H", len(s)) + u + b"\x00\x00"
    while len(data) % 4:
        data += b"\x00"
    hdr = 28
    strings_start = hdr + 4 * len(strings)
    size = strings_start + len(data)
    return struct.pack("<HHIIIIII", 0x0001, hdr, size, len(strings), 0, 0, strings_start, 0) + \
        b"".join(struct.pack("<I", o) for o in offs) + data

# ---------------------------------------------------------------- binary XML
ANDROID_NS = "http://schemas.android.com/apk/res/android"
ATTR_IDS = {
    "theme": 0x01010000, "label": 0x01010001, "icon": 0x01010002, "name": 0x01010003,
    "permission": 0x01010006, "exported": 0x01010010, "minSdkVersion": 0x0101020C,
    "versionCode": 0x0101021B, "versionName": 0x0101021C, "targetSdkVersion": 0x01010270,
    "resource": 0x01010025, "thumbnail": 0x010102A5, "required": 0x0101028E,
}
T_REF, T_STR, T_INT, T_BOOL = 0x01, 0x03, 0x10, 0x12

def E(tag, attrs=(), children=()):
    return (tag, list(attrs), list(children))

manifest = E("manifest", [(None, "package", T_STR, PKG),
                          ("a", "versionCode", T_INT, 3), ("a", "versionName", T_STR, "1.2")], [
    E("uses-sdk", [("a", "minSdkVersion", T_INT, 24), ("a", "targetSdkVersion", T_INT, 34)]),
    E("uses-feature", [("a", "name", T_STR, "android.software.live_wallpaper"),
                       ("a", "required", T_BOOL, False)]),
    E("application", [("a", "theme", T_REF, 0x01030007),  # Theme.Black.NoTitleBar.Fullscreen
                      ("a", "label", T_STR, LABEL), ("a", "icon", T_REF, ICON_ID)], [
        E("activity", [("a", "name", T_STR, PKG + ".MainActivity"), ("a", "exported", T_BOOL, True)], [
            E("intent-filter", [], [
                E("action", [("a", "name", T_STR, "android.intent.action.MAIN")]),
                E("category", [("a", "name", T_STR, "android.intent.category.LAUNCHER")])])]),
        E("service", [("a", "name", T_STR, PKG + ".ClockDream"), ("a", "exported", T_BOOL, True),
                      ("a", "permission", T_STR, "android.permission.BIND_DREAM_SERVICE")], [
            E("intent-filter", [], [
                E("action", [("a", "name", T_STR, "android.service.dreams.DreamService")]),
                E("category", [("a", "name", T_STR, "android.intent.category.DEFAULT")])])]),
        E("service", [("a", "name", T_STR, PKG + ".ClockWallpaper"), ("a", "label", T_STR, LABEL),
                      ("a", "exported", T_BOOL, True),
                      ("a", "permission", T_STR, "android.permission.BIND_WALLPAPER")], [
            E("intent-filter", [], [
                E("action", [("a", "name", T_STR, "android.service.wallpaper.WallpaperService")])]),
            E("meta-data", [("a", "name", T_STR, "android.service.wallpaper"),
                            ("a", "resource", T_REF, WALL_ID)])]),
    ]),
])

wallpaper_xml = E("wallpaper", [("a", "thumbnail", T_REF, ICON_ID)])

def build_axml(root):
    # attribute names with resource ids go first (resource map order)
    attr_names = []
    def walk(n):
        for ns, name, *_ in n[1]:
            if ns and name not in attr_names:
                attr_names.append(name)
        for c in n[2]:
            walk(c)
    walk(root)
    strings = list(attr_names)
    def idx(s):
        if s not in strings:
            strings.append(s)
        return strings.index(s)
    body = b""
    i_prefix, i_uri = idx("android"), idx(ANDROID_NS)
    body += struct.pack("<HHIIIII", 0x0100, 16, 24, 1, 0xFFFFFFFF, i_prefix, i_uri)
    def emit(n):
        nonlocal body
        tag, attrs, children = n
        a_bin = b""
        for ns, name, typ, val in attrs:
            ns_i = i_uri if ns else 0xFFFFFFFF
            if typ == T_STR:
                si = idx(val); raw, data = si, si
            elif typ == T_BOOL:
                raw, data = 0xFFFFFFFF, 0xFFFFFFFF if val else 0
            else:
                raw, data = 0xFFFFFFFF, val
            a_bin += struct.pack("<IIIHBBI", ns_i, idx(name), raw, 8, 0, typ, data)
        t = idx(tag)
        body += struct.pack("<HHIII", 0x0102, 16, 36 + len(a_bin), 1, 0xFFFFFFFF)
        body += struct.pack("<IIHHHHHH", 0xFFFFFFFF, t, 20, 20, len(attrs), 0, 0, 0) + a_bin
        for c in children:
            emit(c)
        body += struct.pack("<HHIIIII", 0x0103, 16, 24, 1, 0xFFFFFFFF, 0xFFFFFFFF, t)
    emit(root)
    body += struct.pack("<HHIIIII", 0x0101, 16, 24, 1, 0xFFFFFFFF, i_prefix, i_uri)
    pool = string_pool(strings)
    resmap = struct.pack("<HHI", 0x0180, 8, 8 + 4 * len(attr_names)) + \
        b"".join(struct.pack("<I", ATTR_IDS[n]) for n in attr_names)
    payload = pool + resmap + body
    return struct.pack("<HHI", 0x0003, 8, 8 + len(payload)) + payload

# ---------------------------------------------------------------- resources.arsc
def build_arsc(res):
    """res: list of (type_name, key_name, file_path); type i gets id i+1, entry 0."""
    gpool = string_pool([r[2] for r in res])
    tpool = string_pool([r[0] for r in res])
    kpool = string_pool([r[1] for r in res])
    chunks = b""
    config = struct.pack("<I", 64) + b"\x00" * 60
    hdr = 20 + len(config)
    for i in range(len(res)):
        tid = i + 1
        chunks += struct.pack("<HHIBBHI", 0x0202, 16, 20, tid, 0, 0, 1) + struct.pack("<I", 0)
        entry = struct.pack("<HHI", 8, 0, i) + struct.pack("<HBBI", 8, 0, T_STR, i)
        entries_start = hdr + 4
        chunks += struct.pack("<HHIBBHII", 0x0201, hdr, entries_start + len(entry), tid, 0, 0, 1,
                              entries_start) + config + struct.pack("<I", 0) + entry
    name = PKG.encode("utf-16-le").ljust(256, b"\x00")
    pkg_hdr = 288
    pkg_body = tpool + kpool + chunks
    pkg = struct.pack("<HHII", 0x0200, pkg_hdr, pkg_hdr + len(pkg_body), 0x7F) + name + \
        struct.pack("<IIIII", pkg_hdr, len(res), pkg_hdr + len(tpool), len(res), 0) + pkg_body
    payload = gpool + pkg
    return struct.pack("<HHII", 0x0002, 12, 12 + len(payload), 1) + payload

# ---------------------------------------------------------------- icon
def make_icon(size=192):
    s = 4
    S = size * s
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.ellipse([0, 0, S - 1, S - 1], fill=(12, 12, 16, 255))
    import math
    cx, cy, r = S * 0.30, S * 0.5, S * 0.42
    for m in range(0, 60):
        ang = math.radians((m - 56) * 6)
        if abs(math.degrees(ang)) > 100 and abs(math.degrees(ang)) < 260:
            continue
        major = m % 5 == 0
        r1, r2 = r - S * (0.09 if major else 0.06), r - S * 0.04
        x1, y1 = cx + r1 * math.cos(ang), cy + r1 * math.sin(ang)
        x2, y2 = cx + r2 * math.cos(ang), cy + r2 * math.sin(ang)
        d.line([x1, y1, x2, y2], fill=(255, 255, 255, 220 if major else 120), width=int(S * (0.014 if major else 0.007)))
    px, pw, ph = cx + r - S * 0.02, S * 0.30, S * 0.17
    d.rounded_rectangle([px - pw / 2, cy - ph / 2, px + pw / 2, cy + ph / 2], radius=ph / 2,
                        outline=(185, 167, 255, 255), width=int(S * 0.022))
    d.rectangle([S * 0.20, S * 0.36, S * 0.26, S * 0.64], fill=(255, 255, 255, 255))  # stylised hour bar
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, S - 1, S - 1], fill=255)
    im.putalpha(Image.composite(im.getchannel("A"), mask, mask))
    im = im.resize((size, size), Image.LANCZOS)
    b = io.BytesIO(); im.save(b, "PNG", optimize=True); return b.getvalue()

# ---------------------------------------------------------------- zip (aligned)
def build_zip(files):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for name, data, stored in files:
            zi = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            zi.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
            if stored:
                off = buf.tell() + 30 + len(name.encode())
                need = (-off) % 4
                if need:
                    zi.extra = struct.pack("<HH", 0xD935, need) + b"\x00" * need
                off2 = buf.tell() + 30 + len(name.encode()) + len(zi.extra)
                assert off2 % 4 == 0, (name, off2)
            z.writestr(zi, data)
    return buf.getvalue()

# ---------------------------------------------------------------- v2 signing
def lp(b):
    return struct.pack("<I", len(b)) + b

def chunk_digest(parts):
    chunks = []
    for p in parts:
        for i in range(0, len(p), 1 << 20):
            c = p[i:i + (1 << 20)]
            chunks.append(hashlib.sha256(b"\xa5" + struct.pack("<I", len(c)) + c).digest())
    return hashlib.sha256(b"\x5a" + struct.pack("<I", len(chunks)) + b"".join(chunks)).digest()

def sign_v2(apk, key, cert_der):
    eocd_off = apk.rfind(b"PK\x05\x06")
    cd_off = struct.unpack("<I", apk[eocd_off + 16:eocd_off + 20])[0]
    entries, cd, eocd = apk[:cd_off], apk[cd_off:eocd_off], apk[eocd_off:]
    digest = chunk_digest([entries, cd, eocd])
    ALG = 0x0103
    signed_data = lp(lp(struct.pack("<I", ALG) + lp(digest))) + lp(lp(cert_der)) + lp(b"")
    sig = key.sign(signed_data, padding.PKCS1v15(), hashes.SHA256())
    pub = key.public_key().public_bytes(serialization.Encoding.DER,
                                        serialization.PublicFormat.SubjectPublicKeyInfo)
    signer = lp(signed_data) + lp(lp(struct.pack("<I", ALG) + lp(sig))) + lp(pub)
    value = lp(lp(signer))
    pair = struct.pack("<Q", 4 + len(value)) + struct.pack("<I", 0x7109871A) + value
    size = len(pair) + 8 + 16
    block = struct.pack("<Q", size) + pair + struct.pack("<Q", size) + b"APK Sig Block 42"
    new_cd_off = cd_off + len(block)
    eocd2 = eocd[:16] + struct.pack("<I", new_cd_off) + eocd[20:]
    return entries + block + cd + eocd2

def load_or_make_key(path):
    if os.path.exists(path + ".key"):
        key = serialization.load_pem_private_key(open(path + ".key", "rb").read(), None)
        cert = x509.load_pem_x509_certificate(open(path + ".crt", "rb").read())
    else:
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "Dial Clock")])
        now = datetime.datetime(2026, 1, 1)
        cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name)
                .public_key(key.public_key()).serial_number(x509.random_serial_number())
                .not_valid_before(now).not_valid_after(now + datetime.timedelta(days=365 * 30))
                .sign(key, hashes.SHA256()))
        open(path + ".key", "wb").write(key.private_bytes(serialization.Encoding.PEM,
              serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        open(path + ".crt", "wb").write(cert.public_bytes(serialization.Encoding.PEM))
    return key, cert.public_bytes(serialization.Encoding.DER)

if __name__ == "__main__":
    dex, out = sys.argv[1], sys.argv[2]
    icon = "res/drawable/icon.png"
    files = [
        ("AndroidManifest.xml", build_axml(manifest), False),
        ("classes.dex", open(dex, "rb").read(), False),
        ("resources.arsc", build_arsc([("drawable", "icon", icon),
                                        ("xml", "wallpaper", "res/xml/wallpaper.xml")]), True),
        (icon, make_icon(), True),
        ("res/xml/wallpaper.xml", build_axml(wallpaper_xml), False),
    ]
    apk = build_zip(files)
    key, cert = load_or_make_key(os.path.join(os.path.dirname(os.path.abspath(__file__)), "signing"))
    open(out, "wb").write(sign_v2(apk, key, cert))
    print("wrote", out, os.path.getsize(out))
