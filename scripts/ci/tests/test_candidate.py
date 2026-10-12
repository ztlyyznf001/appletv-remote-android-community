"""Candidate trust-boundary tests; no SDK, credentials or network required."""
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location("pipeline", Path(__file__).parents[1] / "pipeline.py")
pipeline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pipeline)

METADATA = {"application_id": "info.remote.appletv", "version_code": 7, "version_name": "0.2.2"}
UNSIGNED = subprocess.CompletedProcess([], 1, "", "DOES NOT VERIFY\nERROR: Missing META-INF/MANIFEST.MF\n")


class CandidateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.apk = self.root / "input.apk"
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"synthetic manifest")

    def environment(self):
        return {"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/main",
                "GITHUB_REPOSITORY": pipeline.SOURCE_REPOSITORY, "GITHUB_SHA": "a" * 40,
                "RELEASE_VERSION_NAME": "0.2.2", "RELEASE_SOURCE_COMMIT": "b" * 40}

    def test_inputs_reject_shell_options_newlines_refs_and_invalid_sha(self):
        for value in ("", "main", "--help", "a" * 39, "A" * 40, "b" * 40 + "\n", "$(id)", "a" * 40 + ";id"):
            environment = self.environment()
            environment["RELEASE_SOURCE_COMMIT"] = value
            with self.subTest(value=value), patch.object(pipeline, "git") as git, self.assertRaises(ValueError):
                pipeline.validate_candidate_inputs(self.root, environment)
            git.assert_not_called()
        for value in ("", "v0.2.2", "0.2", "0.02.2", "0.2.2\nsource_commit=bad", "0.2.2;id", "$(id)", "0.2.2+build"):
            environment = self.environment()
            environment["RELEASE_VERSION_NAME"] = value
            with self.subTest(value=value), patch.object(pipeline, "git") as git, self.assertRaises(ValueError):
                pipeline.validate_candidate_inputs(self.root, environment)
            git.assert_not_called()

    def test_dispatch_requires_exact_repository_main_and_manual_event(self):
        for key, value in (("GITHUB_EVENT_NAME", "push"), ("GITHUB_REF", "refs/tags/v0.2.2"),
                           ("GITHUB_REF", "refs/heads/topic"), ("GITHUB_REPOSITORY", "someone/fork")):
            environment = self.environment()
            environment[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                pipeline.validate_candidate_inputs(self.root, environment)

    def test_inputs_require_exact_control_and_real_commit_object(self):
        for git_results in (("c" * 40,), ("a" * 40, "tag"), ("a" * 40, "tree")):
            with self.subTest(results=git_results), patch.object(pipeline, "git", side_effect=git_results), self.assertRaises(ValueError):
                pipeline.validate_candidate_inputs(self.root, self.environment())

    def test_inputs_verify_source_and_dispatch_main_ancestry(self):
        with patch.object(pipeline, "git", side_effect=["a" * 40, "commit"]), patch.object(pipeline.subprocess, "run") as run:
            self.assertEqual({"version_name": "0.2.2", "source_commit": "b" * 40},
                             pipeline.validate_candidate_inputs(self.root, self.environment()))
            self.assertEqual(["git", "merge-base", "--is-ancestor", "a" * 40, "refs/remotes/origin/main"], run.call_args_list[0].args[0])
            self.assertEqual(["git", "merge-base", "--is-ancestor", "b" * 40, "a" * 40], run.call_args_list[1].args[0])

    def test_non_main_source_is_rejected_with_real_git(self):
        def git(*args):
            return subprocess.check_output(["git", *args], cwd=self.root, text=True, stderr=subprocess.DEVNULL).strip()
        git("init", "--initial-branch=main")
        git("config", "user.name", "Candidate test")
        git("config", "user.email", "14541874+ztlyyznf001@users.noreply.github.com")
        git("commit", "--allow-empty", "-m", "main source")
        main = git("rev-parse", "HEAD")
        git("update-ref", "refs/remotes/origin/main", main)
        git("checkout", "-b", "outside-main")
        git("commit", "--allow-empty", "-m", "not reviewed on main")
        outside = git("rev-parse", "HEAD")
        git("checkout", "main")
        environment = {**self.environment(), "GITHUB_SHA": main, "RELEASE_SOURCE_COMMIT": outside}
        with self.assertRaises(subprocess.CalledProcessError):
            pipeline.validate_candidate_inputs(self.root, environment)
        environment["RELEASE_SOURCE_COMMIT"] = main
        self.assertEqual(main, pipeline.validate_candidate_inputs(self.root, environment)["source_commit"])

    def test_source_identity_rejects_wrong_commit_or_dirty_tree(self):
        with patch.object(pipeline, "git", return_value="a" * 40), self.assertRaises(ValueError):
            pipeline.source_identity(self.root, "b" * 40)
        with patch.object(pipeline, "git", side_effect=["a" * 40, " M source.kt"]), self.assertRaises(ValueError):
            pipeline.source_identity(self.root, "a" * 40)
        with patch.object(pipeline, "git", side_effect=["a" * 40, "", "b" * 40]):
            self.assertEqual(("a" * 40, "b" * 40), pipeline.source_identity(self.root, "a" * 40))

    def test_rejects_debuggable_and_malformed_apk_metadata(self):
        badging = "package: name='info.remote.appletv' versionCode='7' versionName='0.2.2'\n"
        for text in (badging + "application-debuggable\n", badging.replace("versionCode='7'", "versionCode='0'"),
                     badging.replace("versionCode='7'", "versionCode='2100000001'"),
                     badging.replace("versionCode='7'", "versionCode='٧'"),
                     badging.replace("versionName='0.2.2'", "")):
            with self.subTest(badging=text), self.assertRaises(ValueError):
                pipeline.apk_metadata(text)

    def test_apksigner_must_report_exact_unsigned_result(self):
        with patch.object(pipeline.subprocess, "run", return_value=UNSIGNED):
            pipeline.verify_unsigned_apk(self.apk, self.root)
        for verification in (subprocess.CompletedProcess([], 0, "", ""),
                             subprocess.CompletedProcess([], 2, "", "tool error"),
                             subprocess.CompletedProcess([], 1, "", "corrupt signature")):
            with self.subTest(result=verification), patch.object(pipeline.subprocess, "run", return_value=verification), self.assertRaises(ValueError):
                pipeline.verify_unsigned_apk(self.apk, self.root)

    def test_rejects_v1_signature_material_before_external_verifier(self):
        for name in ("META-INF/MANIFEST.MF", "META-INF/CERT.RSA", "META-INF/CERT.SF", "META-INF/CERT.DSA", "META-INF/CERT.EC"):
            with zipfile.ZipFile(self.apk, "w") as archive:
                archive.writestr("AndroidManifest.xml", b"synthetic manifest")
                archive.writestr(name, b"synthetic signature")
            with self.subTest(name=name), patch.object(pipeline.subprocess, "run") as run, self.assertRaises(ValueError):
                pipeline.verify_unsigned_apk(self.apk, self.root)
            run.assert_not_called()

    def test_rejects_v2_v3_signing_block_even_if_signature_is_invalid(self):
        original = self.apk.read_bytes()
        with zipfile.ZipFile(self.apk) as archive:
            directory = archive.start_dir
        block = struct.pack("<Q", 24) * 2 + b"APK Sig Block 42"
        modified = bytearray(original[:directory] + block + original[directory:])
        end = modified.rfind(b"PK\x05\x06")
        struct.pack_into("<I", modified, end + 16, directory + len(block))
        self.apk.write_bytes(modified)
        with patch.object(pipeline.subprocess, "run") as run, self.assertRaises(ValueError):
            pipeline.verify_unsigned_apk(self.apk, self.root)
        run.assert_not_called()

    def test_manifest_exactly_binds_apk_version_hash_repository_commit_and_tree(self):
        out = self.root / "out"
        pipeline.stage_release(self.apk, out, METADATA, "v0.2.2", "a" * 40, "b" * 40)
        digest = pipeline.sha256(self.apk)
        self.assertEqual({"schema": 1, "source_repository": pipeline.SOURCE_REPOSITORY,
                          "source_commit": "a" * 40, "source_tree": "b" * 40,
                          **METADATA, "apk_sha256": digest, "signing": "unsigned", "expected_tag": "v0.2.2"},
                         json.loads((out / "release-metadata.json").read_text()))
        self.assertEqual(digest + "  app-release-unsigned.apk\n", (out / "SHA256SUMS").read_text())
        self.assertEqual(self.apk.read_bytes(), (out / "app-release-unsigned.apk").read_bytes())

    def test_bad_tree_or_version_never_creates_candidate(self):
        for tag, tree in (("v0.2.3", "b" * 40), ("v0.2.2", "main")):
            with self.subTest(tag=tag, tree=tree), self.assertRaises(ValueError):
                pipeline.stage_release(self.apk, self.root / "out", METADATA, tag, "a" * 40, tree)
            self.assertFalse((self.root / "out").exists())
