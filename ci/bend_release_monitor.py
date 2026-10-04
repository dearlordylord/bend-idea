#!/usr/bin/env python3
"""Test each published Bend release newer than the approved compiler pin.

The GitHub Actions workflow runs this against the release archive, while the
ordinary check task continues to use ci/bend-test-toolchain.properties.
"""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tarfile
import tempfile
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen


UPSTREAM = "bendlang/bend"
STATE_BRANCH = "bend-release-results"
STATE_PATH = "bend-release-results.json"
COMPATIBILITY_SUITE = "editor-compatibility-v2"
MINIMUM_VERSION = (2, 0, 35)  # Existing pinned compiler is already gated by verify.yml.
TAG = re.compile(r"v([0-9]+)\.([0-9]+)\.([0-9]+)\Z")
DIGEST = re.compile(r"sha256:([0-9a-f]{64})\Z")
MAX_ARCHIVE_BYTES = 150 * 1024 * 1024
MAX_EXTRACTED_BYTES = 350 * 1024 * 1024
MAX_CANDIDATES_PER_RUN = 20


def version(tag):
    match = TAG.fullmatch(tag)
    return tuple(map(int, match.groups())) if match else None


def candidates(releases, results, retry_tag=""):
    """Return every unseen stable release with a verified-asset candidate."""
    selected = []
    seen_tags = set()
    for release in releases:
        tag = release.get("tag_name", "")
        parsed = version(tag)
        if release.get("draft") or release.get("prerelease") or not release.get("published_at"):
            continue
        if parsed is None or (parsed <= MINIMUM_VERSION and tag != retry_tag):
            continue
        if tag in seen_tags:
            raise ValueError(f"Duplicate published Bend tag: {tag}")
        seen_tags.add(tag)
        if retry_tag and tag != retry_tag:
            continue
        asset_name = f"bend-{tag[1:]}-linux-x64.tar.gz"
        assets = [asset for asset in release.get("assets", []) if asset.get("name") == asset_name]
        if len(assets) > 1:
            raise ValueError(f"Duplicate release archive: {tag}")
        if not assets or assets[0].get("state") != "uploaded":
            continue  # A newly published release may still be receiving assets.
        match = DIGEST.fullmatch(assets[0].get("digest") or "")
        if match is None:
            continue  # Never run an archive without a published digest.
        release_id = release.get("id")
        if not isinstance(release_id, int) or release_id <= 0:
            raise ValueError(f"Invalid release ID for {tag}")
        previous = results.get(str(release_id), {})
        if not retry_tag and previous.get("digest") == match.group(1) and previous.get("tag") == tag and previous.get("suite") == COMPATIBILITY_SUITE:
            continue
        selected.append({
            "id": release_id,
            "tag": tag,
            "published_at": release["published_at"],
            "asset_name": asset_name,
            "digest": match.group(1),
        })
    if retry_tag and not selected:
        raise ValueError(f"No testable published Bend release found for {retry_tag}")
    return sorted(selected, key=lambda item: (item["published_at"], item["id"]))[:MAX_CANDIDATES_PER_RUN]


def pending_assets(releases):
    """Keep a published release without a verifiable archive visible in CI."""
    pending = []
    for release in releases:
        tag = release.get("tag_name", "")
        parsed = version(tag)
        if release.get("draft") or release.get("prerelease") or not release.get("published_at"):
            continue
        if parsed is None or parsed <= MINIMUM_VERSION:
            continue
        name = f"bend-{tag[1:]}-linux-x64.tar.gz"
        assets = [asset for asset in release.get("assets", []) if asset.get("name") == name]
        if len(assets) != 1 or assets[0].get("state") != "uploaded" or not DIGEST.fullmatch(assets[0].get("digest") or ""):
            pending.append(tag)
    return pending


