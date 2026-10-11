import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("release", Path(__file__).resolve().parents[1] / "release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.name", "Release test")
        self.git("config", "user.email", "release@example.invalid")
        base = self.commit("android/app.txt", "baseline")
        self.config = {"base_commit": base, "base_version_code": 19, "series": "3.0", "paths": ["android/", "tools/"], "apk_asset": "app-release.apk"}

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.repo), *args], stderr=subprocess.DEVNULL).decode().strip()

    def commit(self, path, body):
        file = self.repo / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(body)
        self.git("add", "--all")
        self.git("commit", "-q", "-m", body)
        return self.git("rev-parse", "HEAD")

    def plan(self, published=()):
        return release.pending_releases(self.repo, self.config, published)

    def test_only_selected_folders_increment_version(self):
        self.commit("README.md", "docs")
        first = self.commit("android/app.txt", "app change")
        self.commit(".github/workflows/android.yml", "workflow only")
        second = self.commit("tools/import.py", "tool change")
        self.assertEqual([(r["sha"], r["tag"]) for r in self.plan()], [(first, "v3.0.20"), (second, "v3.0.21")])

    def test_each_commit_in_a_batch_gets_a_release(self):
        commits = [self.commit("android/app.txt", f"change {n}") for n in range(3)]
        self.assertEqual([r["sha"] for r in self.plan()], commits)
        self.assertEqual([r["version_code"] for r in self.plan()], [20, 21, 22])

    def test_a_change_and_revert_in_one_push_both_get_versions(self):
        self.commit("android/app.txt", "temporary change")
        self.commit("android/app.txt", "baseline")
        self.assertEqual(self.git("diff", self.config["base_commit"], "HEAD"), "")
        self.assertEqual([r["tag"] for r in self.plan()], ["v3.0.20", "v3.0.21"])

    def test_deletion_and_move_out_of_a_folder_count(self):
        self.git("mv", "android/app.txt", "outside.txt")
        self.git("commit", "-q", "-m", "move outside")
        self.assertEqual(self.plan()[0]["version_code"], 20)

    def test_merge_counts_the_change_entering_main_once(self):
        self.git("checkout", "-q", "-b", "feature")
        self.commit("android/app.txt", "feature one")
        self.commit("android/app.txt", "feature two")
        self.git("checkout", "-q", "main")
        self.commit("README.md", "parallel docs")
        self.git("merge", "-q", "--no-ff", "feature", "-m", "merge feature")
        self.assertEqual([(r["sha"], r["version_code"]) for r in self.plan()], [(self.git("rev-parse", "HEAD"), 20)])

    def test_successful_release_is_skipped_on_retry(self):
        self.commit("android/app.txt", "app change")
        self.git("tag", "-a", "v3.0.20", "-m", "released")
        self.assertEqual(self.plan([{"tag_name": "v3.0.20", "assets": ["app-release.apk"]}]), [])

    def test_existing_tag_with_missing_apk_is_retried(self):
        self.commit("android/app.txt", "app change")
        self.git("tag", "v3.0.20")
        self.assertEqual([r["tag"] for r in self.plan([{"tag_name": "v3.0.20", "assets": []}])], ["v3.0.20"])

    def test_existing_tag_is_never_moved(self):
        self.git("tag", "v3.0.20")
        self.commit("android/app.txt", "app change")
        with self.assertRaisesRegex(ValueError, "refusing to move"):
            self.plan()

    def test_old_commit_keeps_its_version_after_new_commits(self):
        first = self.commit("android/app.txt", "app change")
        self.commit("tools/import.py", "tool change")
        versions = release.versions(self.repo, self.config, first)
        self.assertEqual([(r["sha"], r["version_code"]) for r in versions], [(first, 20)])

    def test_full_history_is_required(self):
        self.config["base_commit"] = "0" * 40
        with self.assertRaisesRegex(ValueError, "full main branch"):
            self.plan()


if __name__ == "__main__":
    unittest.main()
