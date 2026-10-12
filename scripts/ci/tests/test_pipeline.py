import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("pipeline", Path(__file__).parents[1] / "pipeline.py")
pipeline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pipeline)


class PipelineTest(unittest.TestCase):
    def test_accepts_stable_and_prerelease_tags(self):
        for tag in ("v0.2.1", "v1.0.0", "v0.2.2-ui", "v1.2.3-rc.1"):
            self.assertEqual(tag, pipeline.validate_tag(tag))

    def test_rejects_refs_options_and_shell_injection(self):
        for tag in ("main", "--help", "v1.2", "v01.2.3", "v1.2.3\nother", "v1.2.3;echo hi", "v1.2.3/../../tmp", "v1.2.3+$()"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                pipeline.validate_tag(tag)

    def test_common_checks_always_run(self):
        self.assertEqual([":protocol:test", ":app:testDebugUnitTest", ":app:lintDebug", ":app:assembleDebug"], pipeline.tasks("verify", False))

    def test_future_ui_tests_compile_but_do_not_claim_execution(self):
        tasks = pipeline.tasks("verify", True)
        self.assertIn(":app:assembleDebugAndroidTest", tasks)
        self.assertNotIn(":app:connectedDebugAndroidTest", tasks)

    def test_release_adds_release_checks(self):
        self.assertEqual([":app:lintRelease", ":app:assembleRelease"], pipeline.tasks("release", False)[-2:])

    def test_reads_version_from_actual_apk(self):
        self.assertEqual({"application_id": "info.remote.appletv", "version_code": 5, "version_name": "0.2.1"},
            pipeline.apk_metadata("package: name='info.remote.appletv' versionCode='5' versionName='0.2.1'\n"))

    def test_rejects_wrong_application(self):
        with self.assertRaises(ValueError):
            pipeline.apk_metadata("package: name='other.app' versionCode='5' versionName='0.2.1'")

    def test_rejects_version_mismatch(self):
        with self.assertRaises(ValueError):
            pipeline.check_version("v0.2.2", {"version_name": "0.2.1"})

    def test_stages_only_allowlisted_unsigned_candidate_and_hashes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); apk = root / "input.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"test manifest")
            output = root / "out"
            pipeline.stage_release(apk, output, {"version_name": "0.2.1"}, "v0.2.1", "a" * 40)
            self.assertEqual({"app-release-unsigned.apk", "SHA256SUMS", "release-metadata.json"}, {p.name for p in output.iterdir()})
            metadata = json.loads((output / "release-metadata.json").read_text())
            self.assertEqual("unsigned", metadata["signing"])
            self.assertEqual(pipeline.sha256(apk), metadata["apk_sha256"])
            self.assertEqual("a" * 40, metadata["commit"])
            with self.assertRaises(FileExistsError):
                pipeline.stage_release(apk, output, {"version_name": "0.2.1"}, "v0.2.1", "a" * 40)

    def test_invalid_commit_never_creates_candidate(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "out"
            with self.assertRaises(ValueError):
                pipeline.stage_release(Path(tmp) / "missing.apk", out, {"version_name": "0.2.1"}, "v0.2.1", "main")
            self.assertFalse(out.exists())