class GitHub:
    def __init__(self, token, repository):
        self.token = token
        self.repository = repository

    def request(self, method, path, body=None, missing_ok=False):
        data = json.dumps(body).encode() if body is not None else None
        headers = {
            "Accept": "application/vnd.github+json",
            "User-Agent": "bend-idea-release-monitor",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        if data is not None:
            headers["Content-Type"] = "application/json"
        request = Request(f"https://api.github.com{path}", data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=30) as response:
                return json.load(response)
        except HTTPError as error:
            if missing_ok and error.code == 404:
                return None
            raise RuntimeError(f"GitHub API {method} {path} returned HTTP {error.code}") from error

    def releases(self):
        all_releases = []
        for page in range(1, 21):
            batch = self.request("GET", f"/repos/{UPSTREAM}/releases?per_page=100&page={page}")
            if not isinstance(batch, list):
                raise ValueError("Bend release response is not a list")
            all_releases.extend(batch)
            if len(batch) < 100:
                return all_releases
        raise ValueError("Bend release list exceeded 2,000 entries; increase pagination limit")

    def tag_commit(self, tag):
        commit = self.request("GET", f"/repos/{UPSTREAM}/commits/{quote(tag, safe='')}")
        sha = commit.get("sha", "")
        if not re.fullmatch(r"[0-9a-f]{40}", sha):
            raise ValueError(f"Cannot resolve immutable commit for {tag}")
        return sha

    def load_state(self):
        path = f"/repos/{self.repository}/contents/{STATE_PATH}?ref={STATE_BRANCH}"
        content = self.request("GET", path, missing_ok=True)
        if content is None:
            return {"schema": 1, "results": {}}, None
        raw = base64.b64decode(content["content"], validate=False)
        state = json.loads(raw)
        if state.get("schema") != 1 or not isinstance(state.get("results"), dict):
            raise ValueError("Unsupported Bend release result state")
        return state, content["sha"]

    def ensure_state_branch(self):
        path = f"/repos/{self.repository}/git/ref/heads/{STATE_BRANCH}"
        if self.request("GET", path, missing_ok=True) is not None:
            return
        repo = self.request("GET", f"/repos/{self.repository}")
        default = repo["default_branch"]
        base = self.request("GET", f"/repos/{self.repository}/git/ref/heads/{quote(default, safe='')}")
        self.request("POST", f"/repos/{self.repository}/git/refs", {
            "ref": f"refs/heads/{STATE_BRANCH}", "sha": base["object"]["sha"]
        })

    def save_state(self, state, content_sha):
        self.ensure_state_branch()
        encoded = base64.b64encode((json.dumps(state, indent=2, sort_keys=True) + "\n").encode()).decode()
        body = {
            "message": "Record Bend release compatibility result",
            "content": encoded,
            "branch": STATE_BRANCH,
        }
        if content_sha:
            body["sha"] = content_sha
        response = self.request("PUT", f"/repos/{self.repository}/contents/{STATE_PATH}", body)
        return response["content"]["sha"]


def download_archive(candidate, destination):
    tag, name = candidate["tag"], candidate["asset_name"]
    url = f"https://github.com/{UPSTREAM}/releases/download/{tag}/{name}"
    request = Request(url, headers={"User-Agent": "bend-idea-release-monitor"})
    digest = hashlib.sha256()
    size = 0
    with urlopen(request, timeout=120) as response, destination.open("wb") as output:
        while chunk := response.read(1024 * 1024):
            size += len(chunk)
            if size > MAX_ARCHIVE_BYTES:
                raise ValueError(f"Release archive exceeds {MAX_ARCHIVE_BYTES} bytes: {tag}")
            digest.update(chunk)
            output.write(chunk)
    if digest.hexdigest() != candidate["digest"]:
        raise ValueError(f"Release archive digest mismatch: {tag}")


def extract_archive(archive, destination):
    total = 0
    with tarfile.open(archive, "r:gz") as source:
        members = source.getmembers()
        if len(members) > 2_000:
            raise ValueError("Release archive has too many entries")
        for member in members:
            parts = PurePosixPath(member.name).parts
            if not parts or parts[0] != "bend" or any(part in (".", "..") for part in parts):
                raise ValueError(f"Unsafe release archive path: {member.name}")
            if not member.isfile() and not member.isdir():
                raise ValueError(f"Unsupported release archive entry: {member.name}")
            total += member.size
            if total > MAX_EXTRACTED_BYTES:
                raise ValueError("Release archive expands beyond the size limit")
            target = destination.joinpath(*parts)
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with source.extractfile(member) as input_file, target.open("wb") as output:
                    while chunk := input_file.read(1024 * 1024):
                        output.write(chunk)
                target.chmod(member.mode & 0o777)
    compiler = destination / "bend/bin/bend"
    base = destination / "bend/bend2/base.bend"
    if not compiler.is_file() or not os.access(compiler, os.X_OK) or not base.is_file():
        raise ValueError("Release archive does not contain an executable Bend and matching Base")
    return compiler, base


def write_results(results):
    path = Path("build/bend-release-monitor-results.json")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(results, indent=2, sort_keys=True) + "\n")


