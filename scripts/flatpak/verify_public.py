#!/usr/bin/env python3
"""Verify locally signed public output against the accepted repository identity."""
import argparse
import base64
import configparser
import json
import re
import subprocess
import tempfile
from pathlib import Path
from urllib.parse import urlsplit
from prepare import APP_ID, APP_NAME, PROJECT_URL, release_source


def validate_identity(repository_url, fingerprint):
    parsed = urlsplit(repository_url)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment or not repository_url.endswith("/")
            or any(character.isspace() for character in repository_url)):
        raise ValueError("An explicit HTTPS repository URL ending in / is required")
    if not re.fullmatch(r"(?:[0-9A-F]{40}|[0-9A-F]{64})", fingerprint):
        raise ValueError("An explicit full uppercase signing fingerprint is required")


def read_accepted_key(path, fingerprint):
    with tempfile.TemporaryDirectory() as signing:
        # A relative home also works when native Python invokes an MSYS GnuPG executable.
        command = ["gpg", "--no-options", "--homedir", ".", "--batch"]
        public_key = subprocess.run(command + ["--dearmor"], input=path.read_bytes(), cwd=signing,
                                    check=True, capture_output=True).stdout
        if not public_key:
            raise ValueError("The accepted public key is empty")
        records = subprocess.run(command + ["--with-colons", "--show-keys"], input=public_key, cwd=signing,
                                 check=True, capture_output=True).stdout.decode("utf-8")
    fingerprints = []
    primary = False
    for line in records.splitlines():
        fields = line.split(":")
        if fields[0] in ("pub", "sub"):
            primary = fields[0] == "pub"
        elif fields[0] == "fpr" and primary:
            fingerprints.append(fields[9])
            primary = False
    if fingerprints != [fingerprint]:
        raise ValueError("The accepted public key differs from the signing fingerprint")
    return public_key


def verify_descriptors(public, repository_url, public_key):
    for name, section in ((f"{APP_NAME}.flatpakref", "Flatpak Ref"),
                          (f"{APP_NAME.lower()}.flatpakrepo", "Flatpak Repo")):
        descriptor = configparser.ConfigParser(interpolation=None)
        descriptor.read(public / name)
        values = descriptor[section]
        if values["Url"] != repository_url:
            raise ValueError("The installer points to a different update remote")
        if base64.b64decode(values["GPGKey"], validate=True) != public_key:
            raise ValueError("The installer signing key differs from the pinned identity")
        if section == "Flatpak Ref" and (values["Name"] != APP_ID or values["Branch"] != "stable"):
            raise ValueError("The installer application/branch differs from the stable contract")


def verify(public, source, release, repository_url, signing_fingerprint, accepted_public_key):
    version, _, url, digest = release_source(release)
    validate_identity(repository_url, signing_fingerprint)
    provenance = json.loads(source.read_text())
    if (provenance.get("application_version"), provenance.get("source_url"), provenance.get("source_sha256"),
        provenance.get("signing_fingerprint"), provenance.get("repository_url"), provenance.get("source_code")) != (
            version, url, digest, signing_fingerprint, repository_url, f"{PROJECT_URL}/tree/v{version}"):
        raise ValueError("Published provenance differs from the canonical release/signing identity")
    allowed = {"repo", f"{APP_NAME.lower()}.asc", f"{APP_NAME}.flatpakref",
               f"{APP_NAME.lower()}.flatpakrepo", ".nojekyll", "index.html"}
    if {p.name for p in public.iterdir()} != allowed:
        raise ValueError("Unexpected files in the public publication root")
    public_key = read_accepted_key(accepted_public_key, signing_fingerprint)
    provided_key = read_accepted_key(public / f"{APP_NAME.lower()}.asc", signing_fingerprint)
    if public_key != provided_key:
        raise ValueError("The published key differs from accepted source")
    verify_descriptors(public, repository_url, public_key)
    if any(p.is_symlink() for p in public.rglob("*")):
        raise ValueError("Publication must contain ordinary files, not symlinks")
    if sum(p.stat().st_size for p in public.rglob("*") if p.is_file()) > 900 * 1024 * 1024:
        raise ValueError("Publication exceeds the Pages size budget")
    print(f"PUBLIC_FILES_OK version={version} fingerprint={signing_fingerprint}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ("public", "source", "release-json", "accepted-public-key"):
        parser.add_argument("--" + option, required=True, type=Path)
    parser.add_argument("--repository-url", required=True)
    parser.add_argument("--signing-fingerprint", required=True)
    args = parser.parse_args()
    verify(args.public, args.source, json.loads(args.release_json.read_text()),
           args.repository_url, args.signing_fingerprint, args.accepted_public_key)
