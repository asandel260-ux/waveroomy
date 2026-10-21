#!/usr/bin/env python3
"""Converts the web app's Lucide icon set (common.js) into SVG path-data lists for Compose."""
import re, sys, math
import xml.etree.ElementTree as ET

src = open(sys.argv[1], encoding='utf-8').read()
m = re.search(r"const ICONS = \{(.*?)\n  \};", src, re.S)
body = m.group(1)
icons = {}
for line in body.split("\n"):
    mm = re.match(r"\s*'([^']+)':\s*'(.*)',?\s*$", line)
    if mm:
        icons[mm.group(1)] = mm.group(2)

extra = {
 'qr': '<rect width="5" height="5" x="3" y="3" rx="1"/><rect width="5" height="5" x="16" y="3" rx="1"/><rect width="5" height="5" x="3" y="16" rx="1"/><path d="M21 16h-3a2 2 0 0 0-2 2v3"/><path d="M21 21v.01"/><path d="M12 7v3a2 2 0 0 1-2 2H7"/><path d="M3 12h.01"/><path d="M12 3h.01"/><path d="M12 16v.01"/><path d="M16 12h1"/><path d="M21 12v.01"/><path d="M12 21v-1"/>',
 'scan': '<path d="M3 7V5a2 2 0 0 1 2-2h2"/><path d="M17 3h2a2 2 0 0 1 2 2v2"/><path d="M21 17v2a2 2 0 0 1-2 2h-2"/><path d="M7 21H5a2 2 0 0 1-2-2v-2"/><path d="M7 12h10"/>',
 'wifi': '<path d="M12 20h.01"/><path d="M2 8.82a15 15 0 0 1 20 0"/><path d="M5 12.859a10 10 0 0 1 14 0"/><path d="M8.5 16.429a5 5 0 0 1 7 0"/>',
 'user': '<path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/>',
 'check': '<path d="M20 6 9 17l-5-5"/>',
 'share': '<circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" x2="15.42" y1="13.51" y2="17.49"/><line x1="15.42" x2="8.59" y1="6.51" y2="10.49"/>',
 'more': '<circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/><circle cx="5" cy="12" r="1"/>',
 'more-v': '<circle cx="12" cy="12" r="1"/><circle cx="12" cy="5" r="1"/><circle cx="12" cy="19" r="1"/>',
 'send': '<path d="m22 2-7 20-4-9-9-4Z"/><path d="M22 2 11 13"/>',
 'shield': '<path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"/>',
 'chevron-right': '<path d="m9 18 6-6-6-6"/>',
 'chevron-left': '<path d="m15 18-6-6 6-6"/>',
 'chevron-up': '<path d="m18 15-6-6-6 6"/>',
 'info': '<circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/>',
 'settings': '<path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z"/><circle cx="12" cy="12" r="3"/>',
 'user-plus': '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><line x1="19" x2="19" y1="8" y2="14"/><line x1="22" x2="16" y1="11" y2="11"/>',
 'ban': '<circle cx="12" cy="12" r="10"/><path d="m4.9 4.9 14.2 14.2"/>',
 'crown': '<path d="m2 4 3 12h14l3-12-6 7-4-7-4 7-6-7zm3 16h14"/>',
 'speaker': '<rect width="16" height="20" x="4" y="2" rx="2"/><circle cx="12" cy="14" r="4"/><line x1="12" x2="12.01" y1="6" y2="6"/>',
 'zap': '<path d="M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z"/>',
}
icons.update(extra)

def f(v):
    s = ('%.3f' % float(v)).rstrip('0').rstrip('.')
    return s if s else '0'

def to_paths(frag):
    root = ET.fromstring('<g>' + frag + '</g>')
    out = []
    for el in root:
        t = el.tag
        a = el.attrib
        if t == 'path':
            out.append(a['d'])
        elif t == 'line':
            out.append('M%s %s L%s %s' % (f(a['x1']), f(a['y1']), f(a['x2']), f(a['y2'])))
        elif t == 'circle':
            cx, cy, r = float(a['cx']), float(a['cy']), float(a['r'])
            out.append('M%s %s a%s %s 0 1 0 %s 0 a%s %s 0 1 0 %s 0Z' % (f(cx - r), f(cy), f(r), f(r), f(2 * r), f(r), f(r), f(-2 * r)))
        elif t == 'rect':
            x, y, w, h = float(a['x']), float(a['y']), float(a['width']), float(a['height'])
            r = float(a.get('rx', 0))
            if r <= 0:
                out.append('M%s %s h%s v%s h%s Z' % (f(x), f(y), f(w), f(h), f(-w)))
            else:
                out.append('M%s %s h%s a%s %s 0 0 1 %s %s v%s a%s %s 0 0 1 %s %s h%s a%s %s 0 0 1 %s %s v%s a%s %s 0 0 1 %s %s Z' % (
                    f(x + r), f(y), f(w - 2 * r), f(r), f(r), f(r), f(r), f(h - 2 * r), f(r), f(r), f(-r), f(r),
                    f(-(w - 2 * r)), f(r), f(r), f(-r), f(-r), f(-(h - 2 * r)), f(r), f(r), f(r), f(-r)))
        elif t in ('polyline', 'polygon'):
            pts = [float(n) for n in re.split(r'[ ,]+', a['points'].strip())]
            d = 'M%s %s' % (f(pts[0]), f(pts[1]))
            for i in range(2, len(pts), 2):
                d += ' L%s %s' % (f(pts[i]), f(pts[i + 1]))
            if t == 'polygon':
                d += ' Z'
            out.append(d)
    return out

lines = ['package app.waveroom.ui.components', '',
         '// GENERATED by tools/gen_icons.py from the web app\'s Lucide icon set. Do not edit by hand.', '',
         'val ICON_PATHS: Map<String, List<String>> = mapOf(']
for name, frag in icons.items():
    try:
        ps = to_paths(frag)
    except Exception as e:
        sys.stderr.write('skip %s: %s\n' % (name, e))
        continue
    lines.append('    "%s" to listOf(%s),' % (name, ', '.join('"%s"' % p.replace('\\', '\\\\').replace('"', '\\"') for p in ps)))
lines.append(')')
open(sys.argv[2], 'w').write('\n'.join(lines) + '\n')
print(len(icons), 'icons')
