from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DOMAIN = ROOT / "core-domain/src"
for path in DOMAIN.rglob("*.kt"):
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("import "):
            assert not line.startswith(("import android.", "import androidx.", "import app.daiyu.android.MainActivity")), path

for folder in ("app/src", "core-domain/src"):
    for path in (ROOT / folder).rglob("*"):
        if path.is_file():
            assert b"io.github.mangi.eta" not in path.read_bytes(), path
manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
assert {node.attrib["{http://schemas.android.com/apk/res/android}name"] for node in manifest.findall("uses-permission")} == {"android.permission.INTERNET"}, "M0 exact permission boundary"
assert 'applicationId = "app.daiyu.android"' in (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
assert 'rootProject.name = "代鱼"' in (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
print("Module boundary, independent identity and M0 permissions: passed")
