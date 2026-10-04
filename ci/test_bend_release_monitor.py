import io
from pathlib import Path
import tarfile
import tempfile
import unittest

from ci.bend_release_monitor import COMPATIBILITY_SUITE, candidates, extract_archive, pending_assets, record_results


def release(number, release_id, digest="a" * 64, assets=True):
    tag = f"v2.0.{number}"
    return {
        "id": release_id,
        "tag_name": tag,
        "published_at": f"2026-09-27T{number % 24:02d}:00:00Z",
        "draft": False,
        "prerelease": False,
        "assets": ([{
            "name": f"bend-2.0.{number}-linux-x64.tar.gz",
            "state": "uploaded",
            "digest": f"sha256:{digest}",
        }] if assets else []),
    }


class CandidateTests(unittest.TestCase):
    def test_all_unseen_releases_are_kept_even_when_published_on_one_day(self):
        found = candidates([release(38, 38), release(37, 37), release(36, 36), release(35, 35)], {})
        self.assertEqual(["v2.0.36", "v2.0.37", "v2.0.38"], [item["tag"] for item in found])

    def test_recorded_result_is_skipped_but_changed_digest_is_retested(self):
        recorded = {"38": {"tag": "v2.0.38", "digest": "a" * 64, "status": "failed", "suite": COMPATIBILITY_SUITE}}
        self.assertEqual([], candidates([release(38, 38)], recorded))
        self.assertEqual(1, len(candidates([release(38, 38, "b" * 64)], recorded)))
        self.assertEqual(1, len(candidates([release(38, 38)], recorded, "v2.0.38")))

    def test_unfinished_asset_is_left_for_the_next_poll(self):
        self.assertEqual([], candidates([release(38, 38, assets=False)], {}))
        self.assertEqual(["v2.0.38"], pending_assets([release(38, 38, assets=False)]))
        with self.assertRaises(ValueError):
            candidates([release(38, 38, assets=False)], {}, "v2.0.38")

    def test_duplicate_tag_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Duplicate published Bend tag"):
            candidates([release(38, 38), release(38, 39)], {})


class ArchiveTests(unittest.TestCase):
    def test_archive_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / "bad.tar.gz"
            with tarfile.open(archive, "w:gz") as output:
                data = b"bad"
                entry = tarfile.TarInfo("bend/../outside")
                entry.size = len(data)
                output.addfile(entry, io.BytesIO(data))
            with self.assertRaisesRegex(ValueError, "Unsafe release archive path"):
                extract_archive(archive, Path(temporary) / "unpacked")
            self.assertFalse((Path(temporary) / "outside").exists())


class ResultTests(unittest.TestCase):
    def test_failed_result_is_recorded_without_marking_it_passed(self):
        class Store:
            saved = None

            def load_state(self):
                return {"schema": 1, "results": {}}, None

            def save_state(self, state, content_sha):
                self.saved = state

        store = Store()
        result = {
            "release_id": 32,
            "tag": "v2.0.32",
            "commit": "a" * 40,
            "digest": "b" * 64,
            "status": "failed",
            "exit_code": 1,
        }
        self.assertEqual(1, record_results(store, [result]))
        self.assertEqual("failed", store.saved["results"]["32"]["status"])

    def test_invalid_result_does_not_write_state(self):
        class Store:
            saved = False

            def load_state(self):
                return {"schema": 1, "results": {}}, None

            def save_state(self, state, content_sha):
                self.saved = True

        store = Store()
        with self.assertRaises(ValueError):
            record_results(store, [{"release_id": 32, "status": "passed"}])
        self.assertFalse(store.saved)


if __name__ == "__main__":
    unittest.main()
