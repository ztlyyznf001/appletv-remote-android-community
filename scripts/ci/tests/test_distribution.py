"""Regression checks for the public source distribution, without credentials."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[3]
LICENSES = ROOT / "app/src/main/assets/licenses"


class DistributionTest(unittest.TestCase):
    def test_original_and_adapted_licenses_are_preserved(self):
        self.assertEqual((ROOT / "LICENSE").read_bytes(), (LICENSES / "PROJECT-MIT.txt").read_bytes())
        self.assertEqual((ROOT / "protocol/LICENSE-pyatv.txt").read_bytes(), (LICENSES / "pyatv-MIT.txt").read_bytes())
        self.assertIn("Pierre Ståhl", (LICENSES / "pyatv-MIT.txt").read_text())

    def test_runtime_license_texts_and_inventory_are_included(self):
        self.assertIn("END OF TERMS AND CONDITIONS", (LICENSES / "Apache-2.0.txt").read_text())
        bc = (LICENSES / "BouncyCastle-1.80.txt").read_text()
        self.assertIn("The Legion of the Bouncy Castle Inc.", bc)
        self.assertIn('THE SOFTWARE IS PROVIDED "AS IS"', bc)
        inventory = (LICENSES / "THIRD-PARTY.txt").read_text()
        coordinates = re.findall(r"^([\w.-]+:[\w.-]+:[\w.-]+) \|", inventory, re.M)
        self.assertEqual(57, len(set(coordinates)))
        self.assertFalse(any(c.startswith("junit:") for c in coordinates))
        self.assertFalse(any(c.startswith("org.hamcrest:") for c in coordinates))

    def test_test_only_licenses_are_separate(self):
        test_licenses = ROOT / "app/src/androidTest/assets/licenses"
        self.assertIn("Eclipse Public License", (test_licenses / "JUnit-4.13.2-EPL-1.0.txt").read_text())
        self.assertIn("Redistribution and use", (test_licenses / "Hamcrest-1.3-BSD.txt").read_text())
        self.assertFalse((LICENSES / "JUnit-4.13.2-EPL-1.0.txt").exists())

    def test_public_ci_has_no_signing_or_publication_job(self):
        workflows = sorted((ROOT / ".github/workflows").glob("*.yml"))
        self.assertEqual(["android-ci.yml", "android-release-candidate.yml"], [p.name for p in workflows])
        self.assertIn("Tests, lint and debug APK", workflows[0].read_text())
        for path in workflows:
            workflow = path.read_text()
            self.assertIn("permissions:\n  contents: read", workflow)
            for forbidden in ("secrets", ": write", "pull_request_target:", "workflow_run:", "gh release", "repository_dispatch", "environment:"):
                with self.subTest(workflow=path.name, forbidden=forbidden):
                    self.assertNotIn(forbidden, workflow)
            for action in re.findall(r"uses:\s+(\S+)", workflow):
                self.assertRegex(action, r"^[\w/-]+@[0-9a-f]{40}$")

    def test_release_candidate_is_manual_main_only_and_unsigned(self):
        workflow = (ROOT / ".github/workflows/android-release-candidate.yml").read_text()
        self.assertIn("workflow_dispatch:", workflow)
        self.assertNotIn("\n  push:", workflow)
        self.assertNotIn("\n  pull_request:", workflow)
        self.assertIn("github.ref == 'refs/heads/main'", workflow)
        self.assertIn("ref: ${{ github.sha }}", workflow)
        self.assertIn("ref: ${{ steps.validated.outputs.source_commit }}", workflow)
        self.assertIn("persist-credentials: false", workflow)
        self.assertIn("cache-read-only: true", workflow)
        self.assertIn("app-release-unsigned.apk", workflow)
        self.assertIn("release-metadata.json", workflow)
        # Inputs travel through environment variables, never interpolated into shell.
        self.assertEqual(2, workflow.count("${{ inputs."))

    def test_documentation_local_links_resolve(self):
        pages = [ROOT / name for name in ("README.md", "CONTRIBUTING.md", "SECURITY.md", "THIRD_PARTY_NOTICES.md", "protocol/README.md")]
        pages.extend((ROOT / "docs").glob("*.md"))
        for page in pages:
            for target in re.findall(r"\]\(([^)]+)\)", page.read_text()):
                if "://" in target or target.startswith("#"):
                    continue
                with self.subTest(page=page.relative_to(ROOT), target=target):
                    self.assertTrue((page.parent / target.split("#", 1)[0]).is_file())
