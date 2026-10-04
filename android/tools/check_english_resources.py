#!/usr/bin/env python3
"""Verify English-only UI resources and Kotlin resource references."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

android = Path(__file__).resolve().parents[1]
resource_root = android / "app/src/main/res"
path = resource_root / "values/strings.xml"
entries = ET.parse(path).getroot().findall("string")
keys = [entry.attrib["name"] for entry in entries]
assert len(keys) == len(set(keys)), "Duplicate English string keys"
assert all(entry.text for entry in entries), "Empty English strings"
assert not (resource_root / "values-te").exists(), "Telugu UI resources must not be shipped"
assert not (resource_root / "values-hi").exists(), "Hindi UI resources must not be shipped"
for source in (android / "app/src").rglob("*.kt"):
    used = set(re.findall(r"(?<!android\.)R\.string\.(\w+)", source.read_text()))
    assert not used - set(keys), f"Missing English resource in {source}: {used - set(keys)}"
main = (android / "app/src/main/java/org/resqmesh/app/MainActivity.kt").read_text()
assert "configuration.setLocale(Locale.ENGLISH)" in main, "App UI must use English"
assert "AppLanguage" not in main, "Language chooser scaffolding remains"
print(f"PASS: {len(keys)} English string resources; all Kotlin references resolve; English-only UI configuration.")
