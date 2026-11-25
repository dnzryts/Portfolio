"""Builds app/src/main/java/com/uliptracker/Changelog.java from the repo's CHANGELOG.md.

Usage (from the repo root):  python3 Portfolio/tools/changelog.py CHANGELOG.md Portfolio/app/src/main/java/com/uliptracker/Changelog.java
"""
import re
import sys


def parse(md):
    out, cur = [], None
    for line in md.splitlines():
        m = re.match(r"^##\s+(.+?)\s*$", line)
        if m:
            head = m.group(1)
            ver, _, date = head.partition(" — ")
            cur = {"version": ver.strip(), "date": date.strip(), "items": []}
            out.append(cur)
            continue
        m = re.match(r"^-\s+(.+)$", line)
        if m and cur is not None:
            cur["items"].append(m.group(1).strip())
    return out


def jstr(s):
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def java(entries):
    rows = []
    for e in entries:
        items = ", ".join(jstr(i) for i in e["items"])
        rows.append(f"            new Entry({jstr(e['version'])}, {jstr(e['date'])}, new String[]{{{items}}}),")
    return """package com.uliptracker;

/** Release history shown in More › About & what's new. Generated from CHANGELOG.md by tools/changelog.py — don't edit by hand. */
public final class Changelog {

    private Changelog() { }

    public static final class Entry {
        public final String version, date;
        public final String[] items;
        Entry(String version, String date, String[] items) { this.version = version; this.date = date; this.items = items; }
    }

    /** Newest first. */
    public static final Entry[] ENTRIES = {
""" + "\n".join(rows) + """
    };
}
"""


if __name__ == "__main__":
    src, dst = sys.argv[1], sys.argv[2]
    entries = parse(open(src, encoding="utf-8").read())
    open(dst, "w", encoding="utf-8").write(java(entries))
    print(f"{len(entries)} versions -> {dst}")
