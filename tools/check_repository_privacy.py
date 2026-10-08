"""Contrôle préventif de l'index Git ; aucun accès réseau ni lecture véhicule."""

from pathlib import PurePosixPath
import re
import subprocess
import sys


PROFILE = re.compile(rb"[A-Za-z]:(?:[\\/])+Users(?:[\\/])+[^\\/\s<>\"']+", re.I)
VIN = re.compile(rb"(?<![A-Z0-9])([A-HJ-NPR-Z0-9]{11}[0-9]{6})(?![A-Z0-9])")
# Numéros de série réservés aux fixtures fictives et aux exemples documentés.
FIXTURE_SERIALS = {b"000000", b"000001", b"123456", b"123457"}
RASTER = {".jpg", ".jpeg", ".png", ".webp"}


def main():
    index = subprocess.check_output(["git", "ls-files", "--stage", "-z"])
    entries = []
    errors = []
    for record in index.split(b"\0"):
        if not record:
            continue
        metadata, raw_path = record.split(b"\t", 1)
        mode, oid, stage = metadata.split()
        path = raw_path.decode("utf-8", "replace")
        if stage != b"0":
            errors.append((path, "conflit Git non résolu"))
        if path.startswith(("captures/", "screenshots/")) or path == "rapport_obd2.html":
            errors.append((path, "fichier personnel brut interdit"))
        if PurePosixPath(path).suffix.lower() in RASTER and not path.startswith("app/src/"):
            errors.append((path, "image hors ressources applicatives : revue privée nécessaire"))
        if mode != b"160000":
            entries.append((oid, path))
    batch = subprocess.check_output(["git", "cat-file", "--batch"],
                                    input=b"\n".join(oid for oid, _ in entries) + b"\n")
    cursor = 0
    for oid, path in entries:
        end = batch.index(b"\n", cursor)
        found, kind, raw_size = batch[cursor:end].split()
        size = int(raw_size)
        content = batch[end + 1:end + 1 + size]
        cursor = end + 2 + size
        assert found == oid and kind == b"blob"
        if b"\0" in content:
            continue
        if PROFILE.search(content):
            errors.append((path, "chemin de profil utilisateur"))
        # Les trames CAN compactes peuvent aussi mesurer 17 caractères.
        if any(not re.fullmatch(rb"[0-9A-F]+", vin)
               for vin in VIN.findall(content) if vin[-6:] not in FIXTURE_SERIALS):
            errors.append((path, "VIN potentiel sans numéro de série fictif réservé"))
    if errors:
        for path, reason in sorted(set(errors)):
            print(f"{path}: {reason}", file=sys.stderr)
        return 1
    print(f"Confidentialité : {len(entries)} fichiers de l'index contrôlés, aucun motif interdit.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
