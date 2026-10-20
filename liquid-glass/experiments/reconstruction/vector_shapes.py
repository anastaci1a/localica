"""Clean geometric traces, not Apple's original SF Symbols outlines.

python vector_shapes.py /private/icons
Coordinates use the declared normalized viewBox. SVG coverage is solid; no sampled image,
color grading, transparency or blur is encoded in these shapes.
"""
from pathlib import Path
import argparse

SHAPES={
 'home':'<path fill="#000" d="M43.8 2.6 Q50 -2.6 56.2 2.6 L95.9 36.3 Q100 39.7 100 45 L100 89 Q100 100 89 100 L67 100 Q63 100 63 96 L63 68 Q63 64 59 64 L41 64 Q37 64 37 68 L37 96 Q37 100 33 100 L11 100 Q0 100 0 89 L0 45 Q0 39.7 4.1 36.3 Z"/>',
 'search':'<path fill="#000" fill-rule="evenodd" d="M40.9 0 A40.9 40.9 0 1 1 40.9 81.8 A40.9 40.9 0 1 1 40.9 0 Z M40.9 9.2 A31.7 31.7 0 1 0 40.9 72.6 A31.7 31.7 0 1 0 40.9 9.2 Z"/><path fill="#000" d="M63.04 70.96 L89.64 97.56 A5.6 5.6 0 0 0 97.56 89.64 L70.96 63.04 Z"/>',
 'new-grid':''.join(f'<rect x="{x}" y="{y}" width="45.5" height="45.5" rx="9.5" fill="#000"/>' for y in [0,54.5] for x in [0,54.5]),
 'library-stack':'<path fill="#000" d="M22 9 C22 4 26 0 32 0 H68 C74 0 78 4 78 9 Z M12 25 C12 19 17 15 23 15 H77 C83 15 88 19 88 25 Z"/>'
 '<path fill="#000" fill-rule="evenodd" d="M22.5 32 H77.5 A22.5 22.5 0 0 1 100 54.5 V109.5 A22.5 22.5 0 0 1 77.5 132 H22.5 A22.5 22.5 0 0 1 0 109.5 V54.5 A22.5 22.5 0 0 1 22.5 32 Z '
 'M69.5 53.4 C71.9 52.7 73.5 54 73.5 56.7 V99.5 C73.5 106.3 68.1 110.7 62 111.6 C55.8 112.6 52.1 109.9 51.9 105.9 C51.7 101.9 54.6 98 60.5 96.1 L66.2 94.4 V65.7 L41 72.1 V106.2 C41 113 35.6 117.4 29.5 118.3 C23.3 119.3 19.6 116.6 19.4 112.6 C19.2 108.6 22.1 104.7 28 102.8 L33.7 101.1 V65.7 C33.7 63.5 34.9 62.4 37.1 61.8 Z"/>',
 'selected-backing':'<rect width="281" height="200" rx="100" fill="#000"/>',
}
VIEWBOX={'library-stack':'0 0 100 132','selected-backing':'0 0 281 200'}


def svg(name):
    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="'+VIEWBOX.get(name,'0 0 100 100')+'">'+SHAPES[name]+'</svg>\n'


def library_variant(dx=0.,dy=0.,scale=1.):
    """Move/scale the clean note as one unit; leave symmetric frames intact."""
    import xml.etree.ElementTree as ET
    from fontTools.svgLib.path import parse_path
    from fontTools.pens.svgPathPen import SVGPathPen
    from fontTools.pens.transformPen import TransformPen
    root=ET.fromstring(svg('library-stack'))
    node=next(n for n in root.iter() if n.get('fill-rule')=='evenodd')
    outer,note=node.get('d').split(' M69.5',1)
    pen=SVGPathPen(None)
    parse_path('M69.5'+note,TransformPen(pen,(scale,0,0,scale,dx+46.45*(1-scale),dy+86.35*(1-scale))))
    node.set('d',outer+' '+pen.getCommands())
    return ET.tostring(root)


if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('out',type=Path);args=ap.parse_args()
    args.out.mkdir(parents=True,exist_ok=True)
    for name in SHAPES:(args.out/(name+'.svg')).write_text(svg(name))
