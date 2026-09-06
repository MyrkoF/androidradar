#!/usr/bin/env python3
"""Régénère app/src/main/assets/oui.tsv depuis le fichier `manuf` de Wireshark.

Usage : tools/update_oui.py [chemin/manuf]   (sans argument : télécharge la version courante)

Format de sortie, une ligne par bloc : préfixe hex sans séparateur, nom court, nom long.
Longueur du préfixe = taille du bloc : 6 (MA-L /24), 7 (MA-M /28), 9 (MA-S /36).
`Oui.lookup()` lit longest-prefix-first (9, puis 7, puis 6).
"""
import sys, urllib.request, pathlib, datetime

URL = "https://www.wireshark.org/download/automated/data/manuf"
OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/oui.tsv"

def main():
    if len(sys.argv) > 1:
        raw = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
    else:
        raw = urllib.request.urlopen(URL, timeout=60).read().decode("utf-8", errors="replace")
    rows, sizes = [], {6: 0, 7: 0, 9: 0}
    for line in raw.splitlines():
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        prefix, short = parts[0].strip(), parts[1].strip()
        long = parts[2].strip() if len(parts) > 2 and parts[2].strip() else short
        hexpart, _, bits = prefix.partition("/")
        bits = int(bits) if bits else 24
        key = hexpart.replace(":", "").upper()[: bits // 4]
        if len(key) not in sizes:
            print(f"ignoré (bloc /{bits}) : {line}", file=sys.stderr)
            continue
        sizes[len(key)] += 1
        rows.append(f"{key}\t{short}\t{long}")
    OUT.write_text("\n".join(rows) + "\n", encoding="utf-8")
    print(f"{OUT} : {len(rows)} blocs (MA-L {sizes[6]}, MA-M {sizes[7]}, MA-S {sizes[9]}) — {datetime.date.today()}")

if __name__ == "__main__":
    main()
