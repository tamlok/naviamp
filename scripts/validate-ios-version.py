#!/usr/bin/env python3
"""Reject stale Xcode release versions and bundle identities before packaging."""
import re
import sys
from pathlib import Path


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


def validate(root):
    version = (root / "VERSION").read_text().strip().removeprefix("v")
    marketing = re.split(r"[-+]", version, maxsplit=1)[0]
    build = (root / "VERSION_CODE").read_text().strip()
    application_id = (root / "APPLICATION_ID").read_text().strip()
    if not re.fullmatch(r"[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+", application_id):
        raise ValueError("APPLICATION_ID must contain one reverse-domain bundle identifier")
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
        validate_bundle_ids(project, path, application_id, targets)


if __name__ == "__main__":
    try:
        validate(Path(__file__).resolve().parents[1])
    except (ValueError, OSError) as error:
        sys.exit(str(error))
    print("Xcode archive defaults match VERSION, VERSION_CODE and APPLICATION_ID")
