"""Lossless RGB code-value loading for 8/16-bit research PNG inputs.

No transfer-function, ICC, alpha-compositing or exposure operation is applied.
A 16-bit PNG may carry a decoded 10-bit video; its original encoding metadata
remains separate provenance. The returned values are encoded RGB, not radiance.
"""
from pathlib import Path
import cv2
import numpy as np


def read_encoded_rgb(path,dtype=np.float64):
    dtype=np.dtype(dtype)
    if dtype not in (np.dtype(np.float32),np.dtype(np.float64)):
        raise ValueError('Use float32 or float64 for normalized RGB')
    path=Path(path)
    if not path.is_file():raise ValueError(f'Missing raster input: {path}')
    raw=cv2.imread(str(path),cv2.IMREAD_UNCHANGED)
    if raw is None or raw.ndim!=3 or raw.shape[2]!=3 or raw.dtype not in (np.uint8,np.uint16):
        raise ValueError('Opaque three-channel uint8/uint16 RGB raster required')
    rgb=np.array(raw[...,::-1],dtype=dtype,order='C')
    return rgb/np.iinfo(raw.dtype).max


def pixel_center_scale(scale):
    """Homogeneous map for an isotropic resize of integer pixel centers."""
    scale=float(scale)
    if not np.isfinite(scale) or scale<=0:raise ValueError('Positive finite resize scale required')
    return np.array([[scale,0,(scale-1)/2],[0,scale,(scale-1)/2],[0,0,1]],float)


def rescale_affine(matrix,source_scale,destination_scale):
    """Conjugate an existing registration; do not refit image features."""
    matrix=np.asarray(matrix,float);short=matrix.shape==(2,3)
    if short:matrix=np.vstack([matrix,[0,0,1]])
    if matrix.shape!=(3,3) or not np.isfinite(matrix).all() or not np.array_equal(matrix[2],[0,0,1]):
        raise ValueError('Finite2x3 or homogeneous3x3 affine required')
    result=pixel_center_scale(destination_scale)@matrix@np.linalg.inv(pixel_center_scale(source_scale))
    return result[:2] if short else result
