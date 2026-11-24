import base64
import copy
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from prepare import APP_ID, APP_NAME, PROJECT_URL
from publish import publish
from verify_public import read_accepted_key, verify, verify_descriptors


def release_fixture():
    return {"tag_name": "v2.10.0", "draft": False, "prerelease": False, "immutable": True,
            "html_url": f"{PROJECT_URL}/releases/tag/v2.10.0", "published_at": "2026-11-03T12:00:00Z",
            "assets": [{"name": f"{APP_NAME}-v2.10.0-linux-x64-release.zip",
                        "browser_download_url": f"{PROJECT_URL}/releases/download/v2.10.0/{APP_NAME}-v2.10.0-linux-x64-release.zip",
                        "digest": "sha256:" + "a" * 64}]}


class DescriptorTests(unittest.TestCase):
    def test_installer_cannot_substitute_remote_key_or_application(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repository_url = "https://example.org/naviamp/repo/"
            key = b"accepted public key"
            encoded = base64.b64encode(key).decode()
            reference = f"[Flatpak Ref]\nName={APP_ID}\nBranch=stable\nTitle={APP_NAME}\nUrl={repository_url}\nGPGKey={encoded}\n"
            repository = f"[Flatpak Repo]\nTitle={APP_NAME}\nUrl={repository_url}\nGPGKey={encoded}\n"
            (root / f"{APP_NAME.lower()}.flatpakrepo").write_text(repository)
            path = root / f"{APP_NAME}.flatpakref"
            path.write_text(reference)
            verify_descriptors(root, repository_url, key)
            for name, original in ((f"{APP_NAME}.flatpakref", reference),
                                   (f"{APP_NAME.lower()}.flatpakrepo", repository)):
                replacements = [original.replace("example.org", "attacker.example"),
                                original.replace(encoded, base64.b64encode(b"other key").decode()),
                                original.replace(encoded, "not-valid-base64!")]
                if name == f"{APP_NAME}.flatpakref":
                    replacements += [original.replace(APP_ID, "io.github.goosepod.Naviamp"),
                                     original.replace("Branch=stable", "Branch=testing")]
                for replacement in replacements:
                    with self.subTest(name=name, replacement=replacement):
                        (root / name).write_text(replacement)
                        with self.assertRaises(ValueError):
                            verify_descriptors(root, repository_url, key)
                (root / name).write_text(original)


class PublicationInputTests(unittest.TestCase):
    def test_commands_require_each_explicit_trust_input(self):
        for script, paths in (("publish.py", ("repo", "signing", "public", "assets", "release-json")),
                              ("verify_public.py", ("public", "source", "release-json"))):
            arguments = {**{name: "unused" for name in paths},
                         "repository-url": "https://example.org/repo/",
                         "signing-fingerprint": "A" * 40, "accepted-public-key": "accepted.asc"}
            for missing in ("repository-url", "signing-fingerprint", "accepted-public-key"):
                command = [sys.executable, str(Path(__file__).with_name(script))]
                for name, value in arguments.items():
                    if name != missing:
                        command.extend(("--" + name, value))
                with self.subTest(script=script, missing=missing):
                    result = subprocess.run(command, capture_output=True, text=True)
                    self.assertEqual(2, result.returncode)
                    self.assertIn("required", result.stderr)
                    self.assertIn("--" + missing, result.stderr)

    def test_publish_rejects_invalid_identity_before_creating_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for url, fingerprint in (("", "A" * 40), ("http://example.org/repo/", "A" * 40),
                                     ("https://user:password@example.org/repo/", "A" * 40),
                                     ("https://example.org/repo/?x=/", "A" * 40),
                                     ("https://example.org/repo/\n", "A" * 40),
                                     ("https://example.org/repo/", ""),
                                     ("https://example.org/repo/", "A" * 16)):
                with self.subTest(url=url, fingerprint=fingerprint), self.assertRaises(ValueError):
                    publish(root / "repo", root / "signing", root / "public", root / "assets",
                            release_fixture(), url, fingerprint, root / "accepted.asc")
                self.assertEqual([], list(root.iterdir()))

    def test_publish_rejects_public_signing_material_and_nonpristine_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            repo, public, assets = (root / name for name in ("repo", "public", "assets"))
            for signing in (root, repo, public / "signing", assets / "signing"):
                with self.subTest(signing=signing), self.assertRaisesRegex(ValueError, "Signing material"):
                    publish(repo, signing, public, assets, release_fixture(),
                            "https://example.org/repo/", "A" * 40, root / "accepted.asc")
                self.assertEqual([], list(root.iterdir()))
            for output in (public, assets):
                output.mkdir(exist_ok=True)
                existing = output / "existing"
                existing.write_text("must not overwrite")
                with self.subTest(output=output), self.assertRaisesRegex(ValueError, "fresh"):
                    publish(repo, root / "signing", public, assets, release_fixture(),
                            "https://example.org/repo/", "A" * 40, root / "accepted.asc")
                self.assertEqual("must not overwrite", existing.read_text())
                existing.unlink()


@unittest.skipUnless(shutil.which("gpg") and shutil.which("gpgconf"), "GnuPG is required for real key validation")
class PublicIdentityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.directory.cleanup)
        cls.signing = Path(cls.directory.name) / "signing"
        cls.signing.mkdir(mode=0o700)
        cls.addClassCleanup(subprocess.run, ["gpgconf", "--homedir", ".", "--kill", "gpg-agent"],
                            cwd=cls.signing, check=True, capture_output=True)
        command = ["gpg", "--no-options", "--homedir", ".", "--batch"]
        cls.keys = []
        for name in ("accepted", "unaccepted"):
            identity = f"Flatpak test {name} <{name}@example.org>"
            result = subprocess.run(command + ["--pinentry-mode", "loopback", "--passphrase", "",
                                               "--quick-generate-key", identity, "ed25519", "sign", "0"],
                                    cwd=cls.signing, capture_output=True)
            if result.returncode:
                raise AssertionError(f"GnuPG fixture key generation failed ({result.returncode}):\n"
                                     + result.stderr.decode("utf-8", errors="replace"))
            records = subprocess.run(command + ["--with-colons", "--list-keys", identity],
                                     cwd=cls.signing, check=True, capture_output=True, text=True).stdout
            fingerprint = next(line.split(":")[9] for line in records.splitlines() if line.startswith("fpr:"))
            armored = subprocess.run(command + ["--armor", "--export", fingerprint],
                                     cwd=cls.signing, check=True, capture_output=True).stdout
            binary = subprocess.run(command + ["--export", fingerprint], cwd=cls.signing,
                                    check=True, capture_output=True).stdout
            cls.keys.append((fingerprint, armored, binary))

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.public = self.root / "public"
        self.public.mkdir()
        (self.public / "repo").mkdir()
        for name in (".nojekyll", "index.html"):
            (self.public / name).touch()
        self.fingerprint, armored, self.key = self.keys[0]
        self.accepted = self.root / "accepted.asc"
        self.accepted.write_bytes(armored)
        (self.public / f"{APP_NAME.lower()}.asc").write_bytes(armored)
        self.url = "https://example.org/repo/"
        encoded = base64.b64encode(self.key).decode()
        (self.public / f"{APP_NAME}.flatpakref").write_text(
            f"[Flatpak Ref]\nName={APP_ID}\nBranch=stable\nTitle={APP_NAME}\nUrl={self.url}\nGPGKey={encoded}\n")
        (self.public / f"{APP_NAME.lower()}.flatpakrepo").write_text(
            f"[Flatpak Repo]\nTitle={APP_NAME}\nUrl={self.url}\nGPGKey={encoded}\n")
        self.release = release_fixture()
        self.provenance = {"application_version": "2.10.0",
                           "source_url": self.release["assets"][0]["browser_download_url"],
                           "source_sha256": "a" * 64, "signing_fingerprint": self.fingerprint,
                           "repository_url": self.url, "source_code": f"{PROJECT_URL}/tree/v2.10.0"}
        self.source = self.root / "source.json"
        self.source.write_text(json.dumps(self.provenance))

    def verify_output(self):
        verify(self.public, self.source, self.release, self.url, self.fingerprint, self.accepted)

    def test_accepts_only_matching_fingerprint_key_and_descriptors(self):
        self.assertEqual(self.key, read_accepted_key(self.accepted, self.fingerprint))
        self.verify_output()
        self.accepted.write_bytes(self.keys[1][1])
        with self.assertRaisesRegex(ValueError, "signing fingerprint"):
            self.verify_output()
        self.accepted.write_bytes(self.keys[0][1])
        (self.public / f"{APP_NAME.lower()}.asc").write_bytes(self.keys[1][1])
        with self.assertRaisesRegex(ValueError, "signing fingerprint"):
            self.verify_output()

    def test_rejects_extra_accepted_keys(self):
        self.accepted.write_bytes(self.keys[0][1] + self.keys[1][1])
        with self.assertRaisesRegex(ValueError, "signing fingerprint"):
            self.verify_output()

    def test_rejects_each_changed_provenance_field(self):
        for field in self.provenance:
            changed = copy.copy(self.provenance)
            changed[field] = "unaccepted"
            self.source.write_text(json.dumps(changed))
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "provenance"):
                self.verify_output()

    def test_rejects_unexpected_public_files_and_oversized_output(self):
        extra = self.public / "private.asc"
        extra.write_bytes(self.keys[0][1])
        with self.assertRaisesRegex(ValueError, "Unexpected files"):
            self.verify_output()
        extra.unlink()
        with (self.public / "repo" / "oversized").open("wb") as output:
            output.truncate(900 * 1024 * 1024 + 1)
        with self.assertRaisesRegex(ValueError, "size budget"):
            self.verify_output()

    def test_publish_rejects_signer_different_from_accepted_key_before_signing(self):
        repo = self.root / "repo"
        repo.mkdir()
        (repo / "config").touch()
        self.accepted.write_bytes(self.keys[1][1])
        with self.assertRaisesRegex(ValueError, "signing fingerprint"):
            publish(repo, self.signing, self.root / "publication", self.root / "assets", self.release,
                    self.url, self.fingerprint, self.accepted)


if __name__ == "__main__":
    unittest.main()
