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
SOURCE_REPOSITORY = "ztlyyznf001/appletv-remote-android-community"
APPLICATION_ID = "info.remote.appletv"
TAG = re.compile(r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?")


def validate_tag(tag):
    if not TAG.fullmatch(tag) or len(tag) > 96:
        raise ValueError("Use an existing version tag such as v0.2.2 or v0.2.2-rc.1")
    return tag


def validate_version_name(version):
    validate_tag("v" + version)
    return version


def validate_sha(value):
    if not re.fullmatch(r"[0-9a-f]{40}", value):
        raise ValueError("Source identity must be a full, lowercase 40-character Git SHA")
    return value


def git(root, *arguments):
    return subprocess.check_output(["git", *arguments], cwd=root, text=True).strip()


def validate_candidate_inputs(root, environment):
    """Run trusted main tooling before checking out the requested source commit."""
    if (environment.get("GITHUB_EVENT_NAME") != "workflow_dispatch"
            or environment.get("GITHUB_REF") != "refs/heads/main"
            or environment.get("GITHUB_REPOSITORY") != SOURCE_REPOSITORY):
        raise ValueError("Candidates require a manual dispatch from the public repository's main branch")
    version = validate_version_name(environment.get("RELEASE_VERSION_NAME", ""))
    commit = validate_sha(environment.get("RELEASE_SOURCE_COMMIT", ""))
    dispatch = validate_sha(environment.get("GITHUB_SHA", ""))
    if git(root, "rev-parse", "HEAD") != dispatch:
        raise ValueError("Control checkout must exactly match the main workflow dispatch SHA")
    if git(root, "cat-file", "-t", commit) != "commit":
        raise ValueError("Requested source must name a commit, not a tag or tree")
    # Both commits must still belong to main at the checked-out fetch snapshot.
    subprocess.run(["git", "merge-base", "--is-ancestor", dispatch, "refs/remotes/origin/main"], cwd=root, check=True)
    subprocess.run(["git", "merge-base", "--is-ancestor", commit, dispatch], cwd=root, check=True)
    return {"version_name": version, "source_commit": commit}


def source_identity(root, expected_commit=""):
    commit = validate_sha(git(root, "rev-parse", "HEAD"))
    if expected_commit and commit != validate_sha(expected_commit):
        raise ValueError("Source checkout does not exactly match the requested commit")
    if git(root, "status", "--porcelain", "--untracked-files=all"):
        raise ValueError("Source checkout must be clean; commit all source changes before building a candidate")
    tree = validate_sha(git(root, "rev-parse", "HEAD^{tree}"))
    return commit, tree


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
    if (values.get("name") != APPLICATION_ID or not values.get("versionCode", "").isascii()
            or not values.get("versionCode", "").isdigit()
            or not 1 <= int(values["versionCode"]) <= 2100000000):
        raise ValueError("Unexpected APK application ID or version code")
    if any(line.strip().startswith("application-debuggable") for line in badging.splitlines()):
        raise ValueError("Refusing a debuggable release candidate")
    version = validate_version_name(values.get("versionName", ""))
    return {"application_id": values["name"], "version_code": int(values["versionCode"]), "version_name": version}


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


def verify_unsigned_apk(apk, sdk):
    with zipfile.ZipFile(apk) as archive:
        if "AndroidManifest.xml" not in archive.namelist() or archive.testzip() is not None:
            raise ValueError("Invalid APK archive")
        # Refuse even invalid/expired signing material, rather than treating any
        # failed signature verification as evidence that an APK is unsigned.
        if any(re.fullmatch(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))", name, re.I)
               for name in archive.namelist()):
            raise ValueError("Refusing APK v1 signing material")
        with apk.open("rb") as source:
            source.seek(max(0, archive.start_dir - 16))
            if source.read(16) == b"APK Sig Block 42":
                raise ValueError("Refusing APK v2/v3 signing block")
    verification = subprocess.run([str(sdk / "build-tools/35.0.0/apksigner"), "verify", str(apk)], capture_output=True, text=True)
    output = (verification.stdout + "\n" + verification.stderr).strip()
    if verification.returncode == 0:
        raise ValueError("Refusing to label a signed APK as unsigned")
    if verification.returncode != 1 or output.splitlines() != ["DOES NOT VERIFY", "ERROR: Missing META-INF/MANIFEST.MF"]:
        raise ValueError("APK signature verification did not report the expected unsigned APK result")


