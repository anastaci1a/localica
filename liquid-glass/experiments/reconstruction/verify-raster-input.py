"""Native code values, legacy equality and real high-bit input preservation."""
from pathlib import Path
import argparse,json,tempfile
import cv2,numpy as np
from PIL import Image
from raster_input import read_encoded_rgb
ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--legacy',type=Path,nargs='*',default=[]);ap.add_argument('--high-bit',type=Path);args=ap.parse_args();records=[]
with tempfile.TemporaryDirectory(prefix='localica-rgb-input-') as folder:
    folder=Path(folder)
    for bits in [8,16]:
        raw=(np.arange(17*13*3).reshape(17,13,3)*101+1).astype(np.uint8 if bits==8 else np.uint16)
        path=folder/f'rgb{bits}.png';assert cv2.imwrite(str(path),raw[...,::-1])
        result=read_encoded_rgb(path);maximum=(1<<bits)-1
        assert np.array_equal(np.rint(result*maximum).astype(raw.dtype),raw)
        assert result.flags.c_contiguous
        if bits==8:
            for dtype in [np.float32,np.float64]:assert np.array_equal(read_encoded_rgb(path,dtype),np.asarray(Image.open(path),dtype)/255)
        else:
            assert np.any(abs(result*255-np.rint(result*255))>1e-4),'16-bit precision was lost'
        records.append(dict(fixture_bits=bits,exact_code_value_roundtrip=True))
    for raw in [np.zeros((3,4),np.uint8),np.zeros((3,4,4),np.uint8)]:
        path=folder/'unsupported.png';assert cv2.imwrite(str(path),raw)
        try:read_encoded_rgb(path)
        except ValueError:pass
        else:raise AssertionError('Unspecified gray/alpha conversion accepted')
    try:read_encoded_rgb(folder/'rgb8.png',np.int32)
    except ValueError:pass
    else:raise AssertionError('Integer normalized output accepted')
for path in args.legacy:
    raw=cv2.imread(str(path),cv2.IMREAD_UNCHANGED);assert raw.dtype==np.uint8 and raw.shape[2]==3
    for dtype in [np.float32,np.float64]:assert np.array_equal(read_encoded_rgb(path,dtype),np.asarray(Image.open(path),dtype)/255)
    records.append(dict(file=path.name,legacy_float32_and_float64_exact=True))
if args.high_bit:
    raw=cv2.imread(str(args.high_bit),cv2.IMREAD_UNCHANGED);assert raw.dtype==np.uint16 and raw.shape[2]==3
    expected=raw[...,::-1].astype(np.float64)/65535;actual=read_encoded_rgb(args.high_bit);assert np.array_equal(actual,expected)
    legacy=np.asarray(Image.open(args.high_bit),np.float64)/255
    records.append(dict(file=args.high_bit.name,native_dimensions=[raw.shape[1],raw.shape[0]],decoded_bits=16,new_loader_exact=True,legacy_pillow_dtype=str(np.asarray(Image.open(args.high_bit)).dtype),legacy_normalized_max_difference=float(abs(legacy-expected).max())))
print(json.dumps(dict(passed=True,records=records,limits='Input precision/code values only. No source adoption, optical-score comparison or camera/Apple geometry claim.'),indent=2))
