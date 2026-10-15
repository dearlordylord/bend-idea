#!/usr/bin/env python3
"""Verify pinned-recovery notices in each supplied plugin JAR or ZIP."""
import io
import sys
import zipfile
from pathlib import Path

RESOURCES = Path(__file__).resolve().parent.parent / "src/main/resources"
HELPER = "semantic/bend-structured-helper.ts"
NOTICES = ("META-INF/bend-recovery-LICENSE.txt", "META-INF/bend-recovery-NOTICES.txt")


def verify(contents):
    found = 0
    with zipfile.ZipFile(contents) as archive:
        names = set(archive.namelist())
        if HELPER in names:
            if archive.read(HELPER) != (RESOURCES / HELPER).read_bytes():
                raise ValueError("Packaged recovery helper differs from candidate source")
            for notice in NOTICES:
                if notice not in names or archive.read(notice) != (RESOURCES / notice).read_bytes():
                    raise ValueError(f"Missing or altered recovery notice: {notice}")
            found += 1
        for name in names:
            if name.endswith(".jar"):
                found += verify(io.BytesIO(archive.read(name)))
    return found


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("Usage: verify-recovery-notices.py <plugin.jar|plugin.zip> ...")
    for argument in sys.argv[1:]:
        artifact = Path(argument)
        if verify(artifact) != 1:
            raise SystemExit(f"Expected exactly one recovery helper with notices in {artifact}")
        print(f"Recovery notices verified: {artifact}")
