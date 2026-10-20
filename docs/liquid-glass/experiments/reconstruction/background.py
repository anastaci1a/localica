"""Uncovered-background recovery. Fits never sample the target's glass interior."""
import cv2
import numpy as np


def paper(image, excluded):
    """Robust degree-four RGB field from uncovered, text-free page pixels."""
    yy,xx=np.mgrid[:image.shape[0],:image.shape[1]]
    x=(xx-image.shape[1]/2)/(image.shape[1]/2)
    y=(yy-image.shape[0]/2)/(image.shape[0]/2)
    basis=np.stack([x**i*y**j for i in range(5) for j in range(5-i)],axis=-1)
    allowed=(~excluded)&(image.min(2)>.65)&(xx%8==0)&(yy%8==0)
    A=basis[allowed];b=image[allowed];keep=np.ones(len(b),bool)
    for _ in range(6):
        co=np.linalg.lstsq(A[keep],b[keep],rcond=None)[0]
        residual=np.max(np.abs(A@co-b),axis=1)
        median=np.median(residual[keep]);mad=np.median(np.abs(residual[keep]-median))
        keep=residual<median+max(3*mad,1/255)
    field=np.clip(basis@co,0,1).astype(np.float32)
    return field,dict(background_samples=int(keep.sum()),background_mae_255=float(np.abs(A[keep]@co-b[keep]).mean()*255),background_coefficients=co.tolist())


def page_source(registered,target,contaminated):
    excluded=contaminated.copy();excluded[:260]=True
    source_bg,_=paper(registered,excluded)
    excluded=np.zeros(target.shape[:2],bool);excluded[250:1000,380:1540]=True
    target_bg,evidence=paper(target,excluded)
    # Preserve the recovered text, changing its page field rather than inserting
    # a constant-colored rectangular patch. Missing/covered source pixels are blank.
    ink=np.clip(registered/np.maximum(source_bg,1e-4),0,1)
    ink[contaminated]=1
    return target_bg*ink,evidence


def slider_source(target,uncovered):
    excluded=np.zeros(uncovered.shape[:2],bool);excluded[500:610,540:1450]=True
    source,evidence=paper(uncovered,excluded)
    # Exposed blue span is y540..559 (20px), centered at 549.5. The old OpenCV
    # 21px stroke rasterized to a 22px span, inflating width before any optics.
    # Integrate the same continuous capsule used to define the input geometry.
    # The covered blue endpoint is still an explicit estimate, not observed.
    for end,color in [(1377.5,np.median(target[544:556,1000:1250],axis=(0,1))),(718.5,np.median(target[544:556,580:625],axis=(0,1)))]:
        x0,x1=568,int(np.ceil(end+12));y0,y1=537,563
        yy,xx=np.mgrid[y0:y1:.25,x0:x1:.25];xx=xx-.375;yy=yy-.375
        dx=np.maximum(np.maximum(579.5-xx,xx-end),0)
        alpha=(dx*dx+(yy-549.5)**2<=100).reshape(y1-y0,4,x1-x0,4).mean((1,3))[...,None]
        source[y0:y1,x0:x1]=source[y0:y1,x0:x1]*(1-alpha)+color*alpha
    evidence['track_geometry']={'center_y':549.5,'diameter':20.,'start_center_x':579.5,'gray_end_center_x':1377.5,'estimated_blue_end_center_x':718.5,'coverage_subpixels_per_axis':4}
    return source,evidence
