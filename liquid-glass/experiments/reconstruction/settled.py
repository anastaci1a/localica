"""Replay frozen, dimensionless recreations; no optimization or live-lab changes.

python settled.py /private/videos /private/replay [--assets ../../assets]
Only this implementation's rasterization depends on NumPy/OpenCV. The portable
contract is ../../recreation-models.md and recipes.json, not a particular API.
"""
from pathlib import Path
import argparse, hashlib, json
import cv2
import numpy as np
from PIL import Image
from render import lookup, case_geometry, shade
from reproduce import CASES, prepare, points, scores
from publish import compose, pair, read, coverage

ROOT = Path(__file__).resolve().parent
RECIPES = json.loads((ROOT/'recipes.json').read_text())['scenes']


def radial(d, p):
    A, b, power = p['amplitude'], p['band'], p['power']
    k, q = p['width'], p['inner_power']
    t = np.clip((d-b/2)/(k*b/2), 0, 1-1e-12)
    tail = A*.5**power*np.exp(q*np.log1p(-t)+(q-power*k)*t+.5*(q-power*k*k)*t*t)
    initial = A*np.clip(1-d/b, 0, 1)**power
    return np.where(d <= b/2, initial, np.where(d < (1+k)*b/2, tail, 0.))


def mapping(x, y, preset):
    s, p = preset['shape'], preset['parameters']
    if preset['model'] != 'radial-tail':
        return lookup(x, y, s['half'], s['radius'], p, preset['model'], oval=s.get('oval'))
    r = np.hypot(x, y)
    a = radial(1-r, p)
    scale = np.divide(r-a, r, out=np.ones_like(r), where=r > 1e-12)
    return x*scale, y*scale


def lighting(x, y, preset):
    s = preset['shape']
    d, nx, ny = case_geometry(s, x, y)
    d = np.maximum(-d, 0)
    thin = np.exp(-(d/preset['thin_rim'])**2)
    e = np.exp(-(d/preset['rim_width'])**2)
    f = np.exp(-(d/(4*preset['rim_width']))**2)
    X, Y = x/s['half'][0], y/s['half'][1]
    u = np.clip(d/.30, 0, 1)
    core = u**3*(u*(6*u-15)+10)
    return np.stack([thin, thin*nx, thin*ny, e, e*nx, e*ny, f, f*nx, f*ny,
                     X, Y, X*X, Y*Y, X*Y, core], axis=-1)


def icon_color(material, p, length):
    C = 1-np.asarray(p['strength'])*(1-np.clip(material, 0, 1))**np.asarray(p['gamma'])
    L = C @ np.array([.2126, .7152, .0722])
    w = np.clip((1-L)/p['dark_span'], 0, 1)**2
    C = np.clip(L[..., None]+(1+p['dark_saturation']*w[..., None])*(C-L[..., None]), 0, 1)
    u = np.clip((C-p['highlight_start'])/(1-p['highlight_start']), 0, 1)
    C = C-p['taper']*u**3*(1-C)
    return cv2.GaussianBlur(C, (0, 0), p['final_sigma']*length)


