#!/usr/bin/env python3
"""Offline, byte-exact license asset gate; no Gradle, network, download or extraction.

Default: verify the committed source-tree bundle and frozen SHA-256 inventory,
         plus the four native-source archives pinned by proot/artifacts.json.
--apk PATH: additionally verify the same entries in an existing APK (ZIP), read-only.
--stage: maintainer-only explicit copy from repository originals and refresh inventory;
         review/commit the resulting diff. Never use --stage in a verification CI step.
Neither success nor --apk claims a resolved dependency graph or legal compatibility.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import sys
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
ASSETS = "app/src/main/assets/"
BUNDLE = ASSETS + "licenses/"
INVENTORY = BUNDLE + "bundle.sha256.json"
INDEX = "THIRD_PARTY/license-bundle/source-index.md"
PROOT_ARTIFACTS = "THIRD_PARTY/proot/artifacts.json"
NATIVE_SOURCES = tuple(ASSETS + "native-sources/" + name + ".tgz" for name in (
    "proot", "talloc", "shmem", "eta-native-build",
))
# Copies are complete repository originals, not reconstructed license templates.
COPIES = (
    ("LICENSE", "AGPL-3.0.txt"),
    ("THIRD_PARTY/rikkahub/NOTICE.md", "rikkahub/NOTICE.md"),
    ("THIRD_PARTY/rikkahub/model-icons/NOTICE.md", "rikkahub/model-icons/NOTICE.md"),
    ("THIRD_PARTY/rikkahub/model-icons/LICENSE-LobeHub", "rikkahub/model-icons/LICENSE-LobeHub"),
    ("docs/third-party/virtual-display-owner/LICENSE", "virtual-display-owner/LICENSE"),
    ("docs/third-party/virtual-display-owner/NOTICE.md", "virtual-display-owner/NOTICE.md"),
    ("THIRD_PARTY/linux-installer/NOTICE.md", "linux-installer/NOTICE.md"),
    ("THIRD_PARTY/task19/OpenMinis-NOTICE.md", "openminis/NOTICE.md"),
    ("docs/P32-voice-network-LICENSES.md", "P32-voice-network-LICENSES.md"),
    (ASSETS + "whale-maid/LICENSE", "whale-maid/LICENSE"),
    (ASSETS + "whale-maid/NOTICE", "whale-maid/NOTICE"),
    (INDEX, "source-index.md"),
) + tuple(("THIRD_PARTY/sherpa-ncnn/" + name, "sherpa-ncnn/" + name) for name in (
    "LICENSE", "LICENSE-ncnn", "LICENSE-kaldi-native-fbank", "KISSFFT-COPYING",
    "KISSFFT-LICENSE", "MODEL-CARD.md", "NOTICE.md",
))
COPIES += tuple(("THIRD_PARTY/license-bundle/maven/" + artifact + "/" + name,
                 "maven/" + artifact + "/" + name)
                for artifact in ("commons-compress-1.28.0", "commons-codec-1.19.0",
                                 "commons-io-2.20.0", "commons-lang3-3.18.0")
                for name in ("LICENSE.txt", "NOTICE.txt"))
EXISTING = (
    "GPL-3.0.txt", "NOTICE-proot.txt", "proot-5.1.107.92.txt",
    "talloc-2.4.3.txt", "libandroid-shmem-0.7.txt",
)
WHALE_FILES = tuple(ASSETS + "whale-maid/" + name + ".png" for name in (
    "pet_sprite", "cupboard_bg", "rice_bowl", "rice_grain", "rice_pot", "rice_spoon",
))
# Previously frozen repository evidence; no new upstream audit is inferred.
PINNED = {
    "LICENSE": "0d96a4ff68ad6d4b6f1f30f713b18d5184912ba8dd389f86aa7710db079abcb0",
    "THIRD_PARTY/rikkahub/LICENSE": "0d96a4ff68ad6d4b6f1f30f713b18d5184912ba8dd389f86aa7710db079abcb0",
    "THIRD_PARTY/task19/upstream-licenses/openminis-4ef2900-LICENSE":
        "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986",
    BUNDLE + "GPL-3.0.txt": "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986",
}
SCHEMA = 1
MAX_ENTRY = 32 * 1024 * 1024  # bounded reads, including the existing sprite sheet


class BundleError(ValueError):
    pass


def local(root, name):
    """Repository-relative regular files only; do not follow symlinks."""
    path = PurePosixPath(name)
    if not name or path.is_absolute() or ".." in path.parts or "\\" in name or str(path) != name:
        raise BundleError("unsafe repository path: " + name)
    current = root
    for part in path.parts:
        current = current / part
        if current.is_symlink():
            raise BundleError("symlink is not an input: " + name)
    return current


def read(root, name):
    path = local(root, name)
    if not path.is_file():
        raise BundleError("missing regular file: " + name)
    if path.stat().st_size > MAX_ENTRY:
        raise BundleError("oversized license/evidence file: " + name)
    data = path.read_bytes()
    if not data:
        raise BundleError("empty file: " + name)
    return data


def sha(data):
    return hashlib.sha256(data).hexdigest()


def record(root, name):
    data = read(root, name)
    return {"path": name, "bytes": len(data), "sha256": sha(data)}


def required_paths():
    return {BUNDLE + name for _, name in COPIES} | {BUNDLE + name for name in EXISTING}


def license_paths(root):
    directory = local(root, BUNDLE.rstrip("/"))
    if not directory.is_dir():
        raise BundleError("missing licenses directory")
    result = set()
    for path in directory.rglob("*"):
        if path.is_symlink():
            raise BundleError("symlink in license bundle")
        if path.is_file():
            name = path.relative_to(root).as_posix()
            if name != INVENTORY:
                result.add(name)
    return result


def verify_originals(root):
    for name, digest in PINNED.items():
        if sha(read(root, name)) != digest:
            raise BundleError("fixed repository evidence hash changed: " + name)
    # This shared full GPL text also covers OpenMinis; no redundant GPL template.
    if read(root, BUNDLE + "GPL-3.0.txt") != read(
        root, "THIRD_PARTY/task19/upstream-licenses/openminis-4ef2900-LICENSE"
    ):
        raise BundleError("OpenMinis GPL original differs from bundled shared GPL")


def verify_copies(root):
    verify_originals(root)
    for source, destination in COPIES:
        target = BUNDLE + destination
        if read(root, source) != read(root, target):
            raise BundleError("repository original / asset mismatch: " + target)


def stage(root):
    """Explicit maintenance operation; must not be called by verification tests/CI."""
    verify_originals(root)
    verify_native_sources(root)  # Existing proot pins are never regenerated by --stage.
    # Validate and read all inputs before publishing any copy.
    copies = [(BUNDLE + target, read(root, source)) for source, target in COPIES]
    for name in EXISTING:
        read(root, BUNDLE + name)
    for name in WHALE_FILES:
        read(root, name)
    for name, data in copies:
        target = local(root, name)
        target.parent.mkdir(parents=True, exist_ok=True)
        # Keep already correct Sherpa originals byte-for-byte (and do not rewrite them).
        if not target.exists() or target.read_bytes() != data:
            target.write_bytes(data)
    present = license_paths(root)
    if present != required_paths():
        raise BundleError("unindexed license file(s); review COPIES/EXISTING before staging")
    inventory = {
        "schema_version": SCHEMA,
        "scope": "Source-tree byte inventory, not a resolved graph, upstream image binding or APK verification result.",
        "licenses": [record(root, name) for name in sorted(present)],
        "local_image_evidence": [record(root, name) for name in WHALE_FILES],
        "image_upstream_binding": "unresolved: local hashes identify bundled bytes only; upstream revision/path/transform not established",
    }
    local(root, INVENTORY).write_text(json.dumps(inventory, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return verify_tree(root)


def checked_records(value, expected, label):
    if not isinstance(value, list):
        raise BundleError("invalid inventory list: " + label)
    result = {}
    for item in value:
        if not isinstance(item, dict) or set(item) != {"path", "bytes", "sha256"}:
            raise BundleError("invalid inventory record: " + label)
        name = item["path"]
        if not isinstance(name, str) or name not in expected or name in result:
            raise BundleError("unexpected/duplicate inventory path: " + str(name))
        if type(item["bytes"]) is not int or not 0 < item["bytes"] <= MAX_ENTRY:
            raise BundleError("invalid inventory byte length: " + name)
        if not isinstance(item["sha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", item["sha256"]):
            raise BundleError("invalid inventory SHA-256: " + name)
        result[name] = item
    if set(result) != expected:
        raise BundleError("inventory does not contain the exact required set: " + label)
    return result


def verify_native_sources(root):
    """Reuse the existing proot archive inventory, without repinning or extracting."""
    manifest = json.loads(read(root, PROOT_ARTIFACTS))
    if not isinstance(manifest, dict) or not isinstance(manifest.get("artifacts"), list):
        raise BundleError("invalid proot artifacts inventory")
    archives = []
    for item in manifest["artifacts"]:
        if not isinstance(item, dict) or not isinstance(item.get("path"), str):
            raise BundleError("invalid proot artifact record")
        if item["path"].startswith(ASSETS + "native-sources/"):
            archives.append({key: item.get(key) for key in ("path", "bytes", "sha256")})
    records = checked_records(archives, set(NATIVE_SOURCES), "proot native-sources")
    for name, item in records.items():
        if record(root, name) != item:
            raise BundleError("proot native-source SHA-256/length mismatch: " + name)
    return records


def verify_tree(root):
    verify_copies(root)
    native_sources = verify_native_sources(root)
    inventory = json.loads(read(root, INVENTORY))
    if (not isinstance(inventory, dict) or type(inventory.get("schema_version")) is not int
            or inventory.get("schema_version") != SCHEMA):
        raise BundleError("unsupported inventory schema")
    licenses = checked_records(inventory.get("licenses"), required_paths(), "licenses")
    images = checked_records(inventory.get("local_image_evidence"), set(WHALE_FILES), "images")
    if license_paths(root) != set(licenses):
        raise BundleError("unindexed or missing license asset")
    for name, item in {**licenses, **images}.items():
        if record(root, name) != item:
            raise BundleError("frozen SHA-256/length mismatch: " + name)
    # The inventory is itself required in the APK and compared to the checked source bytes.
    return {**licenses, **images, **native_sources, INVENTORY: record(root, INVENTORY)}


def read_apk_entry(archive, info):
    try:
        return archive.read(info)
    except zlib.error:
        raise BundleError("APK asset decompression failed") from None


def verify_apk(root, apk, records):
    """Read exact ZIP entries, reject duplicates; never extract or build an APK."""
    with zipfile.ZipFile(apk) as archive:
        counts = Counter(info.filename for info in archive.infolist())
        expected_licenses = {"assets/" + name[len(ASSETS):] for name in records if name.startswith(BUNDLE)}
        actual_licenses = {name for name in counts if name.startswith("assets/licenses/") and not name.endswith("/")}
        if actual_licenses != expected_licenses:
            raise BundleError("APK license entry set differs from checked inventory")
        for name, item in records.items():
            apk_name = "assets/" + name[len(ASSETS):]
            if counts[apk_name] != 1:
                raise BundleError("APK missing/duplicate asset entry: " + apk_name)
            info = archive.getinfo(apk_name)
            if info.file_size != item["bytes"] or info.file_size > MAX_ENTRY:
                raise BundleError("APK entry size mismatch: " + apk_name)
            if sha(read_apk_entry(archive, info)) != item["sha256"]:
                raise BundleError("APK entry SHA-256 mismatch: " + apk_name)
        # Required Notice / attribution originals still at their normal resource paths too.
        for suffix in ("LICENSE", "NOTICE"):
            name = "whale-maid/" + suffix
            apk_name = "assets/" + name
            if counts[apk_name] != 1:
                raise BundleError("APK missing/duplicate original image notice: " + apk_name)
            info = archive.getinfo(apk_name)
            data = read(root, ASSETS + name)
            if info.file_size != len(data) or sha(read_apk_entry(archive, info)) != sha(data):
                raise BundleError("APK original image notice mismatch: " + apk_name)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", action="store_true", help="explicitly copy repository originals and freeze local hashes; review/commit diff")
    parser.add_argument("--apk", type=Path, help="also check an existing APK ZIP, read-only; no APK is built")
    args = parser.parse_args(argv)
    if args.stage and args.apk:
        parser.error("--stage and --apk are separate maintenance/verification operations")
    try:
        records = stage(ROOT) if args.stage else verify_tree(ROOT)
        if args.apk is not None:
            verify_apk(ROOT, args.apk, records)
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, RuntimeError) as error:
        print("license bundle FAILED: " + str(error), file=sys.stderr)
        return 1
    if args.stage:
        print("Staged repository originals and frozen local hashes; review and commit the diff. No APK checked.")
    elif args.apk is not None:
        print("Source-tree and supplied APK license/image/native-source bytes match. Resolved dependency graph and legal review NOT checked.")
    else:
        print("Source-tree license/image/native-source bytes match. APK, resolved dependency graph and legal review NOT checked.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
