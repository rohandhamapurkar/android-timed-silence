import unittest
from unittest.mock import patch

from verify_apk import main, release_version, verify_metadata


class VerifyApkTest(unittest.TestCase):
    fingerprint = "ab" * 32
    badging = ("package: name='com.example.timedsilence' versionCode='1002003' versionName='1.2.3'\n"
               "minSdkVersion:'36'\ntargetSdkVersion:'36'\napplication-debuggable\n")

    def verify(self, badging=None, certificates=None, tag="v1.2.3"):
        return verify_metadata(self.badging if badging is None else badging,
                               f"Signer #1 certificate SHA-256 digest: {self.fingerprint}\n" if certificates is None else certificates,
                               tag, self.fingerprint)

    def test_accepts_matching_version_and_certificate(self):
        self.assertEqual(self.verify()["versionCode"], 1002003)
        self.assertEqual(self.verify(tag="1.2.3")["version"], "1.2.3")

    def test_rejects_wrong_package_version_code_and_platform(self):
        for old, new in [("com.example.timedsilence", "com.other.app"),
                         ("1002003", "1"), ("1.2.3", "1.0.1"),
                         ("minSdkVersion:'36'", "minSdkVersion:'35'"),
                         ("targetSdkVersion:'36'", "targetSdkVersion:'35'"),
                         ("application-debuggable", "")]:
            with self.subTest(field=old), self.assertRaises(ValueError):
                self.verify(badging=self.badging.replace(old, new))

    def test_rejects_missing_metadata(self):
        with self.assertRaises(ValueError):
            self.verify(badging="")

    def test_accepts_older_aapt_minimum_sdk_label(self):
        self.verify(badging=self.badging.replace("minSdkVersion:", "sdkVersion:"))

    def test_rejects_wrong_missing_and_multiple_signers(self):
        for certs in ["", f"Signer #1 certificate SHA-256 digest: {'cd' * 32}",
                      f"Signer #1 certificate SHA-256 digest: {self.fingerprint}\nSigner #2 certificate SHA-256 digest: {self.fingerprint}"]:
            with self.subTest(certificates=certs), self.assertRaises(ValueError):
                self.verify(certificates=certs)

    def test_rejects_malformed_tags(self):
        for tag in ["latest", "v1.2", "v1.2.3-beta", "v0.0.0", "v1.1000.0", "v01.2.3"]:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release_version(tag)

    def test_version_codes_increase_across_patch_minor_and_major(self):
        codes = [release_version(tag)[1] for tag in ["v1.0.1", "v1.0.2", "v1.1.0", "v2.0.0"]]
        self.assertEqual(codes, sorted(set(codes)))

    @patch("verify_apk.subprocess.run")
    def test_signature_failure_stops_metadata_checks(self, run):
        import subprocess
        run.side_effect = subprocess.CalledProcessError(1, "apksigner")
        with patch("sys.argv", ["verify_apk", "--apk", "bad.apk", "--tag", "v1.2.3",
                               "--aapt2", "aapt2", "--apksigner", "apksigner",
                               "--expected-cert-sha256", self.fingerprint]):
            with self.assertRaises(subprocess.CalledProcessError):
                main()
        run.assert_called_once()


if __name__ == "__main__":
    unittest.main()
