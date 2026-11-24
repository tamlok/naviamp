import importlib.util
from pathlib import Path
import tempfile
import unittest
import plistlib
import re

SCRIPT = Path(__file__).resolve().parents[1] / "validate-ios-version.py"
SPEC = importlib.util.spec_from_file_location("ios_version", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def release_fixture(root):
    (root / "VERSION").write_text("v2.10.0-beta.1+test\n")
    (root / "VERSION_CODE").write_text("60\n")
    (root / "APPLICATION_ID").write_text((SCRIPT.parents[1] / "APPLICATION_ID").read_text())
    projects = []
    for relative in ("apps/ios/Naviamp.xcodeproj/project.pbxproj",
                     "apps/tvos/NaviampTV.xcodeproj/project.pbxproj"):
        project = root / relative
        project.parent.mkdir(parents=True)
        text = (SCRIPT.parents[1] / relative).read_text()
        for setting, value in (("MARKETING_VERSION", "2.10.0"), ("CURRENT_PROJECT_VERSION", "60")):
            text = re.sub(rf'(\b{setting}\s*=\s*)[^;]+;', rf'\g<1>"{value}";', text)
        project.write_text(text)
        projects.append(project)
    return projects


class IosReleaseVersionTest(unittest.TestCase):
    def test_app_local_defaults_declaration_is_an_actual_app_resource(self):
        root = SCRIPT.parents[1]
        manifest = root / "apps/ios/iosApp/PrivacyInfo.xcprivacy"
        data = plistlib.loads(manifest.read_bytes())
        self.assertEqual([{"NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryUserDefaults",
                           "NSPrivacyAccessedAPITypeReasons": ["CA92.1"]},
                          {"NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryFileTimestamp",
                           "NSPrivacyAccessedAPITypeReasons": ["C617.1"]}], data["NSPrivacyAccessedAPITypes"])
        project = (root / "apps/ios/Naviamp.xcodeproj/project.pbxproj").read_text()
        resource_id = re.search(r"(\w+) /\* PrivacyInfo.xcprivacy in Resources \*/ =", project).group(1)
        resources = re.search(r"A10000000000000000000032 /\* Resources \*/ = \{[^}]+", project).group(0)
        self.assertIn(resource_id, resources)

    def test_audited_sdk_imports_have_manifests_in_both_slices(self):
        root = SCRIPT.parents[1] / "platforms/ios/vendor/bass"
        expected = [
            {"NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategoryFileTimestamp",
             "NSPrivacyAccessedAPITypeReasons": ["C617.1"]},
            {"NSPrivacyAccessedAPIType": "NSPrivacyAccessedAPICategorySystemBootTime",
             "NSPrivacyAccessedAPITypeReasons": ["35F9.1"]},
        ]
        for sdk in ("bass", "bassmidi"):
            manifests = list((root / f"{sdk}.xcframework").glob(f"*/{sdk}.framework/PrivacyInfo.xcprivacy"))
            self.assertEqual(2, len(manifests))
            for manifest in manifests:
                self.assertEqual(expected, plistlib.loads(manifest.read_bytes())["NSPrivacyAccessedAPITypes"])

    def test_real_project_matches_canonical_release(self):
        MODULE.validate(SCRIPT.parents[1])

    def test_both_configurations_and_missing_metadata_are_checked(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            ios, _ = release_fixture(root)
            valid = ios.read_text()
            MODULE.validate(root)
            for setting, value, wrong in (("MARKETING_VERSION", "2.10.0", "2.0.0"),
                                          ("CURRENT_PROJECT_VERSION", "60", "44")):
                declaration = f'{setting} = "{value}";'
                for occurrence in re.finditer(re.escape(declaration), valid):
                    for replacement in (f'{setting} = "{wrong}";', ''):
                        with self.subTest(setting=setting, position=occurrence.start(), replacement=replacement):
                            ios.write_text(valid[:occurrence.start()] + replacement + valid[occurrence.end():])
                            with self.assertRaisesRegex(ValueError, setting):
                                MODULE.validate(root)
            ios.write_text("")
            with self.assertRaises(ValueError):
                MODULE.validate(root)

    def test_apple_tv_project_must_share_the_release_version(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _, tvos = release_fixture(root)
            valid = tvos.read_text()
            MODULE.validate(root)
            for setting, value, wrong in (("MARKETING_VERSION", "2.10.0", "2.0.0"),
                                          ("CURRENT_PROJECT_VERSION", "60", "59")):
                for occurrence in re.finditer(re.escape(f'{setting} = "{value}";'), valid):
                    with self.subTest(setting=setting, position=occurrence.start()):
                        tvos.write_text(valid[:occurrence.start()] + f'{setting} = "{wrong}";' + valid[occurrence.end():])
                        with self.assertRaisesRegex(ValueError, setting):
                            MODULE.validate(root)

    def test_every_app_and_test_bundle_id_rejects_drift_or_missing_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            ios, tvos = release_fixture(root)
            application_id = (root / "APPLICATION_ID").read_text().strip()
            for project, suffixes in ((ios, ("", ".tests", ".keychain-test-host")),
                                      (tvos, (".tvos", ".tvos.storage-test-host", ".tvos.tests", ".tvos.uitests"))):
                valid = project.read_text()
                for suffix in suffixes:
                    bundle = application_id + suffix
                    declarations = list(re.finditer(
                        rf'\bPRODUCT_BUNDLE_IDENTIFIER\s*=\s*"?{re.escape(bundle)}"?;', valid))
                    self.assertEqual(2, len(declarations), bundle)
                    for declaration in declarations:
                        for replacement in ('PRODUCT_BUNDLE_IDENTIFIER = "org.example.wrong";', ''):
                            with self.subTest(bundle=bundle, position=declaration.start(), replacement=replacement):
                                project.write_text(valid[:declaration.start()] + replacement + valid[declaration.end():])
                                with self.assertRaisesRegex(ValueError, "bundle IDs"):
                                    MODULE.validate(root)
                project.write_text(valid)

    def test_device_release_override_is_required_and_cannot_apply_to_simulator_or_debug(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            _, tvos = release_fixture(root)
            valid = tvos.read_text()
            application_id = (root / "APPLICATION_ID").read_text().strip()
            declaration = f'"PRODUCT_BUNDLE_IDENTIFIER[sdk=appletvos*]" = "{application_id}";'
            self.assertEqual(1, valid.count(declaration))
            for replacement in ('', declaration.replace(application_id, application_id + '.tvos'),
                                declaration.replace(application_id, 'app.naviamp.ios'),
                                declaration.replace('sdk=appletvos*', 'sdk=appletvsimulator*')):
                with self.subTest(override=replacement):
                    tvos.write_text(valid.replace(declaration, replacement))
                    with self.assertRaisesRegex(ValueError, "NaviampTV Release bundle IDs"):
                        MODULE.validate(root)
            without_override = valid.replace(declaration, '')
            default = f'PRODUCT_BUNDLE_IDENTIFIER = "{application_id}.tvos";'
            tvos.write_text(without_override.replace(default, default + ' ' + declaration, 1))
            with self.assertRaisesRegex(ValueError, "NaviampTV Debug bundle IDs"):
                MODULE.validate(root)

    def test_missing_bundle_configuration_metadata_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            ios, tvos = release_fixture(root)
            for project in (ios, tvos):
                valid = project.read_text()
                for pattern in (r'buildConfigurationList\s*=\s*[^;]+;',
                                r'buildConfigurations\s*=\s*\([^)]*\);'):
                    with self.subTest(project=project.name, metadata=pattern):
                        project.write_text(re.sub(pattern, '', valid))
                        with self.assertRaisesRegex(ValueError, "bundle metadata"):
                            MODULE.validate(root)
                project.write_text(valid)

    def test_application_id_metadata_is_required_and_authoritative(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release_fixture(root)
            metadata = root / "APPLICATION_ID"
            metadata.unlink()
            with self.assertRaises(OSError):
                MODULE.validate(root)
            for value in ('', 'not an ID', 'org.example.first\norg.example.second', 'org.example.other'):
                with self.subTest(application_id=value):
                    metadata.write_text(value)
                    with self.assertRaises(ValueError):
                        MODULE.validate(root)
