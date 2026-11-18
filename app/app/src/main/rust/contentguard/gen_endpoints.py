#!/usr/bin/env python3
"""Generate src/endpoints_data.rs — tabel template URL/endpoint Cerpen
dalam bentuk byte ter-XOR (bukan plaintext), untuk modul Rust Endpoints.

Pakai:  cat <daftar NAME=template> | python3 gen_endpoints.py > src/endpoints_data.rs
Format daftar: satu baris per entri, NAME=template. Template boleh
mengandung placeholder {0} {1} {2} yang diisi argumen saat runtime.
Baris kosong / diawali '#' diabaikan.

Mask 8 byte dipakai ulang dari file lama bila ada (supaya regenerate
tidak mengubah mask); bila belum ada, dibuat acak.

CATATAN: ini OBFUSKASI, bukan enkripsi — menghentikan `strings` / dump
DEX kasual, bukan reverser bertekad. URL-nya sendiri publik (situs user).
JANGAN menulis nilai URL mentah ke laporan/log — yang dicek hanya
sama/tidak + ada/tidak.
"""

import os
import re
import secrets
import sys

MASK_RE = re.compile(r"pub const URL_MASK: \[u8; 8\] = \[([0-9a-fx, ]+)\];")


def parse_mask(path):
    try:
        m = MASK_RE.search(open(path).read())
        if m:
            return [int(x, 16) for x in m.group(1).split(",") if x.strip()]
    except FileNotFoundError:
        pass
    return None


def fmt_bytes(bs):
    return ", ".join("0x%02x" % b for b in bs)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    out_path = os.path.join(here, "src", "endpoints_data.rs")
    mask = parse_mask(out_path) or list(secrets.token_bytes(8))
    entries = []
    for line in sys.stdin:
        line = line.rstrip("\n")
        if not line or line.startswith("#"):
            continue
        name, _, template = line.partition("=")
        name = name.strip()
        if not name or not template:
            print("baris tidak valid: %r" % line, file=sys.stderr)
            return 1
        raw = template.encode("utf-8")
        obf = bytes(b ^ mask[i % 8] for i, b in enumerate(raw))
        entries.append((name, obf))
    print("// @generated oleh gen_endpoints.py — JANGAN edit manual.")
    print("// Byte template URL ter-XOR dengan URL_MASK (obfuskasi, bukan enkripsi).")
    print("// Regenerate: cat <daftar NAME=template> | python3 gen_endpoints.py > src/endpoints_data.rs")
    print()
    print("pub const URL_MASK: [u8; 8] = [%s];" % fmt_bytes(mask))
    print()
    for name, obf in entries:
        print("pub const %s: &[u8] = &[%s];" % (name, fmt_bytes(obf)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
