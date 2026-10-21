"""Owner-approved preview widths; independent of native scoring resolution."""
import math


def dimensions(width,height,*,large=False,high_quality=False):
    if any(isinstance(v,bool) or not isinstance(v,int) or v<=0 for v in [width,height]):
        raise ValueError('Positive integer source crop dimensions required')
    if high_quality:target=1080
    else:target=min(2*width,max(width,1080 if large else 720))
    return target,max(1,round(height*target/width))
