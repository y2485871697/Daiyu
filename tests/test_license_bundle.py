"""Byte/entry contracts using local synthetic ZIPs, never builds/downloads a real APK."""
from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("license_bundle", ROOT / "scripts/verify_license_bundle.py")
BUNDLE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BUNDLE)


class LicenseBundleContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        # Synthetic originals for the negative tests, not actual license replacements.
        self.pins = patch.dict(BUNDLE.PINNED, {}, clear=True)
        self.pins.start()
        self.addCleanup(self.pins.stop)
        for source, _ in BUNDLE.COPIES:
            self.write(source, ("fixture repository original: " + source + "\n").encode())
        for name in BUNDLE.EXISTING:
            self.write(BUNDLE.BUNDLE + name, ("existing fixture: " + name + "\n").encode())
        self.write("THIRD_PARTY/task19/upstream-licenses/openminis-4ef2900-LICENSE",
                   self.read(BUNDLE.BUNDLE + "GPL-3.0.txt"))
        for name in BUNDLE.WHALE_FILES:
            self.write(name, b"\x89PNG\r\n\x1a\nfixture:" + name.encode())
        native_records = []
        for name in BUNDLE.NATIVE_SOURCES:
            self.write(name, b"synthetic archive fixture: " + name.encode())
            native_records.append({**BUNDLE.record(self.root, name), "source": "fixture-only"})
        self.write(BUNDLE.PROOT_ARTIFACTS, json.dumps({"artifacts": native_records}).encode())
        self.records = BUNDLE.stage(self.root)

    def write(self, path, data):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(data)

    def read(self, path):
        return (self.root / path).read_bytes()

    def assert_bad_tree(self, message):
        with self.assertRaisesRegex(BUNDLE.BundleError, message):
            BUNDLE.verify_tree(self.root)

    def change_inventory(self, operation):
        inventory = json.loads(self.read(BUNDLE.INVENTORY))
        operation(inventory)
        self.write(BUNDLE.INVENTORY, (json.dumps(inventory) + "\n").encode())

    def make_apk(self, omit=None, corrupt=None, duplicate=None, extra=None, truncate=None):
        file = self.root / "fixture.apk"
        with zipfile.ZipFile(file, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name in self.records:
                entry = "assets/" + name[len(BUNDLE.ASSETS):]
                if entry == omit:
                    continue
                data = self.read(name)
                if entry == corrupt:
                    data = b"!" + data[1:]  # same length, different hash
                if entry == truncate:
                    data = data[:-1]
                archive.writestr(entry, data)
                if entry == duplicate:
                    with warnings.catch_warnings():
                        warnings.simplefilter("ignore", UserWarning)
                        archive.writestr(entry, data)
            for suffix in ("LICENSE", "NOTICE"):
                archive.writestr("assets/whale-maid/" + suffix,
                                 self.read(BUNDLE.ASSETS + "whale-maid/" + suffix))
            if extra:
                archive.writestr(extra, b"unexpected")
        return file

    def test_stage_copies_complete_originals_and_freezes_exact_paths(self):
        for source, destination in BUNDLE.COPIES:
            self.assertEqual(self.read(source), self.read(BUNDLE.BUNDLE + destination))
        inventory = json.loads(self.read(BUNDLE.INVENTORY))
        self.assertEqual(BUNDLE.required_paths(), {item["path"] for item in inventory["licenses"]})
        self.assertEqual(set(BUNDLE.WHALE_FILES), {item["path"] for item in inventory["local_image_evidence"]})
        self.assertIn("unresolved", inventory["image_upstream_binding"])
        self.assertEqual(self.records, BUNDLE.verify_tree(self.root))

    def test_read_only_verification_does_not_refresh_hashes_or_assets(self):
        before = {name: self.read(name) for name in self.records}
        with patch.object(BUNDLE, "ROOT", self.root), patch.object(BUNDLE, "stage", side_effect=AssertionError("no auto-fix")):
            output = io.StringIO()
            with redirect_stdout(output):
                self.assertEqual(0, BUNDLE.main([]))
            self.assertIn("APK, resolved dependency graph and legal review NOT checked", output.getvalue())
        self.assertEqual(before, {name: self.read(name) for name in self.records})

    def test_missing_and_empty_required_asset_fail(self):
        path = BUNDLE.BUNDLE + "rikkahub/NOTICE.md"
        (self.root / path).unlink()
        self.assert_bad_tree("missing regular file")
        self.write(path, b"")
        self.assert_bad_tree("empty file")

    def test_source_and_copy_must_match_but_matching_edits_still_need_frozen_hash(self):
        source = "THIRD_PARTY/rikkahub/NOTICE.md"
        destination = BUNDLE.BUNDLE + "rikkahub/NOTICE.md"
        self.write(destination, b"truncated original")
        self.assert_bad_tree("original / asset mismatch")
        self.write(source, b"truncated original")
        self.assert_bad_tree("frozen SHA-256/length mismatch")

    def test_fixed_original_pin_cannot_be_overridden_by_stage(self):
        with patch.dict(BUNDLE.PINNED, {"LICENSE": "0" * 64}):
            with self.assertRaisesRegex(BUNDLE.BundleError, "fixed repository evidence hash changed"):
                BUNDLE.stage(self.root)

    def test_shared_openminis_gpl_is_checked_against_original(self):
        self.write("THIRD_PARTY/task19/upstream-licenses/openminis-4ef2900-LICENSE", b"not GPL")
        self.assert_bad_tree("OpenMinis GPL original differs")

    def test_unindexed_license_rejected_not_silently_accepted(self):
        self.write(BUNDLE.BUNDLE + "unknown-license.txt", b"not indexed")
        self.assert_bad_tree("unindexed or missing")

    def test_missing_inventory_is_not_implicitly_generated(self):
        (self.root / BUNDLE.INVENTORY).unlink()
        self.assert_bad_tree("missing regular file")
        with patch.object(BUNDLE, "ROOT", self.root), patch.object(BUNDLE, "stage", side_effect=AssertionError("no auto-fix")):
            with redirect_stderr(io.StringIO()):
                self.assertEqual(1, BUNDLE.main([]))
        self.assertFalse((self.root / BUNDLE.INVENTORY).exists())

    def test_duplicate_missing_or_unsafe_inventory_records_fail(self):
        original = self.read(BUNDLE.INVENTORY)
        operations = (
            lambda value: value["licenses"].append(value["licenses"][0]),
            lambda value: value["licenses"].pop(),
            lambda value: value["licenses"][0].update(path="../LICENSE"),
            lambda value: value["licenses"][0].update(sha256="not a hash"),
            lambda value: value["licenses"][0].update(bytes=True),
        )
        for operation in operations:
            self.write(BUNDLE.INVENTORY, original)
            self.change_inventory(operation)
            self.assert_bad_tree("inventory")

    def test_local_image_hash_change_is_not_misrepresented_as_upstream_binding(self):
        self.write(BUNDLE.WHALE_FILES[0], b"changed image bytes")
        self.assert_bad_tree("frozen SHA-256/length mismatch")

    def test_safe_paths_disallow_absolute_traversal_and_symlinks(self):
        for name in ("/tmp/LICENSE", "../LICENSE", "a/../LICENSE", "a\\LICENSE", "./LICENSE"):
            with self.assertRaisesRegex(BUNDLE.BundleError, "unsafe"):
                BUNDLE.local(self.root, name)
        link = self.root / "symlink"
        try:
            link.symlink_to(self.root / "LICENSE")
        except (OSError, NotImplementedError):
            return  # Some Windows hosts cannot create symlinks; path cases still checked.
        with self.assertRaisesRegex(BUNDLE.BundleError, "symlink"):
            BUNDLE.read(self.root, "symlink")

    def test_apk_exact_entries_match_no_extraction(self):
        BUNDLE.verify_apk(self.root, self.make_apk(), self.records)
        self.assertFalse((self.root / "assets").exists())

    def test_apk_missing_duplicate_corrupted_or_unindexed_license_fails(self):
        target = "assets/licenses/AGPL-3.0.txt"
        for arguments in ({"omit": target}, {"duplicate": target}, {"corrupt": target},
                          {"extra": "assets/licenses/not-in-inventory"}):
            with self.assertRaises(BUNDLE.BundleError):
                BUNDLE.verify_apk(self.root, self.make_apk(**arguments), self.records)

    def test_apk_images_are_byte_checked_not_upstream_certified(self):
        for arguments in ({"omit": "assets/whale-maid/pet_sprite.png"},
                          {"corrupt": "assets/whale-maid/pet_sprite.png"}):
            with self.assertRaises(BUNDLE.BundleError):
                BUNDLE.verify_apk(self.root, self.make_apk(**arguments), self.records)

    def test_native_source_tree_missing_empty_size_and_hash_changes_fail_without_repins(self):
        manifest = self.read(BUNDLE.PROOT_ARTIFACTS)
        for name in BUNDLE.NATIVE_SOURCES:
            original = self.read(name)
            for data in (None, b"", original[:-1], b"!" + original[1:]):
                with self.subTest(name=name, data=data):
                    if data is None:
                        (self.root / name).unlink()
                    else:
                        self.write(name, data)
                    self.assert_bad_tree("missing regular file|empty file|native-source SHA-256/length mismatch")
                    with self.assertRaises(BUNDLE.BundleError):
                        BUNDLE.stage(self.root)
                    self.assertEqual(manifest, self.read(BUNDLE.PROOT_ARTIFACTS))
                    self.write(name, original)

    def test_native_source_manifest_exact_set_and_record_validation(self):
        original = self.read(BUNDLE.PROOT_ARTIFACTS)
        for operation in (
            lambda rows: rows.pop(),
            lambda rows: rows.append(rows[0]),
            lambda rows: rows[0].update(path=BUNDLE.ASSETS + "native-sources/unknown.tgz"),
            lambda rows: rows[0].update(bytes=True),
            lambda rows: rows[0].update(sha256="bad"),
        ):
            manifest = json.loads(original)
            operation(manifest["artifacts"])
            self.write(BUNDLE.PROOT_ARTIFACTS, json.dumps(manifest).encode())
            self.assert_bad_tree("inventory")
        self.write(BUNDLE.PROOT_ARTIFACTS, original)

    def test_apk_each_native_source_requires_unique_exact_size_and_hash(self):
        for name in BUNDLE.NATIVE_SOURCES:
            entry = "assets/" + name[len(BUNDLE.ASSETS):]
            for mutation, message in (("omit", "missing/duplicate"), ("duplicate", "missing/duplicate"),
                                      ("truncate", "size mismatch"), ("corrupt", "SHA-256 mismatch")):
                with self.subTest(name=name, mutation=mutation):
                    with self.assertRaisesRegex(BUNDLE.BundleError, message):
                        BUNDLE.verify_apk(self.root, self.make_apk(**{mutation: entry}), self.records)

    def test_cli_apk_rejects_missing_native_source_without_staging(self):
        apk = self.make_apk(omit="assets/native-sources/proot.tgz")
        with patch.object(BUNDLE, "ROOT", self.root), patch.object(BUNDLE, "stage", side_effect=AssertionError("no auto-fix")):
            with redirect_stderr(io.StringIO()):
                self.assertEqual(1, BUNDLE.main(["--apk", str(apk)]))

    def test_apk_cannot_be_used_with_maintenance_mode(self):
        with self.assertRaises(SystemExit), redirect_stderr(io.StringIO()):
            BUNDLE.main(["--stage", "--apk", "never-opened.apk"])


class RepositoryLicenseInventoryTest(unittest.TestCase):
    def test_committed_assets_match_complete_originals_and_frozen_sha256(self):
        # Fails until maintenance copies and inventory are committed; never self-repairs.
        BUNDLE.verify_tree(ROOT)

    def test_necessary_originals_and_notices_are_in_the_exact_required_set(self):
        required = BUNDLE.required_paths()
        for name in (
            "AGPL-3.0.txt", "rikkahub/NOTICE.md", "virtual-display-owner/LICENSE",
            "virtual-display-owner/NOTICE.md", "linux-installer/NOTICE.md",
            "GPL-3.0.txt", "openminis/NOTICE.md", "P32-voice-network-LICENSES.md",
            "sherpa-ncnn/KISSFFT-COPYING", "sherpa-ncnn/KISSFFT-LICENSE", "source-index.md",
        ):
            self.assertIn(BUNDLE.BUNDLE + name, required)
        polyform = (ROOT / "docs/third-party/virtual-display-owner/LICENSE").read_text(encoding="utf-8")
        self.assertIn("Required Notice: Copyright © 2026 蛮吉 (Mangi-11).", polyform)
        self.assertIn("## Noncommercial Purposes", polyform)

    def test_ci_gates_the_uploaded_main_apk_read_only_before_sums_and_upload(self):
        workflow = (ROOT / ".github/workflows/build.yml").read_text(encoding="utf-8")
        build = workflow.split("  build:\n", 1)[1].split("  smoke:\n", 1)[0]
        gate = 'python3 scripts/verify_license_bundle.py --apk "delivery/代鱼-0.1.0-${short}.apk"'
        self.assertEqual(build.count(gate), 1)
        self.assertLess(build.index('cp app/build/outputs/apk/debug/app-debug.apk'), build.index(gate))
        self.assertLess(build.index(gate), build.index('sha256sum *.apk tests/*.apk'))
        self.assertLess(build.index(gate), build.index('- name: 上传开发包'))
        self.assertNotIn("--stage", workflow)
        self.assertNotIn("continue-on-error", build)
        records = BUNDLE.verify_tree(ROOT)
        self.assertTrue(set(BUNDLE.NATIVE_SOURCES) <= set(records))

    def test_compiled_openminis_subset_is_exactly_documented(self):
        gradle = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        readme = (ROOT / "components/openminis-browser/README.md").read_text(encoding="utf-8")
        for name in ("BrowserAction.kt", "BrowserActionInput.kt", "BrowserActionResult.kt", "BrowserUseJS.kt",
                     "InterceptedDialogQueue.kt", "BrowserUseManager.kt"):
            self.assertIn('"' + name + '"', gradle)
            self.assertIn('`' + name + '`', readme)
        self.assertIn("does compile", readme)
        self.assertNotIn("not yet wired into the app build", readme)


if __name__ == "__main__":
    unittest.main()
