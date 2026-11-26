#!/usr/bin/env python3
"""Reject stale Xcode release versions, product names and bundle identities before packaging."""
import plistlib
import re
import sys
from pathlib import Path
import xml.etree.ElementTree as ET


def parse_project(project):
    """Read OpenStep dictionaries, arrays and scalar metadata without a macOS dependency."""
    tokens = [token for token in re.findall(
        r'/\*.*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"|[{}()=;,]|[^\s{}()=;,"]+|[^\s]', project, re.S
    ) if not token.startswith(('/*', '//'))]
    position = 0

    def take(expected=None):
        nonlocal position
        token = tokens[position]
        position += 1
        if expected is not None and token != expected:
            raise ValueError(f"Expected {expected!r} in Xcode metadata, got {token!r}")
        return token

    def value():
        token = take()
        if token == '{':
            result = {}
            while tokens[position] != '}':
                key = take().strip('"')
                take('=')
                if key in result:
                    raise ValueError(f"Duplicate Xcode metadata key: {key}")
                result[key] = value()
                take(';')
            take('}')
            return result
        if token == '(':
            result = []
            while tokens[position] != ')':
                result.append(value())
                if tokens[position] != ')':
                    take(',')
            take(')')
            return result
        if token in ('}', ')', '=', ';', ','):
            raise ValueError(f"Unexpected Xcode metadata token: {token}")
        return token.strip('"')

    try:
        result = value()
    except IndexError as error:
        raise ValueError("Incomplete Xcode project metadata") from error
    if position != len(tokens):
        raise ValueError("Trailing Xcode project metadata")
    return result


def validate_bundle_ids(project, path, application_id, targets):
    identity = 'PRODUCT_BUNDLE_IDENTIFIER'
    device_override = identity + '[sdk=appletvos*]'
    try:
        metadata = parse_project(project)
        objects = metadata['objects']
        native_targets = [objects[reference] for reference in objects[metadata['rootObject']]['targets']]
        names = [target['name'] for target in native_targets]
        if sorted(names) != sorted(targets):
            raise ValueError(f"Expected bundle targets {sorted(targets)}, got {names}")
        checked = set()
        for target in native_targets:
            name = target['name']
            configurations = objects[target['buildConfigurationList']]['buildConfigurations']
            configuration_names = []
            for reference in configurations:
                configuration = objects[reference]
                configuration_name = configuration['name']
                configuration_names.append(configuration_name)
                settings = configuration['buildSettings']
                actual = {key: value for key, value in settings.items() if key.startswith(identity)}
                expected = {identity: application_id + targets[name]}
                if name == 'NaviampTV' and configuration_name == 'Release':
                    expected[device_override] = application_id
                if actual != expected:
                    raise ValueError(f"{name} {configuration_name} bundle IDs must equal {expected}; got {actual}")
                checked.add(reference)
            if sorted(configuration_names) != ['Debug', 'Release']:
                raise ValueError(f"{name} must have Debug/Release configurations; got {configuration_names}")
        for reference, item in objects.items():
            if item.get('isa') == 'XCBuildConfiguration' and reference not in checked:
                if any(key.startswith(identity) for key in item['buildSettings']):
                    raise ValueError("Bundle ID outside the expected app/test configurations")
    except (KeyError, TypeError, AttributeError) as error:
        raise ValueError(f"{path.parent.name} missing or malformed bundle metadata") from error
    except ValueError as error:
        raise ValueError(f"{path.parent.name}: {error}") from error
    return metadata


