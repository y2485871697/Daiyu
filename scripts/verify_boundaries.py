from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DOMAIN = ROOT / "core-domain/src"
for path in DOMAIN.rglob("*.kt"):
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("import "):
            assert not line.startswith(("import android.", "import androidx.", "import app.daiyu.android.MainActivity")), path

# A byte-identical legal provenance copy may name the original host without
# making it a runtime dependency. Keep this exception to that one reviewed file.
LEGAL_PROVENANCE = ROOT / "app/src/main/assets/licenses/virtual-display-owner/NOTICE.md"
if LEGAL_PROVENANCE.exists():
    assert LEGAL_PROVENANCE.read_bytes() == (ROOT / "docs/third-party/virtual-display-owner/NOTICE.md").read_bytes()
for folder in ("app/src", "core-domain/src"):
    for path in (ROOT / folder).rglob("*"):
        if path.is_file() and path != LEGAL_PROVENANCE:
            assert b"io.github.mangi.eta" not in path.read_bytes(), path
manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
ANDROID = "{http://schemas.android.com/apk/res/android}"
expected_permissions = {
    "INTERNET": None, "VIBRATE": None, "RECORD_AUDIO": None,
    "SYSTEM_ALERT_WINDOW": None,
    "QUERY_ALL_PACKAGES": None, "PACKAGE_USAGE_STATS": None, "POST_NOTIFICATIONS": None,
    "MODIFY_AUDIO_SETTINGS": None, "CHANGE_WIFI_STATE": "28",
    "ACCESS_NETWORK_STATE": None, "ACCESS_WIFI_STATE": None,
    "ACCESS_COARSE_LOCATION": None, "ACCESS_FINE_LOCATION": None,
    "BLUETOOTH": "30", "BLUETOOTH_CONNECT": None,
    "READ_CONTACTS": None, "READ_CALENDAR": None, "READ_EXTERNAL_STORAGE": "32",
    "READ_MEDIA_IMAGES": None, "READ_MEDIA_VIDEO": None, "READ_MEDIA_AUDIO": None,
    "READ_MEDIA_VISUAL_USER_SELECTED": None,
}
expected_permissions = {"android.permission." + name: maximum for name, maximum in expected_permissions.items()}
expected_permissions["com.android.alarm.permission.SET_ALARM"] = None
permissions = manifest.findall("uses-permission")
assert len(permissions) == len(expected_permissions), "Exact integrated feature permission count"
assert {node.attrib[ANDROID + "name"]: node.get(ANDROID + "maxSdkVersion") for node in permissions} == {
    name: maximum for name, maximum in expected_permissions.items()
}, "Exact integrated feature permission boundary and legacy API caps"
assert 'applicationId = "app.daiyu.android"' in (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
assert 'rootProject.name = "代鱼"' in (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
print("Module boundary, independent identity and integrated permissions: passed")
