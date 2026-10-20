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
    # Native-resolution antialiased round tracks. Uncovered portions determine
    # colors/thickness; the hidden blue endpoint remains an explicit estimate.
    scale=4;canvas=np.uint8(np.clip(source,0,1)*255)
    canvas=cv2.resize(canvas,None,fx=scale,fy=scale,interpolation=cv2.INTER_LINEAR)
    for end,color in [(1378,np.median(target[544:556,1000:1250],axis=(0,1))),(719,np.median(target[544:556,580:625],axis=(0,1)))]:
        rgb=tuple(int(round(float(c)*255)) for c in color)
        cv2.line(canvas,(580*scale,550*scale),(end*scale,550*scale),rgb,21*scale,cv2.LINE_AA)
        for x in [580,end]:cv2.circle(canvas,(x*scale,550*scale),int(10.5*scale),rgb,-1,cv2.LINE_AA)
    return cv2.resize(canvas,(1920,1080),interpolation=cv2.INTER_AREA).astype(np.float32)/255,evidence