def validate_product_names(path, metadata, application_name):
    app_target = path.parent.stem
    plist_relative = {'Naviamp': 'iosApp/Info.plist', 'NaviampTV': 'NaviampTV/Info.plist'}[app_target]
    try:
        objects = metadata['objects']
        targets = {reference: objects[reference]
                   for reference in objects[metadata['rootObject']]['targets']}
        products = {}
        for reference, target in targets.items():
            name = target['name']
            is_app = name == app_target
            product_name = application_name if is_app else name
            extension = '.app' if target['productType'] == 'com.apple.product-type.application' else '.xctest'
            product = objects[target['productReference']]
            if target['productName'] != product_name or product['path'] != product_name + extension:
                raise ValueError(f"{name} product reference must name {product_name + extension}")
            products[reference] = product['path']
            for configuration in objects[target['buildConfigurationList']]['buildConfigurations']:
                settings = objects[configuration]['buildSettings']
                actual = {key: value for key, value in settings.items() if key.startswith('PRODUCT_NAME')}
                expected_name = product_name
                if not is_app and settings.get('PRODUCT_NAME') == '$(TARGET_NAME)':
                    expected_name = '$(TARGET_NAME)'
                if actual != {'PRODUCT_NAME': expected_name}:
                    raise ValueError(f"{name} PRODUCT_NAME must equal {product_name}; got {actual}")
                if is_app:
                    for setting, expected in (('PRODUCT_MODULE_NAME', app_target), ('INFOPLIST_FILE', plist_relative)):
                        actual = {key: value for key, value in settings.items() if key.startswith(setting)}
                        if actual != {setting: expected}:
                            raise ValueError(f"{name} {setting} must equal {expected}; got {actual}")

        plist_path = path.parent.parent / plist_relative
        plist = plistlib.loads(plist_path.read_bytes())
        for key, expected in (
            ('CFBundleDisplayName', application_name), ('CFBundleName', '$(PRODUCT_NAME)'),
            ('CFBundleExecutable', '$(EXECUTABLE_NAME)'), ('CFBundleIdentifier', '$(PRODUCT_BUNDLE_IDENTIFIER)'),
            ('CFBundleShortVersionString', '$(MARKETING_VERSION)'), ('CFBundleVersion', '$(CURRENT_PROJECT_VERSION)'),
        ):
            if plist.get(key) != expected:
                raise ValueError(f"{plist_relative} {key} must equal {expected}")

        schemes = [app_target]
        if app_target == 'NaviampTV':
            schemes.append('NaviampTVSceneTests')
        for name in schemes:
            scheme = ET.parse(path.parent / 'xcshareddata/xcschemes' / (name + '.xcscheme'))
            app_referenced = False
            for buildable in scheme.iter('BuildableReference'):
                reference = buildable.get('BlueprintIdentifier')
                if reference not in targets:
                    raise ValueError(f"{name} scheme references an unknown target {reference}")
                target_name = targets[reference]['name']
                expected = {'BlueprintName': target_name, 'BuildableName': products[reference],
                            'ReferencedContainer': 'container:' + path.parent.name}
                if any(buildable.get(key) != value for key, value in expected.items()):
                    raise ValueError(f"{name} scheme product reference must match {expected}")
                app_referenced |= target_name == app_target
            if not app_referenced:
                raise ValueError(f"{name} scheme must reference {app_target}")
    except (KeyError, TypeError, AttributeError, plistlib.InvalidFileException, ET.ParseError) as error:
        raise ValueError(f"{path.parent.name} missing or malformed product metadata") from error
    except ValueError as error:
        raise ValueError(f"{path.parent.name}: {error}") from error


def validate(root):
    version = (root / "VERSION").read_text().strip().removeprefix("v")
    marketing = re.split(r"[-+]", version, maxsplit=1)[0]
    build = (root / "VERSION_CODE").read_text().strip()
    application_id = (root / "APPLICATION_ID").read_text().strip()
    if not re.fullmatch(r"[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+", application_id):
        raise ValueError("APPLICATION_ID must contain one reverse-domain bundle identifier")
    application_name = (root / "APPLICATION_NAME").read_text().strip()
    if not application_name or len(application_name.splitlines()) != 1:
        raise ValueError("APPLICATION_NAME must contain one product name")
    # Device Release tvOS retains the shared iOS identity, independently of store registration.
    projects = {
        "apps/ios/Naviamp.xcodeproj/project.pbxproj": {
            "Naviamp": "", "NaviampTests": ".tests", "NaviampKeychainTestHost": ".keychain-test-host",
        },
        "apps/tvos/NaviampTV.xcodeproj/project.pbxproj": {
            "NaviampTV": ".tvos", "NaviampTVStorageTestHost": ".tvos.storage-test-host",
            "NaviampTVTests": ".tvos.tests", "NaviampTVUITests": ".tvos.uitests",
        },
    }
    for relative_path, targets in projects.items():
        path = root / relative_path
        project = path.read_text()
        for setting, expected in (("MARKETING_VERSION", marketing), ("CURRENT_PROJECT_VERSION", build)):
            values = re.findall(rf"\b{setting}\s*=\s*([^;]+);", project)
            if len(values) != 2 or any(value.strip().strip('"') != expected for value in values):
                raise ValueError(f"{path.parent.name} Debug/Release {setting} must both equal {expected}; got {values}")
        metadata = validate_bundle_ids(project, path, application_id, targets)
        validate_product_names(path, metadata, application_name)


if __name__ == "__main__":
    try:
        validate(Path(__file__).resolve().parents[1])
    except (ValueError, OSError) as error:
        sys.exit(str(error))
    print("Xcode archive defaults match VERSION, VERSION_CODE, APPLICATION_ID and APPLICATION_NAME")
