#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def verify(apk, tag):
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag):
        raise ValueError("Expected a version tag such as v0.3.7")
    size = Path(apk).stat().st_size
    if not 1 <= size <= 256 * 1024 * 1024:
        raise ValueError("APK size must be between 1 byte and 256 MiB")
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
    min_sdk = re.search(r"^sdkVersion:'([0-9]+)'$", manifest, re.M)
    if not min_sdk or int(min_sdk[1]) < 26:
        raise ValueError("APK must declare a minimum SDK of at least 26")
    if int(package[2]) < 1:
        raise ValueError("APK version code must be positive")
    digest = hashlib.sha256()
    hashed_size = 0
    with Path(apk).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            hashed_size += len(chunk)
            if hashed_size > size:
                raise ValueError("APK changed during verification")
            digest.update(chunk)
    if hashed_size != size:
        raise ValueError("APK changed during verification")
    metadata = {
        "schemaVersion": 1,
        "versionCode": int(package[2]),
        "versionName": package[3],
        "minSdk": int(min_sdk[1]),
        "apkUrl": f"https://github.com/KljajicS/kinjar/releases/download/v{package[3]}/kinjar-release.apk",
        "sizeBytes": size,
        "sha256": digest.hexdigest(),
    }
    if len((json.dumps(metadata) + "\n").encode("utf-8")) > 16 * 1024:
        raise ValueError("Update metadata exceeds 16 KiB")
    print(f"Verified KinJar {package[3]}, version code {package[2]}, retained signing certificate")
    return metadata


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    parser.add_argument("tag")
    parser.add_argument("--metadata", type=Path)
    args = parser.parse_args(argv)
    try:
        metadata = verify(args.apk, args.tag)
        if args.metadata:
            args.metadata.write_text(json.dumps(metadata) + "\n", encoding="utf-8")
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print(f"Release verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
