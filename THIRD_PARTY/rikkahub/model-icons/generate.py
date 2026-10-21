#!/usr/bin/env python3
"""Convert the ten pinned SVGs to Compose vectors; --check is offline and read-only."""
from pathlib import Path
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
TARGET = ROOT / 'app/src/main/kotlin/app/daiyu/android/ui/icons/ModelBrandVectors.kt'
NAMES = {'OPENAI': 'openai', 'CLAUDE': 'claude-color', 'GEMINI': 'gemini-color',
         'DEEPSEEK': 'deepseek-color', 'QWEN': 'qwen-color', 'KIMI': 'kimi-color',
         'GROK': 'grok', 'ZHIPU': 'zhipu-color', 'DOUBAO': 'doubao-color', 'MINIMAX': 'minimax-color'}

def normalize_path(data):
    arity = {'M': 2, 'L': 2, 'H': 1, 'V': 1, 'C': 6, 'S': 4, 'Q': 4, 'T': 2, 'A': 7}
    number = re.compile(r'[+-]?(?:\d+\.\d*|\.\d+|\d+)(?:[eE][+-]?\d+)?')
    pos = 0
    command = None
    out = []
    def skip():
        nonlocal pos
        while pos < len(data) and data[pos] in ' \t\r\n,': pos += 1
    while True:
        skip()
        if pos == len(data): break
        if data[pos] in 'MmLlHhVvCcSsQqTtAaZz':
            command = data[pos]; pos += 1
            if command.upper() == 'Z':
                out.append(command); command = None; continue
        if command is None: raise ValueError('Expected SVG path command')
        args = []
        for index in range(arity[command.upper()]):
            skip()
            if command.upper() == 'A' and index in (3, 4):
                if pos == len(data) or data[pos] not in '01': raise ValueError('Invalid SVG arc flag')
                args.append(data[pos]); pos += 1
            else:
                match = number.match(data, pos)
                if match is None: raise ValueError('Invalid SVG path argument')
                args.append(match.group()); pos = match.end()
        out.append(command + ' '.join(args))
        if command in 'Mm': command = 'l' if command == 'm' else 'L'
    return ' '.join(out)


def number(value):
    return f'{float(value):.8g}f'

def color(value, opacity='1'):
    if value == 'currentColor': value = '#000000'
    assert value.startswith('#'), value
    h = value[1:]
    if len(h) == 3: h = ''.join(c * 2 for c in h)
    assert len(h) == 6
    return f'Color(0x{round(float(opacity) * 255):02X}{h.upper()})'

def render():
    out = ['''// Generated from pinned RikkaHub/LobeHub SVGs by THIRD_PARTY/rikkahub/model-icons/generate.py.
// Source, copyright and licenses: THIRD_PARTY/rikkahub/model-icons/NOTICE.md.
package app.daiyu.android.ui.icons

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

internal object ModelBrandVectors {''']
    for name, filename in NAMES.items():
        root = ET.parse(HERE / 'svg' / (filename + '.svg')).getroot()
        assert root.attrib['viewBox'] == '0 0 24 24'
        gradients = {e.attrib['id']: e for e in root.iter() if e.tag.endswith('}linearGradient')}
        out.append(f'    val {name}: ImageVector by lazy {{\n        ImageVector.Builder(name = "ModelBrand.{name}", defaultWidth = 24.dp, defaultHeight = 24.dp,\n            viewportWidth = 24f, viewportHeight = 24f).apply {{')
        def emit(e, inherited, indent='            '):
            kind = e.tag.split('}')[-1]
            if kind in ('title', 'defs'): return
            attrs = dict(inherited, **e.attrib)
            if kind == 'g':
                m = re.fullmatch(r'translate\(([-.\d]+) ([-.\d]+)\) scale\(([-.\d]+)\)', attrs['transform'])
                assert m, attrs
                x,y,s = map(number,m.groups())
                out.append(indent+f'addGroup(translationX = {x}, translationY = {y}, scaleX = {s}, scaleY = {s})')
                for child in e: emit(child, attrs, indent)
                out.append(indent+'clearGroup()'); return
            if kind == 'rect':
                assert e.attrib == {'width':'24','height':'24','rx':'5','fill':'#000'}
                data = 'M5 0H19A5 5 0 0 1 24 5V19A5 5 0 0 1 19 24H5A5 5 0 0 1 0 19V5A5 5 0 0 1 5 0Z'
            else:
                assert kind == 'path', kind
                data = attrs['d']
            quoted = json.dumps(normalize_path(data))
            fill = attrs.get('fill','currentColor')
            rule = 'true' if attrs.get('fill-rule') == 'evenodd' else 'false'
            if fill.startswith('url(#'):
                gradient = gradients[fill[5:-1]]
                relative = gradient.attrib.get('gradientUnits','objectBoundingBox') == 'objectBoundingBox'
                def coord(key):
                    v=gradient.attrib[key]
                    return number(float(v[:-1])/100 if v.endswith('%') else float(v))
                stops=[]
                for stop in gradient:
                    v=stop.attrib.get('offset','0')
                    n=float(v[:-1])/100 if v.endswith('%') else float(v)
                    stops.append(f'{number(n)} to {color(stop.attrib["stop-color"],stop.attrib.get("stop-opacity","1"))}')
                out.append(indent+f'brandGradientPath({quoted},\n'+indent+'    arrayOf('+', '.join(stops)+'),\n'+indent+f'    Offset({coord("x1")}, {coord("y1")}), Offset({coord("x2")}, {coord("y2")}), relative = {str(relative).lower()}, evenOdd = {rule})')
            else:
                out.append(indent+f'brandPath({quoted}, SolidColor({color(fill)}), evenOdd = {rule})')
        for e in root: emit(e, root.attrib)
        out.append('        }.build()\n    }\n')
    out.append('''}

private fun ImageVector.Builder.brandPath(data: String, fill: Brush, evenOdd: Boolean) {
    addPath(pathData = PathParser().parsePathString(data).toNodes(), fill = fill,
        pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero)
}

private fun ImageVector.Builder.brandGradientPath(data: String, stops: Array<Pair<Float, Color>>,
    start: Offset, end: Offset, relative: Boolean, evenOdd: Boolean) {
    val parser = PathParser().parsePathString(data)
    val bounds = if (relative) parser.toPath().getBounds() else null
    fun coordinate(point: Offset) = if (bounds == null) point
        else Offset(bounds.left + point.x * bounds.width, bounds.top + point.y * bounds.height)
    addPath(pathData = parser.toNodes(), fill = Brush.linearGradient(*stops,
        start = coordinate(start), end = coordinate(end)),
        pathFillType = if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero)
}
''')
    return '\n'.join(out)

if __name__ == '__main__':
    manifest=json.loads((HERE/'sources.json').read_text())
    for item in manifest['icons']:
        assert hashlib.sha256((HERE/item['local']).read_bytes()).hexdigest() == item['sha256'], item['local']
    text = render()
    if '--check' in sys.argv:
        assert TARGET.read_text() == text, 'generated vectors differ'
        print('10 pinned SVG hashes and generated Compose vectors: passed')
    else:
        TARGET.write_text(text)
        print(TARGET.relative_to(ROOT))