def record_results(github, outcomes):
    if not isinstance(outcomes, list):
        raise ValueError("Release result file must contain a list")
    if not outcomes:
        return 0
    state, content_sha = github.load_state()
    seen = set()
    for outcome in outcomes:
        if not isinstance(outcome, dict):
            raise ValueError("Release result must be an object")
        release_id = outcome.get("release_id")
        if not isinstance(release_id, int) or release_id <= 0 or release_id in seen:
            raise ValueError("Invalid or duplicate release result ID")
        seen.add(release_id)
        if (version(outcome.get("tag", "")) is None or
                not re.fullmatch(r"[0-9a-f]{40}", outcome.get("commit", "")) or
                not re.fullmatch(r"[0-9a-f]{64}", outcome.get("digest", "")) or
                outcome.get("status") not in ("passed", "failed") or
                not isinstance(outcome.get("exit_code"), int)):
            raise ValueError(f"Invalid release result for ID {release_id}")
        state["results"][str(release_id)] = outcome
    github.save_state(state, content_sha)
    return len(outcomes)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="List candidates without testing or writing state")
    parser.add_argument("--record", action="store_true", help="Persist completed results from the test job")
    args = parser.parse_args()
    if args.dry_run and args.record:
        parser.error("--dry-run and --record cannot be combined")
    token = os.environ.get("GITHUB_TOKEN", "")
    github = GitHub(token, os.environ.get("GITHUB_REPOSITORY", "dearlordylord/bend-idea"))
    if args.record:
        if not token:
            parser.error("GITHUB_TOKEN is required to record release results")
        path = Path("build/bend-release-monitor-results.json")
        if not path.is_file():
            raise ValueError("Test job did not upload a release result file")
        outcomes = json.loads(path.read_text())
        count = record_results(github, outcomes)
        print(f"Recorded {count} Bend release result(s) on {STATE_BRANCH}")
        return 0
    retry_tag = os.environ.get("BEND_RELEASE_TAG", "").strip()
    if retry_tag and version(retry_tag) is None:
        parser.error("BEND_RELEASE_TAG must be an exact vMAJOR.MINOR.PATCH tag")
    state, _ = github.load_state()
    releases = github.releases()
    selected = candidates(releases, state["results"], retry_tag)
    pending = pending_assets(releases) if not retry_tag else []
    print(f"Bend releases to test: {', '.join(item['tag'] for item in selected) or '(none)'}", flush=True)
    if pending:
        print(f"Published releases awaiting a verifiable Linux archive: {', '.join(pending)}", flush=True)
    if args.dry_run:
        return 0
    write_results([])
    outcomes = []
    plugin_commit = os.environ.get("GITHUB_SHA") or subprocess.check_output(
        ["git", "rev-parse", "HEAD"], text=True).strip()
    failures = False
    for candidate in selected:
        tag = candidate["tag"]
        commit = github.tag_commit(tag)
        with tempfile.TemporaryDirectory(prefix="bend-release-") as temporary:
            directory = Path(temporary)
            archive = directory / candidate["asset_name"]
            download_archive(candidate, archive)
            compiler, base = extract_archive(archive, directory)
            env = os.environ.copy()
            env.pop("GITHUB_TOKEN", None)
            env["BEND_TEST_CURRENT_COMPILER"] = str(compiler)
            env["BEND_TEST_CURRENT_BASE"] = str(base)
            print(f"Testing {tag} at {commit}, archive sha256:{candidate['digest']}", flush=True)
            try:
                code = subprocess.run(
                    ["./gradlew", "--no-daemon", "-PbendReleaseSmoke=true", "test"],
                    env=env, timeout=1_200, check=False
                ).returncode
            except subprocess.TimeoutExpired:
                code = 124
        outcome = {
            "suite": COMPATIBILITY_SUITE,
            "plugin_commit": plugin_commit,
            "release_id": candidate["id"],
            "tag": tag,
            "commit": commit,
            "digest": candidate["digest"],
            "status": "passed" if code == 0 else "failed",
            "exit_code": code,
            "run_url": f"{os.environ.get('GITHUB_SERVER_URL', 'https://github.com')}/{github.repository}/actions/runs/{os.environ.get('GITHUB_RUN_ID', '')}",
        }
        outcomes.append(outcome)
        write_results(outcomes)
        failures |= code != 0
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with Path(summary).open("a") as output:
            output.write(f"## Bend release compatibility\n\nSuite: `{COMPATIBILITY_SUITE}`; plugin commit: `{plugin_commit}`.\n\n")
            output.write("| Release | Result | Source commit | Archive SHA-256 |\n| --- | --- | --- | --- |\n")
            for result in outcomes:
                output.write(f"| {result['tag']} | {result['status']} | `{result['commit']}` | `{result['digest']}` |\n")
            if not outcomes:
                output.write("\nNo unseen published releases.\n")
            if pending:
                output.write(f"\nAwaiting a verifiable Linux archive: {', '.join(pending)}.\n")
    return 1 if failures or pending else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, ValueError, RuntimeError, tarfile.TarError) as error:
        print(f"Bend release monitor failed: {error}", file=sys.stderr)
        sys.exit(1)
