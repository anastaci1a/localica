"""Small color-patch/metadata guard checks; no optical fitting.

python video_color_check.py /private/new-fixture-directory
"""
from pathlib import Path
import argparse,json,subprocess
import numpy as np
from video_color import correct,digest,probe,rgb_digest,ENCODING_COLOR_ARGS


def main():
    ap=argparse.ArgumentParser();ap.add_argument('out',type=Path);a=ap.parse_args();r=a.out
    assert not r.exists(),'Use a new fixture directory';r.mkdir(parents=True)
    colors=np.array([[255,0,0],[0,255,0],[0,0,255],[255,180,30],[80,125,210],[128,128,128]],np.uint8)
    im=np.repeat(np.repeat(colors[None,:,:],32,axis=1),96,axis=0)
    base=['ffmpeg','-v','error','-y','-f','rawvideo','-pixel_format','rgb24','-video_size','192x96','-framerate','30','-i','pipe:0','-frames:v','1']
    codec=['-c:v','libx264','-threads','2','-crf','17','-pix_fmt','yuv420p']
    old=r/'motion.mp4';new=r/'explicit.mp4'
    subprocess.run(base+['-vf','scale=in_range=pc:out_range=tv:out_color_matrix=bt601']+codec+[str(old)],input=im.tobytes(),check=True)
    subprocess.run(base+ENCODING_COLOR_ARGS+codec+[str(new)],input=im.tobytes(),check=True)
    assert probe(new)['color_space']=='smpte170m' and probe(new)['color_range']=='tv'
    before=rgb_digest(old);assert rgb_digest(new)==before,'Explicit declaration changes image samples'
    manifest=dict(render_files={'motion.mp4':digest(old)})
    (r/'render.json').write_text(json.dumps(manifest));report=correct(r)
    assert report['equal_decoded_rgb'] and rgb_digest(old)==before
    assert correct(r)['already_declared'],'Correction must be idempotent'
    original=old.read_bytes();manifest['render_files']['motion.mp4']='0'*64
    (r/'render.json').write_text(json.dumps(manifest))
    try:correct(r)
    except AssertionError:pass
    else:raise AssertionError('Changed render was accepted')
    assert old.read_bytes()==original,'Failed guard changed the video'
    print('PASS explicit601 export / metadata-only repair / every decoded RGB sample / idempotence / changed-input guard',flush=True)
    (r/'result.json').write_text(json.dumps(report,indent=2)+'\n')


if __name__=='__main__':main()
