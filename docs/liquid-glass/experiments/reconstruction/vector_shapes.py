"""Clean geometric traces, not Apple's original SF Symbols outlines.

python vector_shapes.py /private/icons
All coordinates use a100-unit square. SVG coverage is solid; no sampled image,
color grading, transparency or blur is encoded in these shapes.
"""
from pathlib import Path
import argparse

SHAPES={
 'home':'<path fill="#000" d="M43.8 2.6 Q50 -2.6 56.2 2.6 L95.9 36.3 Q100 39.7 100 45 L100 89 Q100 100 89 100 L67 100 Q63 100 63 96 L63 68 Q63 64 59 64 L41 64 Q37 64 37 68 L37 96 Q37 100 33 100 L11 100 Q0 100 0 89 L0 45 Q0 39.7 4.1 36.3 Z"/>',
 'search':'<path fill="#000" fill-rule="evenodd" d="M40.9 0 A40.9 40.9 0 1 1 40.9 81.8 A40.9 40.9 0 1 1 40.9 0 Z M40.9 9.2 A31.7 31.7 0 1 0 40.9 72.6 A31.7 31.7 0 1 0 40.9 9.2 Z"/><path fill="#000" d="M63.04 70.96 L89.64 97.56 A5.6 5.6 0 0 0 97.56 89.64 L70.96 63.04 Z"/>',
}


def svg(name):
    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">'+SHAPES[name]+'</svg>\n'


if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('out',type=Path);args=ap.parse_args()
    args.out.mkdir(parents=True,exist_ok=True)
    for name in SHAPES:(args.out/(name+'.svg')).write_text(svg(name))
