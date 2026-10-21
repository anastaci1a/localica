"""Translate the frozen playback model over its replayed backdrop.

python motion.py /private/replay /private/motion --frames 90 --fps 15 --width 1080
Requires ffmpeg. The GIF palette is bounded to remain below 10,000,000 bytes.
"""
from pathlib import Path
import argparse, hashlib, json, subprocess, struct
import cv2
import numpy as np
from PIL import Image
from settled import ROOT, RECIPES, mapping, lighting, icon_color
from render import shade
from video_color import ENCODING_COLOR_ARGS, probe



def set_gif_timing(path, duration):
    """Set centisecond delays without re-encoding image/palette blocks."""
    data = bytearray(path.read_bytes())
    assert data[:6] in (b'GIF89a', b'GIF87a')
    pos = 13
    if data[10] & 128:
        pos += 3*2**((data[10] & 7)+1)
    delays, images = [], 0
    while data[pos] != 0x3b:
        tag = data[pos]
        pos += 1
        if tag == 0x21:
            label = data[pos]
            pos += 1
            if label == 0xf9:
                assert data[pos] == 4
                delays.append(pos+2)
            while True:
                size = data[pos]
                pos += 1
                if not size:
                    break
                pos += size
        elif tag == 0x2c:
            packed = data[pos+8]
            pos += 9
            if packed & 128:
                pos += 3*2**((packed & 7)+1)
            pos += 1
            while True:
                size = data[pos]
                pos += 1
                if not size:
                    break
                pos += size
            images += 1
        else:
            raise ValueError('Unexpected GIF block')
    assert images == len(delays) and images > 0
    ticks = round(duration*100)
    assert ticks >= images
    for i, offset in enumerate(delays):
        delay = round((i+1)*ticks/images)-round(i*ticks/images)
        assert 0 < delay <= 65535
        struct.pack_into('<H', data, offset, delay)
    path.write_bytes(data)
    return images, ticks*10


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('replay', type=Path)
    ap.add_argument('out', type=Path)
    ap.add_argument('--frames', type=int, default=180)
    ap.add_argument('--width', type=int, default=1920, help='Final movie width; preserve the source aspect with an even video height')
    ap.add_argument('--fps', type=int, default=30, help='Movie frame rate; GIF uses at most15fps without lowering that rate for the size cap')
    ap.add_argument('--record', type=Path, help='Hashed playback refinement record; default retains the original preset')
    args = ap.parse_args()
    assert args.frames > 0 and args.fps > 0 and args.width > 0 and args.width % 2 == 0
    args.out.mkdir(parents=True, exist_ok=True)
    p = RECIPES['media']
    if args.record:
        record = json.loads(args.record.read_text())
        for name, digest in record['input_sha256'].items():
            assert hashlib.sha256((args.replay/name).read_bytes()).hexdigest() == digest, 'Changed motion input'
        p = record['recipe']
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
    height = 2*round(source.shape[0]*args.width/source.shape[1]/2)
    assert height > 0
    video = args.out/'playback-final.mp4'
    log = (args.out/'encode.log').open('w')
    proc = subprocess.Popen(['ffmpeg', '-hide_banner', '-loglevel', 'warning', '-y',
        '-f', 'rawvideo', '-pixel_format', 'rgb24', '-video_size', f'{args.width}x{height}',
        '-framerate', str(args.fps), '-i', 'pipe:0', '-an', '-c:v', 'libx264', '-preset', 'fast',
        '-crf', '17', '-threads', '2', '-filter_threads', '1', '-pix_fmt', 'yuv420p', *ENCODING_COLOR_ARGS, '-movflags', '+faststart', str(video)],
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
            rgb = np.uint8(np.rint(np.clip(result, 0, 1)*255))
            if (args.width, height) != (rgb.shape[1], rgb.shape[0]):
                rgb = cv2.resize(rgb, (args.width, height), interpolation=cv2.INTER_LANCZOS4)
            proc.stdin.write(rgb.tobytes())
    finally:
        proc.stdin.close()
        assert proc.wait() == 0
        log.close()
    gif = args.out/'playback-final.gif'
    fps = min(args.fps, 15)
    for colors in [128, 96, 64, 48, 32]:
        palette = args.out/'palette.png'
        subprocess.run(['ffmpeg', '-v', 'error', '-threads', '2', '-filter_threads', '1', '-filter_complex_threads', '1', '-y', '-i', str(video), '-vf',
            f'fps={fps},palettegen=max_colors={colors}:stats_mode=diff', '-frames:v', '1', str(palette)], check=True)
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', str(video), '-i', str(palette),
            '-lavfi', f'fps={fps}[x];[x][1:v]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle',
            '-loop', '0', '-gifflags', '+transdiff', str(gif)], check=True)
        if gif.stat().st_size < 10_000_000:
            break
    assert gif.stat().st_size < 10_000_000
    gif_frames, gif_duration_ms = set_gif_timing(gif, args.frames/args.fps)
    assert gif_frames == round(args.frames*fps/args.fps)
    report = {}
    for file in [video, gif]:
        subprocess.run(['ffmpeg', '-v', 'error', '-threads', '2', '-i', str(file), '-f', 'null', '-'], check=True)
        report[file.name] = dict(bytes=file.stat().st_size, sha256=hashlib.sha256(file.read_bytes()).hexdigest())
    if args.record:
        report['_recipe'] = dict(sha256=hashlib.sha256(args.record.read_bytes()).hexdigest(), frames=args.frames, fps=args.fps, output_size=[args.width,height], video_color=probe(video), sampling='Two subpixels per output axis; same normalized map/material, lower integration rate than still verification')
    report['_gif'] = dict(frames=gif_frames, fps=fps, duration_ms=gif_duration_ms, palette_colors=colors, dither='bayer:bayer_scale=4', full_movie_width=True)
    (args.out/'media.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
