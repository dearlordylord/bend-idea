#!/usr/bin/env python3
"""Smoke-test a native formatter archive without installed Java or JAVA_HOME."""

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
import runpy
import subprocess
import tarfile
import tempfile
import zipfile
from pathlib import Path

packaging = runpy.run_path(str(Path(__file__).with_name("package-bend-format.py")))


def select_archive(directory, jar):
    version = packaging["manifest_version"](jar)
    target = packaging["native_target"]()
    suffix = ".zip" if os.name == "nt" else ".tar.gz"
    archive = directory / f"bend-format-{version}-{target}{suffix}"
    if not archive.is_file():
        raise ValueError(f"Expected native formatter archive is missing: {archive}")
    return archive


def extract(archive, destination):
    if archive.name.endswith(".zip"):
        with zipfile.ZipFile(archive) as contents:
            for entry in contents.namelist():
                path = Path(entry)
                if path.is_absolute() or ".." in path.parts:
                    raise ValueError(f"Unsafe archive path: {entry}")
            contents.extractall(destination)
    else:
        with tarfile.open(archive) as contents:
            contents.extractall(destination, filter="data")
    roots = list(destination.iterdir())
    if len(roots) != 1 or not roots[0].is_dir():
        raise ValueError("Distribution must contain one bundle root directory")
    return roots[0]


