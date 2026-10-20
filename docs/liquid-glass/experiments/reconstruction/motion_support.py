"""Keep diagnostic unknown-source colors out of illustrative motion previews.

Frozen scoring inputs are never edited. Missing motion context is completed by
nearest observed RGB, and travel is capped against the scored source footprint.
Completion is an estimate, not newly recovered evidence.
"""
import cv2,numpy as np
from source_support import coordinates,masks,kernel_safe,fraction


def prepare(study,p,known,cov,requested):
    X,Y=coordinates(study,p)
    known,observed=masks(study,known);safe,_=kernel_safe(known,study,p);observed_safe,_=kernel_safe(observed,study,p)
    reflection=None
    if 'grazing_reflection' in p:
        from grazing_reflection import layers
        F,_=layers(study,p);active=F>1e-8
        x0,y0,x1,y1=study.crop;RY,RX=np.mgrid[y0:y1,x0:x1].astype(np.float32)
        radius=int(np.ceil(3*p['grazing_reflection']['source_sigma']*study.L))
        def safe_reflection(mask):return cv2.erode(mask,np.ones((2*radius+1,2*radius+1),np.uint8),borderType=cv2.BORDER_CONSTANT,borderValue=0) if radius else mask
        reflection=(RX,RY,active&study.valid,active&(cov>0),safe_reflection(known),safe_reflection(observed))
    def reflected_fraction(confidence,scope,travel):
        RX,RY,scored,whole,_,_=reflection;mask=scored if scope=='scored' else whole
        return min(fraction(confidence,RX,RY,mask,s) for s in np.linspace(-travel,travel,33)) if mask.any() else 1.
    actual=0.;minimum=0.
    for scale in [1.,.75,.5,.25,.125,0.]:
        actual=requested*scale
        minimum=min(fraction(safe,X,Y,study.valid,s) for s in np.linspace(-actual,actual,33))
        if reflection is not None:minimum=min(minimum,reflected_fraction(reflection[4],'scored',actual))
        if minimum>=.999:break
    full=min(fraction(known,X,Y,cov>0,s) for s in np.linspace(-actual,actual,33))
    missing=known<.999
    source=study.source.copy()
    if missing.any():
        _,labels=cv2.distanceTransformWithLabels(missing.astype(np.uint8),cv2.DIST_L2,5,labelType=cv2.DIST_LABEL_PIXEL)
        lookup=np.zeros((int(labels.max())+1,3),np.float32)
        lookup[labels[~missing]]=source[~missing]
        source[missing]=lookup[labels[missing]]
    assert np.array_equal(source[~missing],study.source[~missing])
    report=dict(requested_native_px=requested,actual_native_px=actual,
        minimum_scored_lookup_3sigma_defined_fraction=minimum,
        minimum_whole_shape_lookup_defined_fraction=full,
        minimum_scored_lookup_3sigma_observed_fraction=min(fraction(observed_safe,X,Y,study.valid,s) for s in np.linspace(-actual,actual,33)),
        minimum_whole_shape_lookup_observed_fraction=min(fraction(observed,X,Y,cov>0,s) for s in np.linspace(-actual,actual,33)),
        completion='Nearest observed RGB extends unobserved input only for motion; frozen comparisons and scoring inputs are unchanged.',
        limits='Native-grid support tests at33 shifts, not continuous/subpixel or output-scattering confidence. Completed pixels remain estimates.')
    if reflection is not None:
        report.update(reflection_environment_3sigma_defined_min=reflected_fraction(reflection[4],'scored',actual),
                      reflection_environment_3sigma_observed_min=reflected_fraction(reflection[5],'scored',actual),
                      reflection_environment_full_defined_min=reflected_fraction(known,'whole',actual),
                      reflection_environment_full_observed_min=reflected_fraction(observed,'whole',actual))
    return source,actual,report
