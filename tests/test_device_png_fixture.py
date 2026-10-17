"""Offline integrity of the static fixture; Android decode is a separate device test."""
import base64
from pathlib import Path
import re
import struct
import unittest
import zlib


class PngFixtureTests(unittest.TestCase):
    def test_rgba_png_chunks_crc_and_scanline(self):
        root = Path(__file__).resolve().parents[1]
        source = (root / 'app/src/androidTest/kotlin/app/daiyu/android/DevicePngFixture.kt').read_text()
        data = base64.b64decode(re.search(r'"([A-Za-z0-9+/=]+)"', source).group(1), validate=True)
        self.assertEqual(data[:8], b'\x89PNG\r\n\x1a\n')
        cursor = 8; chunks = []
        while cursor < len(data):
            size = struct.unpack('>I', data[cursor:cursor + 4])[0]
            kind = data[cursor + 4:cursor + 8]; body = data[cursor + 8:cursor + 8 + size]
            checksum = struct.unpack('>I', data[cursor + 8 + size:cursor + 12 + size])[0]
            self.assertEqual(checksum, zlib.crc32(kind + body) & 0xffffffff)
            chunks.append((kind, body)); cursor += size + 12
        self.assertEqual(cursor, len(data))
        self.assertEqual([c[0] for c in chunks], [b'IHDR', b'IDAT', b'IEND'])
        self.assertEqual(struct.unpack('>IIBBBBB', chunks[0][1]), (1, 1, 8, 6, 0, 0, 0))
        self.assertEqual(zlib.decompress(chunks[1][1]), b'\0\x22\x44\x66\xff')
