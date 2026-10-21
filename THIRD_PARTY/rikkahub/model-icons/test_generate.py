"""SVG grammar regression cases for the pinned icon generator."""
import unittest
from generate import normalize_path, HERE, NAMES
import xml.etree.ElementTree as ET


class PathNormalizationTest(unittest.TestCase):
    def test_packed_arc_flags(self):
        cases = {
            'a5.416 5.416 0 00-.478-4.501': 'a5.416 5.416 0 0 0 -.478 -4.501',
            'A5.59 5.59 0 0010.831 1': 'A5.59 5.59 0 0 0 10.831 1',
            'A4.15 4.15 0 015.198 6.41': 'A4.15 4.15 0 0 1 5.198 6.41',
            'a.067.067 0 01.063-.006': 'a.067 .067 0 0 1 .063 -.006',
            'a.502.502 0 000 .975': 'a.502 .502 0 0 0 0 .975',
            'a1.923 1.923 0 110 3.846': 'a1.923 1.923 0 1 1 0 3.846',
            'M0 0a5 5 0 001 2 5 5 0 003 4': 'M0 0 a5 5 0 0 0 1 2 a5 5 0 0 0 3 4',
            'M0 0A5 5 0 101 2': 'M0 0 A5 5 0 1 0 1 2',
        }
        for source, expected in cases.items():
            with self.subTest(source=source):
                self.assertEqual(expected, normalize_path(source))

    def test_implicit_lineto_numbers_and_close(self):
        self.assertEqual('M1e-2 2 L3 4 z m.1 -.2 l.3 -.4',
                         normalize_path('M1e-2,2 3,4z m.1-.2.3-.4'))
        self.assertEqual('M.71 .71 l-.1 -.2', normalize_path('M.71.71l-.1-.2'))

    def test_invalid_path_is_rejected(self):
        for data in ('M0', 'M0 0a5 5 0 201 2', 'M0 0a5 5 0 00', 'Z1 2', 'M0 0?', 'X0 0'):
            with self.subTest(data=data), self.assertRaises(ValueError):
                normalize_path(data)

    def test_all_pinned_paths_are_idempotent(self):
        for filename in NAMES.values():
            root = ET.parse(HERE / 'svg' / (filename + '.svg')).getroot()
            for element in root.iter():
                if 'd' in element.attrib:
                    data = normalize_path(element.attrib['d'])
                    self.assertEqual(data, normalize_path(data), filename)


if __name__ == '__main__':
    unittest.main()
