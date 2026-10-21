"""Exercise the pinned SVG generator regressions in the existing CI tool gate."""
from pathlib import Path
import subprocess
import sys
import unittest


class ModelIconGeneratorTest(unittest.TestCase):
    def test_pinned_paths_and_svg_grammar(self):
        root = Path(__file__).resolve().parents[1]
        generator = root / "THIRD_PARTY/rikkahub/model-icons"
        for script, args in (("test_generate.py", []), ("generate.py", ["--check"])):
            with self.subTest(script=script):
                result = subprocess.run([sys.executable, "-B", str(generator / script), *args],
                                        cwd=root, capture_output=True, text=True, timeout=30)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
