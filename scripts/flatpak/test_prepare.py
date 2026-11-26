import copy
import configparser
import importlib.util
import json
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

spec = importlib.util.spec_from_file_location("prepare", Path(__file__).with_name("prepare.py"))
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class ReleasePackagingTest(unittest.TestCase):
    def setUp(self):
        self.release = {"tag_name": "v2.10.0", "draft": False, "prerelease": False, "immutable": True,
                        "html_url": f"{prepare.PROJECT_URL}/releases/tag/v2.10.0",
                        "published_at": "2026-11-03T12:00:00Z", "assets": [{
                            "name": f"{prepare.APP_NAME}-v2.10.0-linux-x64-release.zip",
                            "browser_download_url": f"{prepare.PROJECT_URL}/releases/download/v2.10.0/{prepare.APP_NAME}-v2.10.0-linux-x64-release.zip",
                            "digest": "sha256:" + "a" * 64}]}

    def assert_rejected(self, release):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "output"
            with self.assertRaises(ValueError):
                prepare.prepare(release, output)
            self.assertFalse(output.exists())

    def test_rejects_unpublished_and_unpinned_inputs(self):
        for key, value in (("draft", True), ("draft", None), ("prerelease", True), ("prerelease", None),
                           ("immutable", False), ("immutable", None),
                           ("published_at", ""), ("tag_name", "v2.10.0/../main")):
            release = copy.deepcopy(self.release)
            release[key] = value
            with self.subTest(key=key, value=value):
                self.assert_rejected(release)
        for key, value in (("digest", ""), ("digest", "sha256:" + "A" * 64),
                           ("digest", "sha256:" + "a" * 63),
                           ("name", "Naviamp-v2.10.0-linux-x64-release.zip"),
                           ("browser_download_url", "https://example.com/application.zip")):
            release = copy.deepcopy(self.release)
            release["assets"][0][key] = value
            with self.subTest(key=key, value=value):
                self.assert_rejected(release)

    def test_rejects_upstream_or_foreign_release_even_with_fork_asset(self):
        for repository in ("goosepod/naviamp", "other/naviamp"):
            for field in ("html_url", "browser_download_url"):
                release = copy.deepcopy(self.release)
                if field == "html_url":
                    release[field] = f"https://github.com/{repository}/releases/tag/v2.10.0"
                else:
                    release["assets"][0][field] = (
                        f"https://github.com/{repository}/releases/download/v2.10.0/"
                        f"{prepare.APP_NAME}-v2.10.0-linux-x64-release.zip")
                with self.subTest(repository=repository, field=field):
                    self.assert_rejected(release)

    def test_refuses_ambiguous_release_assets(self):
        self.release["assets"] *= 2
        self.assert_rejected(self.release)

    def test_package_is_pinned_and_preserves_narrow_persistent_permissions(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp)
            prepare.prepare(self.release, output)
            manifest = json.loads((output / f"{prepare.APP_ID}.json").read_text())
            self.assertEqual("a" * 64, manifest["modules"][-1]["sources"][0]["sha256"])
            self.assertEqual(prepare.APP_ID, manifest["app-id"])
            self.assertEqual("stable", manifest["branch"])
            self.assertEqual("naviamp", manifest["command"])
            commands = manifest["modules"][-1]["build-commands"]
            self.assertIn(f"cp -a {prepare.APP_NAME}/. /app/lib/naviamp/", commands)
            self.assertIn(f"chmod +x /app/lib/naviamp/bin/{prepare.APP_NAME} "
                          "/app/lib/naviamp/lib/runtime/lib/jspawnhelper", commands)
            self.assertIn("install -Dm755 naviamp /app/bin/naviamp", commands)
            self.assertNotIn("/bin/Naviamp", "\n".join(commands))
            launcher = (output / "naviamp").read_text()
            self.assertIn(f'exec /app/lib/naviamp/bin/{prepare.APP_NAME} "$@"', launcher)
            self.assertNotIn("/bin/Naviamp", launcher)
            self.assertEqual(0, manifest["modules"][-1]["sources"][0]["strip-components"])
            self.assertEqual({"--share=network", "--share=ipc", "--socket=x11", "--socket=pulseaudio",
                              "--device=dri", "--talk-name=org.freedesktop.secrets",
                              f"--own-name=org.mpris.MediaPlayer2.{prepare.APP_ID}",
                              "--filesystem=xdg-download", f"--persist=.local/share/{prepare.APP_ID}",
                              "--env=GTK_USE_PORTAL=1"}, set(manifest["finish-args"]))
            source = json.loads((output / "source.json").read_text())
            self.assertEqual("2.10.0", source["application_version"])
            self.assertEqual(f"{prepare.PROJECT_URL}/tree/v2.10.0", source["source_code"])
            self.assertEqual(self.release["assets"][0]["browser_download_url"], source["source_url"])
            self.assertEqual(source["source_url"], manifest["modules"][-1]["sources"][0]["url"])
            self.assertEqual(source["sha256"], manifest["modules"][-1]["sources"][0]["sha256"])
            desktop = configparser.ConfigParser(interpolation=None)
            desktop.read(output / f"{prepare.APP_ID}.desktop")
            self.assertEqual(prepare.APP_ID, desktop["Desktop Entry"]["Icon"])
            self.assertEqual(prepare.APP_NAME, desktop["Desktop Entry"]["Name"])
            self.assertEqual("naviamp", desktop["Desktop Entry"]["Exec"])
            self.assertEqual("app-naviamp-desktop-MainKt", desktop["Desktop Entry"]["StartupWMClass"])
            self.assertTrue((output / f"{prepare.APP_ID}.png").is_file())
            metadata = ET.parse(output / f"{prepare.APP_ID}.metainfo.xml").getroot()
            self.assertEqual(prepare.APP_ID, metadata.findtext("id"))
            self.assertEqual(prepare.APP_NAME, metadata.findtext("name"))
            self.assertEqual("naviamp", metadata.findtext("provides/binary"))
            self.assertEqual(f"{prepare.APP_ID}.desktop", metadata.findtext("launchable"))
            self.assertEqual("cn.com.letalk", metadata.find("developer").get("id"))
            self.assertEqual(prepare.PROJECT_URL, metadata.find("url[@type='homepage']").text)
            self.assertEqual(prepare.PROJECT_URL, metadata.find("url[@type='vcs-browser']").text)
            self.assertEqual(f"{prepare.PROJECT_URL}/issues", metadata.find("url[@type='bugtracker']").text)
            self.assertEqual("2.10.0", metadata.find("releases/release").get("version"))


if __name__ == "__main__":
    unittest.main()
