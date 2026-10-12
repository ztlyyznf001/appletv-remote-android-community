#!/usr/bin/env python3
"""The same checks run locally and in Actions. Never handles signing credentials."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
TAG = re.compile(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?")


def validate_tag(tag):
    if not TAG.fullmatch(tag) or len(tag) > 96:
        raise ValueError("Use an existing version tag such as v0.2.2 or v0.2.2-rc.1")
    return tag


def tasks(mode, has_android_tests):
    result = [":protocol:test", ":app:testDebugUnitTest", ":app:lintDebug", ":app:assembleDebug"]
    if has_android_tests:
        result.append(":app:assembleDebugAndroidTest")
    if mode == "release":
        result.extend([":app:lintRelease", ":app:assembleRelease"])
    return result


def apk_metadata(badging):
    first = next((line for line in badging.splitlines() if line.startswith("package: ")), "")
    values = dict(re.findall(r"(\w+)='([^']*)'", first))
    if values.get("name") != "info.remote.appletv" or not values.get("versionCode", "").isdigit():
        raise ValueError("Unexpected APK application ID or version code")
    return {"application_id": values["name"], "version_code": int(values["versionCode"]), "version_name": values["versionName"]}


def check_version(tag, metadata):
    validate_tag(tag)
    if tag != "v" + metadata["version_name"]:
        raise ValueError("Tag must exactly match the APK versionName (do not relabel an older APK)")


def android_sdk():
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not home:
        raise ValueError("Set ANDROID_HOME to an SDK containing platform 36 and build-tools 35.0.0")
    sdk = Path(home)
    for relative in ["platforms/android-36/android.jar", "build-tools/35.0.0/aapt2", "build-tools/35.0.0/apksigner"]:
        if not (sdk / relative).is_file():
            raise ValueError("Missing Android SDK component: " + relative + "; install it through the official SDK tools")
    return sdk


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stage_release(apk, destination, metadata, tag, commit):
    check_version(tag, metadata)
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Release source must be a full Git commit SHA")
    with zipfile.ZipFile(apk) as archive:
        if "AndroidManifest.xml" not in archive.namelist() or archive.testzip() is not None:
            raise ValueError("Invalid APK archive")
    destination.mkdir(parents=True, exist_ok=False)
    target = destination / "app-release-unsigned.apk"
    shutil.copyfile(apk, target)
    digest = sha256(target)
    (destination / "SHA256SUMS").write_text(digest + "  app-release-unsigned.apk\n", encoding="utf-8")
    (destination / "release-metadata.json").write_text(json.dumps({**metadata, "tag": tag, "commit": commit,
        "signing": "unsigned", "apk_sha256": digest}, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["verify", "release"])
    parser.add_argument("--tag", default="")
    args = parser.parse_args()
    if args.mode == "release":
        validate_tag(args.tag)
    sdk = android_sdk()
    android_tests = any(p.suffix in (".kt", ".java") for p in (ROOT / "app/src/androidTest").rglob("*"))
    subprocess.run([str(ROOT / "gradlew"), "--no-daemon", "--stacktrace", "--console=plain", *tasks(args.mode, android_tests)], cwd=ROOT, check=True)
    if args.mode == "release":
        apk = ROOT / "app/build/outputs/apk/release/app-release-unsigned.apk"
        if not apk.is_file():
            raise ValueError("Expected an unsigned release APK; signing is intentionally not configured by this workflow")
        badging = subprocess.check_output([str(sdk / "build-tools/35.0.0/aapt2"), "dump", "badging", str(apk)], text=True)
        metadata = apk_metadata(badging)
        verification = subprocess.run([str(sdk / "build-tools/35.0.0/apksigner"), "verify", str(apk)], capture_output=True)
        if verification.returncode == 0:
            raise ValueError("Refusing to label a signed APK as unsigned")
        commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        stage_release(apk, ROOT / "build/release-candidate", metadata, args.tag, commit)
    print("Checks passed. Android instrumentation " + ("compiled only; not executed." if android_tests else "not configured in this source."))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print("CI error: " + str(error), file=sys.stderr)
        sys.exit(1)
