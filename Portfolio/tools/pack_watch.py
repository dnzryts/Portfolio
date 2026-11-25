"""Package the Galaxy Watch (Wear OS) companion app: same classes.dex, its own manifest, a round icon,
signed with the same key as the phone app."""
import sys, os, io
import importlib.util
_spec = importlib.util.spec_from_file_location("phone_pack", os.path.join(os.path.dirname(os.path.abspath(__file__)), "pack.py"))
PP = importlib.util.module_from_spec(_spec)      # phone packager: helpers, logo drawing, arsc builder
_spec.loader.exec_module(PP)
from PIL import Image, ImageDraw

P = PP.P
P.PKG = "com.uliptracker.watch"
E, T_STR, T_INT, T_BOOL, T_REF = P.E, P.T_STR, P.T_INT, P.T_BOOL, P.T_REF
P.ATTR_IDS.update({"required": 0x0101028E, "value": 0x01010024})

PP.RES[:] = [("drawable", "icon", "res/drawable/icon.png")]
ICON = 0x7F010000

CLS = "com.uliptracker"
manifest = E("manifest", [(None, "package", T_STR, P.PKG),
                          ("a", "versionCode", T_INT, 3), ("a", "versionName", T_STR, "1.2")], [
    E("uses-sdk", [("a", "minSdkVersion", T_INT, 30), ("a", "targetSdkVersion", T_INT, 34)]),
    E("uses-feature", [("a", "name", T_STR, "android.hardware.type.watch")]),
    E("uses-permission", [("a", "name", T_STR, "android.permission.INTERNET")]),
    E("uses-permission", [("a", "name", T_STR, "android.permission.ACCESS_NETWORK_STATE")]),
    E("uses-permission", [("a", "name", T_STR, "android.permission.BLUETOOTH")]),
    E("uses-permission", [("a", "name", T_STR, "android.permission.BLUETOOTH_CONNECT")]),
    E("application", [("a", "theme", T_REF, 0x0103022E),   # Theme.Material.NoActionBar
                      ("a", "label", T_STR, "Portfolio"), ("a", "icon", T_REF, ICON)], [
        E("meta-data", [("a", "name", T_STR, "com.google.android.wearable.standalone"), ("a", "value", T_BOOL, True)]),
        E("activity", [("a", "name", T_STR, CLS + ".WatchActivity"), ("a", "exported", T_BOOL, True),
                       ("a", "label", T_STR, "Portfolio")], [
            E("intent-filter", [], [
                E("action", [("a", "name", T_STR, "android.intent.action.MAIN")]),
                E("category", [("a", "name", T_STR, "android.intent.category.LAUNCHER")])])]),
    ]),
])


def round_icon(px=192):
    S = px * 4
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    grad = PP._gradient(S)
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, S - 1, S - 1), fill=255)
    im.paste(grad, (0, 0), mask)
    PP._logo(ImageDraw.Draw(im), S, S / 2, S / 2, S * 0.33)
    im = im.resize((px, px), Image.LANCZOS)
    b = io.BytesIO(); im.save(b, "PNG", optimize=True)
    return b.getvalue()


if __name__ == "__main__":
    dex, out = sys.argv[1], sys.argv[2]
    icon = round_icon()
    files = [("AndroidManifest.xml", P.build_axml(manifest), False),
             ("classes.dex", open(dex, "rb").read(), False),
             ("resources.arsc", PP.build_arsc(PP.RES), True),
             ("res/drawable/icon.png", icon, True)]
    apk = P.build_zip(files)
    key, cert = P.load_or_make_key(os.path.join(os.path.dirname(os.path.abspath(__file__)), "signing"))
    open(out, "wb").write(P.sign_v2(apk, key, cert))
    print("wrote", out, os.path.getsize(out))
    if len(sys.argv) > 3:
        open(sys.argv[3], "wb").write(icon)
