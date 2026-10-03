#!/usr/bin/env python3
"""Package the existing offline formatter with a native Java 21 jlink runtime."""

import sys

if sys.version_info < (3, 12):
    raise SystemExit(
        f"Bend formatter distribution tooling requires Python 3.12 or newer at build time "
        f"(found {sys.version.split()[0]}). The distributed formatter does not require Python."
    )


import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import tarfile
import tempfile
import zipfile
from pathlib import Path


def native_target():
    system = {"Linux": "linux", "Darwin": "macos", "Windows": "windows"}.get(platform.system())
    architecture = {"x86_64": "x64", "amd64": "x64", "aarch64": "aarch64", "arm64": "aarch64"}.get(platform.machine().lower())
    if system is None or architecture is None or (system == "windows" and architecture != "x64"):
        raise ValueError(f"Unsupported native distribution host: {platform.system()} {platform.machine()}")
    return f"{system}-{architecture}"


def manifest_version(jar):
    with zipfile.ZipFile(jar) as archive:
        # JAR continuation lines belong to the preceding manifest attribute.
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8").replace("\r\n", "\n").replace("\n ", "")
    attributes = dict(line.split(": ", 1) for line in manifest.split("\n\n", 1)[0].splitlines() if ": " in line)
    version = attributes.get("Implementation-Version", "")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._+-]*", version):
        raise ValueError("Formatter JAR has missing or unsafe Implementation-Version metadata")
    if attributes.get("Main-Class") != "com.dearlordylord.bend.idea.adapters.cli.BendFormatCli":
        raise ValueError("Input JAR is not the standalone Bend formatter")
    return version


def jdk_release(jdk):
    values = {}
    for line in (jdk / "release").read_text(encoding="utf-8").splitlines():
        if "=" in line:
            key, value = line.split("=", 1)
            values[key] = value.strip('"')
    if values.get("JAVA_VERSION", "").split(".", 1)[0] != "21":
        raise ValueError("A full Java 21 JDK is required to package the formatter")
    host_arch = native_target().split("-", 1)[1]
    jdk_arch = {"x86_64": "x64", "amd64": "x64", "aarch64": "aarch64", "arm64": "aarch64"}.get(values.get("OS_ARCH", "").lower())
    jdk_os = {"linux": "linux", "darwin": "macos", "mac os x": "macos", "windows": "windows"}.get(values.get("OS_NAME", "").lower())
    if jdk_arch != host_arch or jdk_os != native_target().split("-", 1)[0]:
        raise ValueError("Cross-compiling runtime distributions is unsupported; use a native JDK on each host")
    return values


