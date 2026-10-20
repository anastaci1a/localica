"""Rasterize solid SVG coverage independently of foreground color response.

SVG assets are private reproduction inputs, just like the recovered backdrop.
Placement uses reference-image coordinates; every output evaluates the vector
at its own resolution. No screenshot alpha, texture or color enters coverage.
"""
from functools import lru_cache
from io import BytesIO
from pathlib import Path
import hashlib
import shutil
import xml.etree.ElementTree as ET
import cv2
import numpy as np


@lru_cache(maxsize=128)
def _coverage(svg, box, placement, scale):
    import cairosvg
    root=ET.fromstring(svg)
    allowed={'svg','g','path','circle','ellipse','rect','polygon','polyline','line'}
    for node in root.iter():
        assert node.tag.split('}')[-1] in allowed, 'Solid vector geometry only'
        assert not any(k.split('}')[-1] in {'href','filter','opacity','fill-opacity','stroke-opacity'} for k in node.attrib), 'Effects belong in the renderer'
    a,b,c,d=box;left,top,right,bottom=placement
    W,H=(c-a)*scale,(d-b)*scale
    # Reference pixel centers have integer coordinates; SVG canvas edges lie
    # half a native pixel outside the first/last center.
    root.set('x',str((left-a+.5)*scale));root.set('y',str((top-b+.5)*scale))
    root.set('width',str((right-left)*scale));root.set('height',str((bottom-top)*scale))
    root.set('preserveAspectRatio','none')
    outer=ET.Element('svg',{'xmlns':'http://www.w3.org/2000/svg','width':str(W),'height':str(H),'viewBox':f'0 0 {W} {H}'})
    outer.append(root)
    png=cairosvg.svg2png(bytestring=ET.tostring(outer),output_width=W*4,output_height=H*4)
    rgba=cv2.imdecode(np.frombuffer(png,np.uint8),cv2.IMREAD_UNCHANGED)
    alpha=rgba[:,:,3].astype(np.float32)/255
    return cv2.resize(alpha,(W,H),interpolation=cv2.INTER_AREA)


def coverage(folder,layer,scale=1):
    path=Path(folder)/layer['vector_asset'];raw=path.read_bytes()
    assert hashlib.sha256(raw).hexdigest()==layer['vector_sha256'], 'Changed frozen vector asset'
    return _coverage(raw,tuple(layer['box']),tuple(layer['vector_box']),scale).copy()


def copy_assets(case,source,destination):
    """Copy declared foreground inputs; constructors verify their frozen hashes."""
    for layer in case.get('foreground_layers',[]):
        for key in ['vector_asset','artwork_asset']:
            if key in layer:
                path=Path(source)/layer[key]
                assert path.is_file(),f'Missing private foreground input: {path}'
                shutil.copyfile(path,Path(destination)/layer[key])
