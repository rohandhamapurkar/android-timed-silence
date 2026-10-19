"""Fail publication when a built APK has the wrong version or signing identity."""

import argparse
import json
import re
import subprocess


def release_version(tag):
    version = tag.removeprefix("v")
    if not re.fullmatch(r"(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})\.(0|[1-9]\d{0,2})", version):
        raise ValueError("Release tag must be MAJOR.MINOR.PATCH, optionally prefixed with v")
    major, minor, patch = map(int, version.split("."))
    code = major * 1_000_000 + minor * 1_000 + patch
    if code <= 0:
        raise ValueError("Release version must be greater than 0.0.0")
    return version, code


def verify_metadata(badging, certificates, tag, expected_certificate):
    version, code = release_version(tag)
    package = re.search(r"^package: (.*)$", badging, re.MULTILINE)
    if not package:
        raise ValueError("Missing APK package metadata")
    fields = dict(re.findall(r"(\w+)='([^']*)'", package[1]))
    for key, expected in {"name": "com.example.timedsilence", "versionName": version, "versionCode": str(code)}.items():
        if fields.get(key) != expected:
            raise ValueError(f"APK {key}: expected {expected!r}, got {fields.get(key)!r}")
    for key in ("(?:minSdkVersion|sdkVersion)", "targetSdkVersion"):
        if not re.search(rf"^{key}:'36'\s*$", badging, re.MULTILINE):
            raise ValueError(f"Expected {key} 36")
    if "application-debuggable" not in badging.splitlines():
        raise ValueError("Expected a debug APK")
    expected_certificate = expected_certificate.replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", expected_certificate):
        raise ValueError("Expected signing fingerprint must be a SHA-256 digest")
    actual = re.findall(r"^(?:Signer #\d+|V\d+(?:\.\d+)? Signer(?: #\d+)?):? certificate SHA-256 digest: ([0-9a-fA-F]+)\s*$",
                        certificates, re.MULTILINE)
    signer_count = re.search(r"^Number of signers: (\d+)\s*$", certificates, re.MULTILINE)
    count = int(signer_count[1]) if signer_count else len(actual)
    # New SDK tools report the same identity once per signature scheme.
    if count != 1 or {digest.lower() for digest in actual} != {expected_certificate}:
        raise ValueError("APK signing certificate does not match the persistent release identity")
    return {"package": fields["name"], "version": version, "versionCode": code, "certificateSha256": expected_certificate}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for argument in ("apk", "tag", "aapt2", "apksigner", "expected-cert-sha256"):
        parser.add_argument(f"--{argument}", required=True)
    args = parser.parse_args()
    # A bad signature or unreadable APK must fail even if its metadata looks right.
    certs = subprocess.run([args.apksigner, "verify", "--verbose", "--print-certs", args.apk],
                           check=True, capture_output=True, text=True).stdout
    badging = subprocess.run([args.aapt2, "dump", "badging", args.apk],
                             check=True, capture_output=True, text=True).stdout
    result = verify_metadata(badging, certs, args.tag, args.expected_cert_sha256)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
