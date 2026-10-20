"""Export solid original font outlines; font files remain private inputs.

python font_vector.py font.otf out.svg --glyph play.tag.color
python font_vector.py font.otf out.svg --text 'All Of Me'
Text uses nominal advances plus size-dependent normal AAT tracking when set.
https://developer.apple.com/fonts/TrueType-Reference-Manual/RM06/Chap6trak.html
Pose/response are separate inputs; curves stay vector paths at every size.
"""
from pathlib import Path
import argparse,bisect,hashlib,json
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.transformPen import TransformPen


def export(font,glyph=None,text=None,size_pt=None):
    f=TTFont(font);gs=f.getGlyphSet();pen=SVGPathPen(gs);bounds=BoundsPen(gs)
    names=[glyph] if glyph else [f.getBestCmap()[ord(c)] for c in text]
    tracking=0.
    if text and size_pt is not None and 'trak' in f and 0. in f['trak'].horizData:
        table=f['trak'].horizData[0.];sizes=sorted(table)
        i=max(1,min(len(sizes)-1,bisect.bisect_right(sizes,size_pt)))
        left,right=sizes[i-1:i+1]
        tracking=table[left]+(table[right]-table[left])*(size_pt-left)/(right-left)
    advance=0
    for name in names:
        transform=(1,0,0,1,advance,0)
        gs[name].draw(TransformPen(pen,transform));gs[name].draw(TransformPen(bounds,transform))
        advance+=f['hmtx'][name][0]+tracking
    x0,y0,x1,y1=bounds.bounds
    svg=f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {x1-x0} {y1-y0}"><path fill="#000" transform="translate({-x0} {y1}) scale(1 -1)" d="{pen.getCommands()}"/></svg>\n'
    return svg,dict(font_file=Path(font).name,font_sha256=hashlib.sha256(Path(font).read_bytes()).hexdigest(),glyph=glyph,text=text,glyphs=names,bounds=[x0,y0,x1,y1],advance=advance,size_pt=size_pt,tracking_font_units=tracking,units_per_em=f['head'].unitsPerEm,layout='Nominal advances plus normal AAT trak interpolation when size supplied; no pair kerning or optical-size substitution')


if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('font',type=Path);ap.add_argument('out',type=Path);ap.add_argument('--size-pt',type=float);g=ap.add_mutually_exclusive_group(required=True);g.add_argument('--glyph');g.add_argument('--text');args=ap.parse_args()
    raw,metadata=export(args.font,args.glyph,args.text,args.size_pt);args.out.write_text(raw)
    args.out.with_suffix('.provenance.json').write_text(json.dumps(metadata,indent=2)+'\n')
