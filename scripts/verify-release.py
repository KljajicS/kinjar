#!/usr/bin/env python3
import os
from pathlib import Path
import re
import subprocess
import sys


def verify(apk, tag):
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag):
        raise ValueError("Expected a version tag such as v0.3.7")
    build_tools = Path(os.environ["ANDROID_HOME"]) / "build-tools" / "37.0.0"
    certs = subprocess.check_output(
        [str(build_tools / "apksigner"), "verify", "--print-certs", apk], text=True
    )
    fingerprints = re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]+)", certs)
    expected = "2f07a019e22b065033e7ccdb0f555bad1fe98b584798eb4507152cc12543dfab"
    if [value.lower() for value in fingerprints] != [expected]:
        raise ValueError("APK signing certificate does not match installed private releases")
    manifest = subprocess.check_output(
        [str(build_tools / "aapt"), "dump", "badging", apk], text=True
    )
    package = re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", manifest, re.M)
    if not package or package[1] != "app.kinjar" or package[3] != tag[1:]:
        raise ValueError("APK package or version does not match the release")
    if re.search(r"^application-debuggable", manifest, re.M):
        raise ValueError("Refusing to publish a debuggable APK")
    print(f"Verified KinJar {package[3]}, version code {package[2]}, retained signing certificate")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Usage: verify-release.py APK VERSION_TAG")
    try:
        verify(sys.argv[1], sys.argv[2])
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(f"Release verification failed: {error}")
