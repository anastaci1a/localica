"""Declare the export's YCbCr matrix without altering encoded image samples.

python video_color.py /private/study/final

Older raw-RGB exports left the matrix unspecified. This correction is allowed
only when their frozen render manifest matches and decoded RGB is unchanged.
No transfer-function/primaries calibration is inferred from a missing tag.
https://ffmpeg.org/ffmpeg-bitstream-filters.html#h264_005fmetadata
"""
from pathlib import Path
import argparse,hashlib,json,subprocess,tempfile

ENCODING_COLOR_ARGS=['-vf','scale=in_range=pc:out_range=tv:out_color_matrix=bt601','-colorspace','smpte170m','-color_range','tv']


def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()


def probe(path):
    raw=subprocess.check_output(['ffprobe','-v','error','-select_streams','v:0','-show_entries','stream=codec_name,color_space,color_range','-of','json',str(path)])
    return json.loads(raw)['streams'][0]


def rgb_digest(path):
    return subprocess.check_output(['ffmpeg','-v','error','-threads','2','-i',str(path),'-map','0:v:0','-pix_fmt','rgb24','-f','hash','-hash','sha256','-']).decode().strip()


def correct(folder):
    video=folder/'motion.mp4';manifest=json.loads((folder/'render.json').read_text())
    assert all(digest(folder/name)==sha for name,sha in manifest['render_files'].items()),'Changed frozen render'
    info=probe(video)
    if info.get('color_space')=='smpte170m' and info.get('color_range')=='tv':return dict(already_declared=True,metadata=info)
    assert info['codec_name']=='h264' and info.get('color_space') in [None,'unknown'],'Do not retag an already-declared different matrix'
    before=digest(video);pixels=rgb_digest(video)
    with tempfile.NamedTemporaryFile(suffix='.mp4',dir=folder,delete=False) as f:temporary=Path(f.name)
    try:
        subprocess.run(['ffmpeg','-v','error','-y','-i',str(video),'-map','0','-c','copy','-bsf:v','h264_metadata=matrix_coefficients=6:video_full_range_flag=0','-colorspace','smpte170m','-color_range','tv','-movflags','+faststart',str(temporary)],check=True)
        after_info=probe(temporary)
        assert after_info.get('color_space')=='smpte170m' and after_info.get('color_range')=='tv'
        assert rgb_digest(temporary)==pixels,'Metadata correction changes decoded pixels; preserve the original'
        backup=folder/'motion-untagged.mp4'
        assert not backup.exists(),'Preserve existing untagged backup'
        video.rename(backup);temporary.rename(video)
    finally:
        if temporary.exists():temporary.unlink()
    report=dict(matrix='BT.601 / SMPTE170M',range='Limited',method='H.264 VUI/MP4 metadata-only stream copy',
        original_sha256=before,corrected_sha256=digest(video),decoded_rgb_sha256=pixels,equal_decoded_rgb=True,
        limits='Declares the existing conversion matrix. No new primaries/transfer calibration, color conversion, optical fit or image sample is introduced.')
    manifest['render_files']['motion.mp4']=report['corrected_sha256'];manifest['video_color']=report
    (folder/'render.json').write_text(json.dumps(manifest,indent=2)+'\n')
    if (folder/'verification.json').exists():
        verification=json.loads((folder/'verification.json').read_text())
        assert verification['files']['motion.mp4']['sha256']==before
        verification['files']['motion.mp4']=dict(bytes=video.stat().st_size,sha256=report['corrected_sha256'])
        verification['render_files']['motion.mp4']=report['corrected_sha256'];verification['video_color']=report
        (folder/'verification.json').write_text(json.dumps(verification,indent=2)+'\n')
    return report


if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('folder',type=Path);a=ap.parse_args();print(json.dumps(correct(a.folder)),flush=True)
