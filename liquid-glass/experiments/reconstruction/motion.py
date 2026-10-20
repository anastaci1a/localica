"""Translate the frozen playback model over its replayed backdrop.

python motion.py /private/replay /private/motion
Requires ffmpeg. The GIF palette is bounded to remain below 10,000,000 bytes.
"""
from pathlib import Path
import argparse, hashlib, json, subprocess
import cv2
import numpy as np
from PIL import Image
from settled import ROOT, RECIPES, mapping, lighting, icon_color
from render import shade


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('replay', type=Path)
    ap.add_argument('out', type=Path)
    ap.add_argument('--frames', type=int, default=180)
    args = ap.parse_args()
    assert args.frames > 0
    args.out.mkdir(parents=True, exist_ok=True)
    p = RECIPES['media']
    L = p['reference_length']
    source = np.asarray(Image.open(args.replay/'media-source.png').convert('RGB'))/255
    source_blur = cv2.GaussianBlur(source, (0, 0), p['source_sigma']*L)
    alpha = np.asarray(Image.open(ROOT/p['icon']['coverage']))/255
    N = 540
    axis = (np.arange(N*2)+.5)/2-N/2
    Y, X = np.meshgrid(axis, axis, indexing='ij')
    x, y = X/L, Y/L
    a, b = mapping(x, y, p)
    light = lighting(x, y, p)
    coverage = (np.hypot(x, y) <= 1).reshape(N, 2, N, 2).mean((1, 3))
    box = np.asarray(p['icon']['box_over_L'])*L
    mask = cv2.remap(alpha, (X-box[0]).astype(np.float32), (Y-box[1]).astype(np.float32),
                     cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT).reshape(N, 2, N, 2).mean((1, 3))
    video = args.out/'playback-final.mp4'
    log = (args.out/'encode.log').open('w')
    proc = subprocess.Popen(['ffmpeg', '-hide_banner', '-loglevel', 'warning', '-y',
        '-f', 'rawvideo', '-pixel_format', 'rgb24', '-video_size', '1920x1080',
        '-framerate', '30', '-i', 'pipe:0', '-an', '-c:v', 'libx264', '-preset', 'fast',
        '-crf', '17', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', str(video)],
        stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=log)
    try:
        for i in range(args.frames):
            t = i/args.frames
            cx, cy = np.rint([960+340*np.sin(2*np.pi*t), 542+120*np.sin(4*np.pi*t)]).astype(int)
            sample = cv2.remap(source_blur, (a*L+cx).astype(np.float32),
                               (b*L+cy).astype(np.float32), cv2.INTER_LINEAR)
            M = shade(sample, light, np.asarray(p['material'])).reshape(N, 2, N, 2, 3).mean((1, 3))
            M = cv2.GaussianBlur(M, (0, 0), p['post_sigma']*L)
            C = icon_color(M, p['icon'], L)
            M = M*(1-mask[..., None])+C*mask[..., None]
            result = source.copy()
            result[cy-N//2:cy+N//2, cx-N//2:cx+N//2] = (
                M*coverage[..., None]+result[cy-N//2:cy+N//2, cx-N//2:cx+N//2]*(1-coverage[..., None]))
            proc.stdin.write(np.uint8(np.rint(np.clip(result, 0, 1)*255)).tobytes())
    finally:
        proc.stdin.close()
        assert proc.wait() == 0
        log.close()
    gif = args.out/'playback-final.gif'
    for fps, colors in [(15, 128), (12, 96), (10, 64), (6, 48)]:
        palette = args.out/'palette.png'
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(video), '-vf',
            f'fps={fps},palettegen=max_colors={colors}:stats_mode=diff', '-frames:v', '1', str(palette)], check=True)
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(video), '-i', str(palette),
            '-lavfi', f'fps={fps}[x];[x][1:v]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle',
            '-loop', '0', '-gifflags', '+transdiff', str(gif)], check=True)
        if gif.stat().st_size < 10_000_000:
            break
    assert gif.stat().st_size < 10_000_000
    report = {}
    for file in [video, gif]:
        subprocess.run(['ffmpeg', '-v', 'error', '-i', str(file), '-f', 'null', '-'], check=True)
        report[file.name] = dict(bytes=file.stat().st_size, sha256=hashlib.sha256(file.read_bytes()).hexdigest())
    (args.out/'media.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