def run(command, timeout=180):
    result = subprocess.run([str(value) for value in command], capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {command[0]}\n{result.stdout}{result.stderr}")
    return result.stdout.strip()


POSIX_LAUNCHER = '''#!/bin/sh
set -eu
case "$0" in
  /*) launcher=$0 ;;
  *) launcher=$PWD/$0 ;;
esac
links=0
while [ -L "$launcher" ]; do
  links=$((links + 1))
  [ "$links" -le 40 ] || { echo "bend-format: launcher symlink loop" >&2; exit 2; }
  if [ -x /usr/bin/readlink ]; then
    link=$(/usr/bin/readlink "$launcher")
  elif [ -x /bin/readlink ]; then
    link=$(/bin/readlink "$launcher")
  else
    echo "bend-format: cannot resolve launcher symlink" >&2
    exit 2
  fi
  case "$link" in
    /*) launcher=$link ;;
    *) launcher=${launcher%/*}/$link ;;
  esac
done
bundle_root=$(CDPATH= cd -P "${launcher%/*}/.." && pwd)
exec "$bundle_root/runtime/bin/java" -jar "$bundle_root/lib/bend-format-tool.jar" "$@"
'''
WINDOWS_LAUNCHER = '''@echo off
setlocal
"%~dp0..\\runtime\\bin\\java.exe" -jar "%~dp0..\\lib\\bend-format-tool.jar" %*
exit /b %errorlevel%
'''


def package(jar, jdk, output, project):
    jar, jdk, output, project = (path.resolve() for path in (jar, jdk, output, project))
    version = manifest_version(jar)
    target = native_target()
    release = jdk_release(jdk)
    suffix = ".exe" if target.startswith("windows-") else ""
    jdeps, jlink = (jdk / "bin" / (tool + suffix) for tool in ("jdeps", "jlink"))
    if not jdeps.is_file() or not jlink.is_file() or not (jdk / "jmods").is_dir():
        raise ValueError("Gradle must supply a full Java 21 JDK containing jdeps, jlink and jmods")
    missing = run([jdeps, "--multi-release", "21", "--missing-deps", jar])
    missing_classes = re.findall(r"^\s+(\S+)\s+->\s+(\S+)\s+not found$", missing, re.MULTILINE)
    # Scala's erased AnyKind has no JVM class. Only its compile-time quoted API
    # references are allowed; missing runnable dependencies still fail packaging.
    if any(not source.startswith("scala.quoted.") or target != "scala.AnyKind"
           for source, target in missing_classes):
        raise ValueError(f"Formatter JAR has missing runtime dependencies:\n{missing}")
    if missing and not missing_classes:
        raise ValueError(f"Unrecognized jdeps missing-dependency output:\n{missing}")
    modules_text = run([jdeps, "--multi-release", "21", "--ignore-missing-deps", "--print-module-deps", jar])
    modules = sorted(set(modules_text.split(",")))
    if not modules or any(not re.fullmatch(r"[a-z][a-z0-9.]*", module) for module in modules):
        raise ValueError(f"Unexpected jdeps module output: {modules_text}")
    output.mkdir(parents=True, exist_ok=True)
    stem = f"bend-format-{version}-{target}"
    archive_name = stem + (".zip" if target.startswith("windows-") else ".tar.gz")
    # Only this invocation's private staging directory is removed.
    with tempfile.TemporaryDirectory(prefix=".bend-format-staging-", dir=output) as temporary:
        stage = Path(temporary)
        bundle = stage / stem
        (bundle / "bin").mkdir(parents=True)
        (bundle / "lib").mkdir()
        shutil.copy2(jar, bundle / "lib" / "bend-format-tool.jar")
        shutil.copy2(project / "LICENSE", bundle / "LICENSE")
        if (project / "NOTICE").is_file():
            shutil.copy2(project / "NOTICE", bundle / "NOTICE")
        shutil.copy2(project / "scripts" / "BEND-FORMAT-THIRD-PARTY-NOTICES.txt", bundle / "THIRD-PARTY-NOTICES.txt")
        if (jdk / "NOTICE").is_file():
            shutil.copy2(jdk / "NOTICE", bundle / "RUNTIME-NOTICE")
        with zipfile.ZipFile(jar) as archive:
            for entry in archive.namelist():
                relative = Path(entry)
                if relative.name.upper() in ("LICENSE", "NOTICE") and not relative.is_absolute() and ".." not in relative.parts:
                    target_notice = bundle / "legal" / "formatter" / relative
                    target_notice.parent.mkdir(parents=True, exist_ok=True)
                    target_notice.write_bytes(archive.read(entry))
        run([jlink, "--module-path", jdk / "jmods", "--add-modules", ",".join(modules),
             "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=2",
             "--output", bundle / "runtime"])
        if not any(path.is_file() for path in (bundle / "runtime" / "legal").rglob("*")):
            raise ValueError("jlink runtime is missing its legal notices")
        launcher = bundle / "bin" / ("bend-format.cmd" if target.startswith("windows-") else "bend-format")
        launcher.write_bytes((WINDOWS_LAUNCHER.replace("\n", "\r\n") if target.startswith("windows-") else POSIX_LAUNCHER).encode("utf-8"))
        launcher.chmod(0o755)
        revision = run(["git", "-C", project, "rev-parse", "HEAD"])
        dirty = bool(run(["git", "-C", project, "status", "--porcelain", "--untracked-files=normal"]))
        metadata = {
            "format": 1,
            "version": version,
            "target": target,
            "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
            "modules": modules,
            "ignoredCompileTimeReferences": sorted({target for _, target in missing_classes}),
            "source": {"revision": revision, "dirty": dirty},
            "jdk": {key: release[key] for key in ("IMPLEMENTOR", "IMPLEMENTOR_VERSION", "JAVA_VERSION", "JAVA_RUNTIME_VERSION", "OS_NAME", "OS_ARCH", "SOURCE", "SOURCE_REPO", "BUILD_SOURCE", "BUILD_SOURCE_REPO") if key in release},
        }
        (bundle / "distribution.json").write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        temporary_archive = stage / archive_name
        if target.startswith("windows-"):
            with zipfile.ZipFile(temporary_archive, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                for path in sorted(bundle.rglob("*")):
                    archive.write(path, path.relative_to(stage).as_posix())
        else:
            with tarfile.open(temporary_archive, "w:gz") as archive:
                archive.add(bundle, arcname=stem)
        destination = output / archive_name
        os.replace(temporary_archive, destination)
    print(f"Packaged {destination} (Java modules: {','.join(modules)})")
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--jdk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--project", type=Path, required=True)
    args = parser.parse_args()
    package(args.jar, args.jdk, args.output, args.project)


if __name__ == "__main__":
    main()