def smoke(archive, jar=None):
    with tempfile.TemporaryDirectory(prefix="bend format distribution smoke ") as temporary:
        base = Path(temporary)
        unpacked = base / "bundle path with spaces"
        unpacked.mkdir()
        bundle = extract(archive, unpacked)
        metadata = json.loads((bundle / "distribution.json").read_text(encoding="utf-8"))
        packaged_jar = bundle / "lib" / "bend-format-tool.jar"
        assert metadata["target"] == packaging["native_target"](), metadata
        assert metadata["version"] == packaging["manifest_version"](packaged_jar), metadata
        assert metadata["jarSha256"] == hashlib.sha256(packaged_jar.read_bytes()).hexdigest(), metadata
        if jar:
            assert metadata["jarSha256"] == hashlib.sha256(jar.read_bytes()).hexdigest(), "Distribution uses a different formatter JAR"
        for notice in ("LICENSE", "THIRD-PARTY-NOTICES.txt"):
            assert (bundle / notice).stat().st_size > 0, notice
        assert any(path.is_file() for path in (bundle / "runtime" / "legal").rglob("*")), "Runtime legal notices were removed"
        launcher = bundle / "bin" / ("bend-format.cmd" if os.name == "nt" else "bend-format")
        work = base / "caller working directory"
        work.mkdir()
        environment = dict(os.environ)
        # Neither an installed Java nor JAVA_HOME can accidentally satisfy this test.
        environment["PATH"] = ""
        environment["JAVA_HOME"] = str(base / "hostile missing java home")
        environment.pop("JDK_JAVA_OPTIONS", None)
        environment.pop("JAVA_TOOL_OPTIONS", None)
        environment.pop("_JAVA_OPTIONS", None)

        def invoke(arguments, code, stdin=None, entry=launcher):
            command = [str(entry), *arguments]
            if os.name == "nt":
                interpreter = str(Path(os.environ["SystemRoot"]) / "System32" / "cmd.exe")
                # Give cmd its own outer quote pair so spaces in both the launcher
                # and caller arguments survive cmd /s parsing.
                command = f'"{interpreter}" /d /s /c "{subprocess.list2cmdline(command)}"'
            result = subprocess.run(command, input=stdin, capture_output=True, cwd=work,
                                    env=environment, timeout=30)
            assert result.returncode == code, (arguments, result.returncode, result.stdout, result.stderr)
            return result.stdout.decode("utf-8")

        assert invoke(["--version"], 0).strip() == f'bend-format {metadata["version"]}'
        for flag in ("--help", "-h"):
            help_text = invoke([flag], 0)
            assert "bend-format check FILE ..." in help_text
            assert "bend-format-tool" not in help_text
        invoke(["check", "--unknown"], 2)
        dash_path = work / "-dash file.bend"
        dash_path.write_bytes(b"def main():\n  1\n")
        invoke(["check", "--", dash_path.name], 0)
        java = bundle / "runtime" / "bin" / ("java.exe" if os.name == "nt" else "java")
        modules = subprocess.run([str(java), "--list-modules"], capture_output=True, env=environment, timeout=30, check=True)
        runtime_modules = {line.split("@", 1)[0] for line in modules.stdout.decode("utf-8").splitlines()}
        assert set(metadata["modules"]) <= runtime_modules, (metadata["modules"], runtime_modules)
        (work / ".editorconfig").write_text("root = true\n[*.bend]\nindent_style = space\nindent_size = 2\nbend_max_line_length = off\n", encoding="utf-8")
        source = b"def main(x: U32,y: U32) -> U32:\n    x\n"
        expected = b"def main(x: U32, y: U32) -> U32:\n  x\n"
        path = work / "safe file.bend"
        path.write_bytes(source)
        assert "would-change: safe file.bend" in invoke(["check", path.name], 1)
        assert path.read_bytes() == source, "Check changed source bytes"
        assert "would-change: stdin only.bend" in invoke(["check", "--stdin-path", "stdin only.bend"], 1, source)
        assert not (work / "stdin only.bend").exists(), "Stdin check wrote a file"
        invoke(["fix", path.name], 0)
        assert path.read_bytes() == expected, path.read_bytes()
        invoke(["check", path.name], 0)
        invoke(["fix", path.name], 0)
        assert path.read_bytes() == expected, "Fix is not idempotent"
        invoke(["check", "--stdin-path", "stdin only.bend"], 0, expected)
        invoke([], 2)
        invoke(["check", "missing.bend"], 2)
        unsafe = work / "unsafe file.bend"
        unsafe_source = b"def unfinished(alpha,beta\n"
        unsafe.write_bytes(unsafe_source)
        invoke(["check", unsafe.name], 2)
        invoke(["fix", unsafe.name], 2)
        assert unsafe.read_bytes() == unsafe_source, "Unsafe formatting wrote source"
        path.write_bytes(source)
        invoke(["fix", path.name, unsafe.name], 2)
        assert path.read_bytes() == expected and unsafe.read_bytes() == unsafe_source, "Batch argument forwarding changed per-file semantics"
        crlf = work / "no final newline.bend"
        crlf.write_bytes(source.rstrip(b"\n").replace(b"\n", b"\r\n"))
        invoke(["fix", crlf.name], 0)
        assert crlf.read_bytes() == expected.rstrip(b"\n").replace(b"\n", b"\r\n"), "Line ending convention changed"
        # Effective settings must still come from the caller's file tree.
        (work / ".editorconfig").write_text("root = true\n[*.bend]\nindent_style = space\nindent_size = 4\nbend_max_line_length = 20\n", encoding="utf-8")
        wrapped = work / "wrapped file.bend"
        wrapped_source = b"def main():\n  combine_three_arguments(1,2,3)\n"
        wrapped.write_bytes(wrapped_source)
        invoke(["check", wrapped.name], 1)
        invoke(["fix", wrapped.name], 0)
        assert wrapped.read_bytes() == b"def main():\n    combine_three_arguments(\n        1,\n        2,\n        3\n    )\n", wrapped.read_bytes()
        invoke(["check", wrapped.name], 0)
        overridden = work / "overridden settings"
        overridden.mkdir()
        (overridden / ".editorconfig").write_text("[*.bend]\nbend_max_line_length = off\n", encoding="utf-8")
        child = overridden / "short file.bend"
        child.write_bytes(wrapped_source)
        invoke(["fix", str(child.relative_to(work))], 0)
        assert child.read_bytes() == b"def main():\n    combine_three_arguments(1, 2, 3)\n", child.read_bytes()
        (overridden / ".editorconfig").write_text("[*.bend]\nbend_max_line_length = invalid\n", encoding="utf-8")
        child_before = child.read_bytes()
        invoke(["check", str(child.relative_to(work))], 2)
        invoke(["fix", str(child.relative_to(work))], 2)
        assert child.read_bytes() == child_before, "Invalid EditorConfig wrote source"
        if os.name != "nt":
            alias = work / "symlink launcher"
            alias.symlink_to(os.path.relpath(launcher, work))
            assert invoke(["--version"], 0, entry=alias).strip() == f'bend-format {metadata["version"]}'
        print(f'Distribution smoke passed: {archive.name} (bundled runtime, hostile JAVA_HOME, empty PATH, cwd/stdin/args, 0/1/2, no-write, EditorConfig, bytes and idempotence)')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    selection = parser.add_mutually_exclusive_group(required=True)
    selection.add_argument("--archive", type=Path)
    selection.add_argument("--distribution-dir", type=Path)
    parser.add_argument("--jar", type=Path)
    args = parser.parse_args()
    if args.distribution_dir and not args.jar:
        parser.error("--distribution-dir requires --jar to select the artifact by its manifest version")
    archive = args.archive or select_archive(args.distribution_dir.resolve(), args.jar.resolve())
    smoke(archive.resolve(), args.jar.resolve() if args.jar else None)


if __name__ == "__main__":
    main()
