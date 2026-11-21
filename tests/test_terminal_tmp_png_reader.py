"""Execute the production embedded reader on the Linux CI runner, not on Android."""
import base64
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import textwrap
import unittest
import zlib

SOURCE = Path(__file__).resolve().parents[1] / 'app/src/main/kotlin/app/daiyu/android/terminal/TerminalRegistry.kt'
CAP = 128 * 1024
SIG = b'\x89PNG\r\n\x1a\n'


def chunk(kind, data):
    return struct.pack('!I', len(data)) + kind + data + struct.pack('!I', zlib.crc32(kind + data) & 0xffffffff)


PNG = SIG + chunk(b'IHDR', struct.pack('!IIBBBBB', 1, 1, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(b'\0\0\0\0')) + chunk(b'IEND', b'')


class TerminalTmpPngReaderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        match = re.search(r'private val TMP_PNG_READER = """\n(.*?)\n\s*"""\.trimIndent\(\)', SOURCE.read_text(encoding='utf-8'), re.S)
        if match is None:
            raise AssertionError('Actual Kotlin reader literal not found')
        cls.reader = textwrap.dedent(match.group(1))

    def make_directory(self):
        directory = tempfile.TemporaryDirectory(prefix='daiyu-reader-test-', dir='/tmp')
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)

    def invoke(self, path):
        encoded = base64.b64encode(str(path).encode()).decode('ascii')
        return subprocess.run([sys.executable, '-I', '-S', '-c', self.reader, encoded], shell=False,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=3, check=False)

    def assert_error(self, path, reason):
        result = self.invoke(path)
        self.assertEqual(1, result.returncode, result.stderr)
        self.assertEqual(b'ERR:' + reason + os.linesep.encode(), result.stdout)
        self.assertEqual(b'', result.stderr)

    @unittest.skipIf(os.name == "nt", "Linux /tmp reader requires POSIX directory descriptors; covered by Linux matrix")
    def test_actual_png_and_path_characters(self):
        self.make_directory()
        path = self.root / "帧 ' quoted.png"
        path.write_bytes(PNG)
        result = self.invoke(path)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(b'PNG:' + base64.b64encode(PNG) + b'\n', result.stdout)
        self.assertEqual(b'', result.stderr)
        self.assertEqual(PNG, path.read_bytes())

    @unittest.skipIf(os.name == "nt", "Linux /tmp reader requires POSIX directory descriptors; covered by Linux matrix")
    def test_limit_and_non_png(self):
        self.make_directory()
        path = self.root / 'frame.png'
        data = SIG + bytes(CAP - len(SIG))
        path.write_bytes(data)
        result = self.invoke(path)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(b'PNG:' + base64.b64encode(data) + b'\n', result.stdout)
        path.write_bytes(data + b'x')
        self.assert_error(path, b'too_large')
        path.write_bytes(b'not a png')
        self.assert_error(path, b'not_png')

    @unittest.skipIf(os.name == "nt", "Linux /tmp reader requires POSIX directory descriptors; covered by Linux matrix")
    def test_symlinks_directory_fifo_and_missing(self):
        self.make_directory()
        target = self.root / 'frame.png'
        target.write_bytes(PNG)
        link = self.root / 'link.png'
        link.symlink_to(target)
        self.assert_error(link, b'open_or_read_failed')
        parent = self.root / 'linked-dir'
        parent.symlink_to(self.root, target_is_directory=True)
        self.assert_error(parent / 'frame.png', b'open_or_read_failed')
        directory = self.root / 'directory.png'
        directory.mkdir()
        self.assert_error(directory, b'not_regular')
        fifo = self.root / 'fifo.png'
        os.mkfifo(fifo)
        self.assert_error(fifo, b'not_regular')
        self.assert_error(self.root / 'missing.png', b'open_or_read_failed')

    def test_invalid_paths(self):
        for path in ('/workspace/frame.png', '/tmp//frame.png', '/tmp/reader/../frame.png', '/tmp/reader/./frame.png'):
            with self.subTest(path=path):
                self.assert_error(path, b'invalid_path')
