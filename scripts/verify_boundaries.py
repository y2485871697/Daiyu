from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
DOMAIN = ROOT / "core-domain/src"
for path in DOMAIN.rglob("*.kt"):
    for line in path.read_text().splitlines():
        if line.startswith("import "):
            assert not line.startswith(("import android.", "import androidx.", "import app.daiyu.android.MainActivity")), path

for folder in ("app/src", "core-domain/src"):
    for path in (ROOT / folder).rglob("*"):
        if path.is_file():
            assert b"io.github.mangi.eta" not in path.read_bytes(), path
manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
assert not manifest.findall("uses-permission"), "S1 must not claim runtime capabilities"
assert 'applicationId = "app.daiyu.android"' in (ROOT / "app/build.gradle.kts").read_text()
assert 'rootProject.name = "代鱼"' in (ROOT / "settings.gradle.kts").read_text()
print("Module boundary, independent identity and S1 permissions: passed")