def stage_release(apk, destination, metadata, tag, commit, tree):
    check_version(tag, metadata)
    validate_sha(commit)
    validate_sha(tree)
    if metadata.get("application_id") != APPLICATION_ID or type(metadata.get("version_code")) is not int:
        raise ValueError("Candidate metadata must come from the verified APK")
    with zipfile.ZipFile(apk) as archive:
        if "AndroidManifest.xml" not in archive.namelist() or archive.testzip() is not None:
            raise ValueError("Invalid APK archive")
    destination.mkdir(parents=True, exist_ok=False)
    target = destination / "app-release-unsigned.apk"
    shutil.copyfile(apk, target)
    digest = sha256(target)
    (destination / "SHA256SUMS").write_text(digest + "  app-release-unsigned.apk\n", encoding="utf-8")
    manifest = {"schema": 1, "source_repository": SOURCE_REPOSITORY,
        "source_commit": commit, "source_tree": tree, "version_name": metadata["version_name"],
        "version_code": metadata["version_code"], "application_id": metadata["application_id"],
        "apk_sha256": digest, "signing": "unsigned", "expected_tag": tag}
    (destination / "release-metadata.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=["verify", "release", "validate-candidate-inputs"])
    parser.add_argument("--tag", default="")
    parser.add_argument("--source-dir", type=Path, default=ROOT)
    parser.add_argument("--version-name", default=os.environ.get("RELEASE_VERSION_NAME", ""))
    parser.add_argument("--source-commit", default=os.environ.get("RELEASE_SOURCE_COMMIT", ""))
    args = parser.parse_args()
    root = args.source_dir.resolve()
    if args.mode == "validate-candidate-inputs":
        validated = validate_candidate_inputs(root, os.environ)
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            for key, value in validated.items():
                output.write(f"{key}={value}\n")
        return
    if args.mode == "release":
        if args.version_name:
            validate_version_name(args.version_name)
            if args.tag and args.tag != "v" + args.version_name:
                raise ValueError("Tag and version_name must agree")
            args.tag = "v" + args.version_name
        validate_tag(args.tag)
        commit, tree = source_identity(root, args.source_commit)
        if (root / "build/release-candidate").exists():
            raise ValueError("Candidate directory already exists; review and move it before a new build")
    sdk = android_sdk()
    android_tests = any(p.suffix in (".kt", ".java") for p in (root / "app/src/androidTest").rglob("*"))
    subprocess.run([str(root / "gradlew"), "--no-daemon", "--stacktrace", "--console=plain", *tasks(args.mode, android_tests)], cwd=root, check=True)
    if args.mode == "release":
        apk = root / "app/build/outputs/apk/release/app-release-unsigned.apk"
        if not apk.is_file():
            raise ValueError("Expected an unsigned release APK; signing is intentionally not configured by this workflow")
        badging = subprocess.check_output([str(sdk / "build-tools/35.0.0/aapt2"), "dump", "badging", str(apk)], text=True)
        metadata = apk_metadata(badging)
        verify_unsigned_apk(apk, sdk)
        if source_identity(root, commit) != (commit, tree):
            raise ValueError("Source identity changed during the build")
        stage_release(apk, root / "build/release-candidate", metadata, args.tag, commit, tree)
    print("Checks passed. Android instrumentation " + ("compiled only; not executed." if android_tests else "not configured in this source."))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print("CI error: " + str(error), file=sys.stderr)
        sys.exit(1)
