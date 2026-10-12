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
        self.assertEqual(["android-ci.yml"], [p.name for p in workflows])
        workflow = workflows[0].read_text()
        self.assertIn("contents: read", workflow)
        self.assertIn("Tests, lint and debug APK", workflow)
        for forbidden in ("secrets.", "contents: write", "pull_request_target:", "workflow_run:", "gh release"):
            self.assertNotIn(forbidden, workflow)

    def test_documentation_local_links_resolve(self):
        pages = [ROOT / name for name in ("README.md", "CONTRIBUTING.md", "SECURITY.md", "THIRD_PARTY_NOTICES.md", "protocol/README.md")]
        pages.extend((ROOT / "docs").glob("*.md"))
        for page in pages:
            for target in re.findall(r"\]\(([^)]+)\)", page.read_text()):
                if "://" in target or target.startswith("#"):
                    continue
                with self.subTest(page=page.relative_to(ROOT), target=target):
                    self.assertTrue((page.parent / target.split("#", 1)[0]).is_file())
