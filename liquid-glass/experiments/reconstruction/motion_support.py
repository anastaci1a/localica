"""Keep diagnostic unknown-source colors out of illustrative motion previews.

Frozen scoring inputs are never edited. Missing motion context is completed by
nearest observed RGB, and travel is capped against the scored source footprint.
Completion is an estimate, not newly recovered evidence.
"""
import cv2,numpy as np
from source_support import coordinates,masks,kernel_safe,fraction,minimum_confidence


def complete(study,known):
    """Presentation-only source extension; never alter frozen input files."""
    missing=known<.999;source=study.source.copy()
    assert (~missing).any(),'No defined source for presentation extension'
    if missing.any():
        _,labels=cv2.distanceTransformWithLabels(missing.astype(np.uint8),cv2.DIST_L2,5,labelType=cv2.DIST_LABEL_PIXEL)
        lookup=np.zeros((int(labels.max())+1,3),np.float32)
        lookup[labels[~missing]]=source[~missing]
        source[missing]=lookup[labels[missing]]
    assert np.array_equal(source[~missing],study.source[~missing])
    return source


def prepare(study,p,known,cov,requested,axis='y'):
    assert axis in ['x','y']
    def shifts(travel):return [(s,0.) if axis=='x' else (0.,s) for s in np.linspace(-travel,travel,33)]
    X,Y=coordinates(study,p)
    known,observed=masks(study,known);safe,_=kernel_safe(known,study,p);observed_safe,_=kernel_safe(observed,study,p)
    from chromatic_transmission import support_rays
    chromatic=support_rays(study,p)
    context=None
    if 'ambient_rim_response' in p or 'material_chroma_sensitivity' in p:
        from material_context import footprint
        context=footprint(study,p)
    def context_fraction(confidence,travel,strict=False):
        X,Y,inside=context
        lookup=minimum_confidence if strict else fraction
        return min(lookup(confidence,X,Y,inside,s) for s in shifts(travel))
    def chromatic_fraction(confidence,scope,travel):
        return min((fraction(confidence,RX.astype(np.float32),RY.astype(np.float32),active&(study.valid if scope=='scored' else cov>0),s)
                    for RX,RY,active in chromatic for s in shifts(travel)
                    if (active&(study.valid if scope=='scored' else cov>0)).any()),default=1.)
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
        return min(fraction(confidence,RX,RY,mask,s) for s in shifts(travel)) if mask.any() else 1.
    actual=0.;minimum=0.
    for scale in [1.,.75,.5,.25,.125,0.]:
        actual=requested*scale
        minimum=min(fraction(safe,X,Y,study.valid,s) for s in shifts(actual))
        if reflection is not None:minimum=min(minimum,reflected_fraction(reflection[4],'scored',actual))
        if chromatic:minimum=min(minimum,chromatic_fraction(safe,'scored',actual))
        if context is not None:minimum=min(minimum,context_fraction(known,actual))
        if minimum>=.999 and (context is None or context_fraction(known,actual,strict=True)>=1-1e-7):break
    full=min(fraction(known,X,Y,cov>0,s) for s in shifts(actual))
    source=complete(study,known)
    report=dict(requested_native_px=requested,actual_native_px=actual,axis=axis,
        minimum_scored_lookup_3sigma_defined_fraction=minimum,
        minimum_whole_shape_lookup_defined_fraction=full,
        minimum_scored_lookup_3sigma_observed_fraction=min(fraction(observed_safe,X,Y,study.valid,s) for s in shifts(actual)),
        minimum_whole_shape_lookup_observed_fraction=min(fraction(observed,X,Y,cov>0,s) for s in shifts(actual)),
        completion='Nearest observed RGB extends unobserved input only for motion; frozen comparisons and scoring inputs are unchanged.',
        limits='Native-grid support tests at33 shifts, not continuous/subpixel or output-scattering confidence. Completed pixels remain estimates.')
    if reflection is not None:
        report.update(reflection_environment_3sigma_defined_min=reflected_fraction(reflection[4],'scored',actual),
                      reflection_environment_3sigma_observed_min=reflected_fraction(reflection[5],'scored',actual),
                      reflection_environment_full_defined_min=reflected_fraction(known,'whole',actual),
                      reflection_environment_full_observed_min=reflected_fraction(observed,'whole',actual))
    if chromatic:
        report.update(chromatic_source_3sigma_defined_min=chromatic_fraction(safe,'scored',actual),
                      chromatic_source_3sigma_observed_min=chromatic_fraction(observed_safe,'scored',actual),
                      chromatic_source_full_defined_min=chromatic_fraction(known,'whole',actual),
                      chromatic_source_full_observed_min=chromatic_fraction(observed,'whole',actual))
    if context is not None:
        report.update(context_mean_source_defined_min=context_fraction(known,actual),
                      context_mean_source_observed_min=context_fraction(observed,actual),
                      context_mean_minimum_defined_confidence=context_fraction(known,actual,strict=True),
                      context_mean_minimum_observed_confidence=context_fraction(observed,actual,strict=True))
    return source,actual,report
