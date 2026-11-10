import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("verify_release", Path(__file__).with_name("verify-release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)

CERTIFICATE = "2f07a019e22b065033e7ccdb0f555bad1fe98b584798eb4507152cc12543dfab"
CERTS = f"Signer #1 certificate DN: CN=Android Debug, O=Android, C=US\nSigner #1 certificate SHA-256 digest: {CERTIFICATE}\n"
MANIFEST = "package: name='app.kinjar' versionCode='14' versionName='0.3.11' platformBuildVersionName='17'\nsdkVersion:'26'\ntargetSdkVersion:'36'\napplication-label:'kinjar'\n"


class ReleaseMetadataTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / "kinjar-release.apk"
        self.payload = b"test APK payload\x00\xff"
        self.apk.write_bytes(self.payload)
        self.env = patch.dict(os.environ, {"ANDROID_HOME": "/test-sdk"})
        self.env.start()
        self.addCleanup(self.env.stop)
        self.stdout = contextlib.redirect_stdout(io.StringIO())
        self.stdout.__enter__()
        self.addCleanup(self.stdout.__exit__, None, None, None)

    def verify(self, manifest=MANIFEST, certs=CERTS, tag="v0.3.11"):
        with patch.object(release.subprocess, "check_output", side_effect=[certs, manifest]) as commands:
            result = release.verify(str(self.apk), tag)
        self.assertEqual(commands.call_args_list[0].args[0], [
            "/test-sdk/build-tools/37.0.0/apksigner", "verify", "--print-certs", str(self.apk),
        ])
        self.assertEqual(commands.call_args_list[1].args[0], [
            "/test-sdk/build-tools/37.0.0/aapt", "dump", "badging", str(self.apk),
        ])
        return result

    def test_metadata_describes_verified_apk_bytes_and_manifest(self):
        self.assertEqual(self.verify(), {
            "schemaVersion": 1,
            "versionCode": 14,
            "versionName": "0.3.11",
            "minSdk": 26,
            "apkUrl": "https://github.com/KljajicS/kinjar/releases/download/v0.3.11/kinjar-release.apk",
            "sizeBytes": len(self.payload),
            "sha256": hashlib.sha256(self.payload).hexdigest(),
        })

    def test_versions_and_minimum_sdk_come_from_apk(self):
        manifest = MANIFEST.replace("versionCode='14'", "versionCode='27'").replace("sdkVersion:'26'", "sdkVersion:'29'")
        result = self.verify(manifest)
        self.assertEqual(result["versionCode"], 27)
        self.assertEqual(result["minSdk"], 29)

    def test_rejects_invalid_package_version_sdk_and_debuggable_build(self):
        manifests = [
            MANIFEST.replace("app.kinjar", "app.other"),
            MANIFEST.replace("versionCode='14'", "versionCode='0'"),
            MANIFEST.replace("versionCode='14'", "versionCode='invalid'"),
            MANIFEST.replace("versionName='0.3.11'", "versionName='0.3.12'"),
            MANIFEST.replace("versionName='0.3.11'", "versionName='0.3.11-beta'"),
            MANIFEST.replace("sdkVersion:'26'\n", ""),
            MANIFEST.replace("sdkVersion:'26'", "sdkVersion:'25'"),
            MANIFEST.replace("sdkVersion:'26'", "sdkVersion:'Q'"),
            MANIFEST + "application-debuggable\n",
        ]
        for manifest in manifests:
            with self.subTest(manifest=manifest), self.assertRaises(ValueError):
                self.verify(manifest)

    def test_rejects_invalid_tags(self):
        for tag in ("0.3.11", "v0.3", "v0.3.11-beta", "v0.3.11/other", "v0.3.11\n"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                self.verify(tag=tag)

    def test_requires_exact_retained_signer(self):
        for certs in ("", CERTS.replace(CERTIFICATE, "a" * 64), CERTS + CERTS):
            with self.subTest(certs=certs), self.assertRaises(ValueError):
                self.verify(certs=certs)
        self.verify(certs=CERTS.replace(CERTIFICATE, CERTIFICATE.upper()))

    def test_rejects_empty_and_oversized_apk_before_sdk_tools(self):
        for size in (0, 256 * 1024 * 1024 + 1):
            with self.subTest(size=size):
                with self.apk.open("wb") as apk:
                    apk.truncate(size)
                with patch.object(release.subprocess, "check_output") as commands:
                    with self.assertRaises(ValueError):
                        release.verify(str(self.apk), "v0.3.11")
                    commands.assert_not_called()

    def test_rejects_oversized_metadata(self):
        version = "1." + "1" * 16_384 + ".0"
        with self.assertRaisesRegex(ValueError, "16 KiB"):
            self.verify(manifest=MANIFEST.replace("0.3.11", version), tag="v" + version)

    def test_rejects_apk_size_change_during_verification(self):
        for contents in (b"short", self.payload + b"changed"):
            with self.subTest(contents=contents):
                self.apk.write_bytes(self.payload)

                def sdk_output(command, **kwargs):
                    if command[1] == "verify":
                        return CERTS
                    self.apk.write_bytes(contents)
                    return MANIFEST

                with patch.object(release.subprocess, "check_output", side_effect=sdk_output):
                    with self.assertRaisesRegex(ValueError, "APK changed"):
                        release.verify(str(self.apk), "v0.3.11")

    def test_cli_writes_metadata_only_after_successful_verification(self):
        output = Path(self.directory.name) / "update.json"
        with patch.object(release.subprocess, "check_output", side_effect=[CERTS, MANIFEST]):
            self.assertEqual(release.main([str(self.apk), "v0.3.11", "--metadata", str(output)]), 0)
        self.assertEqual(json.loads(output.read_text()), self.verify())
        self.assertTrue(output.read_bytes().endswith(b"\n"))

    def test_failed_signature_verification_does_not_emit_metadata(self):
        output = Path(self.directory.name) / "update.json"
        failure = subprocess.CalledProcessError(1, "apksigner")
        with patch.object(release.subprocess, "check_output", side_effect=failure), contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(release.main([str(self.apk), "v0.3.11", "--metadata", str(output)]), 1)
        self.assertFalse(output.exists())

    def test_original_cli_still_verifies_without_metadata_output(self):
        with patch.object(release.subprocess, "check_output", side_effect=[CERTS, MANIFEST]):
            self.assertEqual(release.main([str(self.apk), "v0.3.11"]), 0)
        self.assertEqual(list(Path(self.directory.name).iterdir()), [self.apk])


if __name__ == "__main__":
    unittest.main()