def render(source, case, p, samples=4):
    """Integrate native pixels; lookup and lighting both use normalized units."""
    L = min(case['half'])
    sigma = p['source_sigma']*L
    blur = cv2.GaussianBlur(source, (0, 0), sigma) if sigma else source
    x0, y0, x1, y1 = case['crop']
    result = np.zeros((y1-y0, x1-x0, 3), np.float32)
    for top in range(y0, y1, 24):
        bottom = min(top+24, y1)
        yy, xx = np.mgrid[top:bottom:1/samples, x0:x1:1/samples]
        x = (xx-.5+.5/samples-case['center'][0])/L
        y = (yy-.5+.5/samples-case['center'][1])/L
        X, Y = mapping(x, y, p)
        def at(image, a, b):
            return cv2.remap(image, (a*L+case['center'][0]).astype(np.float32),
                             (b*L+case['center'][1]).astype(np.float32), cv2.INTER_LINEAR)
        if p['model'] == 'chromatic':
            C = []
            for i, wavelength in enumerate([.650, .550, .450]):
                factor = 1+p['parameters']['dispersion']*(wavelength**-2-.550**-2)
                C.append(at(blur, x+(X-x)*factor, y+(Y-y)*factor)[..., i])
            C = np.stack(C, axis=-1)
        else:
            C = at(blur, X, Y)
        if p['blur_transition']:
            sd, *_ = case_geometry(p['shape'], x, y)
            u = np.clip(-sd/p['blur_transition'], 0, 1)
            blend = u**3*(u*(6*u-15)+10)
            clear = at(source, X, Y)
            C = clear+(C-clear)*blend[..., None]
        C = shade(C, lighting(x, y, p), np.asarray(p['material']))
        # The settled playback renderer averages the full pixel's samples;
        # other studies average only covered samples before shape compositing.
        shape = (bottom-top, samples, x1-x0, samples)
        if p['model'] == 'radial-tail':
            value = C.reshape((*shape, 3)).mean((1, 3))
        else:
            sd, *_ = case_geometry(p['shape'], x, y)
            inside = (sd <= 0).astype(float)
            value = (C*inside[..., None]).reshape((*shape, 3)).sum((1, 3))
            value /= np.maximum(inside.reshape(shape).sum((1, 3)), 1)[..., None]
        result[top-y0:bottom-y0] = value
    if p.get('post_sigma'):
        result = cv2.GaussianBlur(result, (0, 0), p['post_sigma']*L)
    return result


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('videos', type=Path)
    ap.add_argument('out', type=Path)
    ap.add_argument('--assets', type=Path)
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((ROOT/'sources.json').read_text())
    for key in ['219', '284']:
        with (args.videos/f'{key}-hd.mp4').open('rb') as f:
            assert hashlib.file_digest(f, 'sha256').hexdigest() == manifest[key]['sha256']
    reports = {}
    for name, p in RECIPES.items():
        case = CASES[name]
        source, target, meta, registration = prepare(args.videos, name, case)
        # Explicit 8-bit intermediate matches the archived calibration inputs.
        source = np.uint8(np.clip(source, 0, 1)*255).astype(float)/255
        target8 = np.uint8(target*255)
        Image.fromarray(np.uint8(np.rint(source*255))).save(args.out/f'{name}-source.png')
        Image.fromarray(target8).save(args.out/f'{name}-target.png')
        material = render(source, case, p)
        x0, y0, x1, y1 = case['crop']
        score = scores(material, target[y0:y1, x0:x1], case)
        full = compose(name, case, target8, material*255, args.out).astype(float)/255
        if name == 'media':
            mask = np.asarray(Image.open(ROOT/p['icon']['coverage']))/255
            ix0, iy0, ix1, iy1 = [int(round(a*min(case['half'])+case['center'][i % 2]))
                                  for i, a in enumerate(p['icon']['box_over_L'])]
            C = icon_color(material, p['icon'], min(case['half']))
            B = material[iy0-y0:iy1-y0, ix0-x0:ix1-x0]
            C = C[iy0-y0:iy1-y0, ix0-x0:ix1-x0]
            full[iy0:iy1, ix0:ix1] = B*(1-mask[..., None])+C*mask[..., None]
            safe = cv2.erode(np.uint8(mask > .99), np.ones((7, 7), np.uint8)) > 0
            score['icon_rmse_255'] = float(np.sqrt(np.mean((C[safe]-target[iy0:iy1, ix0:ix1][safe])**2))*255)
        full8 = np.uint8(np.rint(np.clip(full, 0, 1)*255))
        Image.fromarray(full8).save(args.out/f'{name}-reconstruction.png')
        reports[name] = dict(scores=score, frame=meta, registration=registration,
                             preset='recipes.json#scenes.'+name)
        assets = args.assets or args.out
        if assets:
            assets.mkdir(parents=True, exist_ok=True)
            pair(target8[y0:y1, x0:x1], full8[y0:y1, x0:x1],
                 ('Apple · native HD crop', 'Reconstruction · frozen preset'), font_size=16).save(assets/f'match-{name}.png')
            pair(target8, full8, ('Apple · 1920 × 1080', 'Reconstruction · frozen preset'),
                 'Distant exterior retained; contact band estimated. Glass scored separately.', 24).save(assets/f'match-{name}-full.png')
            if name == 'regular':
                pair(target8[369:435, 650:1270], full8[369:435, 650:1270],
                     ('Apple · upper rim', 'Reconstruction · upper rim'), font_size=16).save(assets/'match-regular-rim.png')
        print(name, round(score['all_glass']['rmse'], 4), round(score['ssim'], 6), flush=True)
    (args.out/'settled-scores.json').write_text(json.dumps(reports, indent=2)+'\n')


if __name__ == '__main__':
    main()
