"""Cache geometry/material fields for a fixed, unblurred glass shape.
Reevaluate normalized RGB source lookups for every position. No target pixels or fitting.
Returns covered-sample body color; composite coverage separately. Rebuild the
field when geometry/material changes. Caller validates the source domain.
"""
import copy
import cv2,numpy as np
from render import case_geometry,shade
from shapes import oval_field
from settled import mapping,lighting

class ClearField:
 def __init__(self,case,preset,samples=4,width=None):
  self.case=copy.deepcopy(case);self.p=copy.deepcopy(preset);p=self.p
  if p['source_sigma'] or p.get('post_sigma') or p.get('blur_transition'):raise ValueError('ClearField supports unfiltered source/output only')
  if p['model'] not in ['field','potential','tensor','chromatic']:raise ValueError('Unsupported fixed clear-field model')
  self.samples=samples;self.L=min(case['half']);x0,y0,x1,y1=case['crop'];self.W=width or x1-x0;self.H=round((y1-y0)*self.W/(x1-x0));self.sx=self.W/(x1-x0);self.sy=self.H/(y1-y0)
  if self.W==x1-x0 and self.H==y1-y0:
   yy,xx=np.mgrid[y0:y1:1/samples,x0:x1:1/samples];xx=xx-.5+.5/samples;yy=yy-.5+.5/samples
  else:
   yy,xx=np.meshgrid(y0-.5+(np.arange(self.H*samples)+.5)/(samples*self.sy),x0-.5+(np.arange(self.W*samples)+.5)/(samples*self.sx),indexing='ij')
  xx=(xx-case['center'][0])/self.L;yy=(yy-case['center'][1])/self.L;s=p['shape']
  inside=oval_field(np.abs(xx),np.abs(yy),s['half'],s['oval'])[0]<=1 if s.get('oval') else case_geometry(s,xx,yy)[0]<=0
  self.inside=inside;self.active=np.flatnonzero(inside);self.count=inside.reshape(self.H,samples,self.W,samples).sum((1,3));self.coverage=self.count/(samples*samples)
  X0=xx.ravel()[self.active];Y0=yy.ravel()[self.active];del xx,yy
  boundary=case_geometry(s,X0,Y0);X,Y=mapping(X0,Y0,p,boundary=boundary);self.light=lighting(X0,Y0,p,boundary=boundary);self.co=np.array(p['material']);self.query=[]
  for wavelength in [.650,.550,.450]:
   if p['model']=='chromatic':f=1+p['parameters']['dispersion']*(wavelength**-2-.550**-2);qx=X0+(X-X0)*f;qy=Y0+(Y-Y0)*f
   else:qx,qy=X,Y
   self.query.append((qx*self.L,qy*self.L))
 def render(self,source,center=None):
  center=center or self.case['center'];N=len(self.active);pack=1024;rows=(N+pack-1)//pack;colors=[]
  for channel,(qx,qy) in enumerate(self.query):
   mx=np.zeros(rows*pack,np.float32);my=mx.copy();mx[:N]=(qx+center[0]).astype(np.float32);my[:N]=(qy+center[1]).astype(np.float32)
   sampled=cv2.remap(source,mx.reshape(rows,pack),my.reshape(rows,pack),cv2.INTER_LINEAR)
   colors.append(sampled.reshape(-1,3)[:N,channel])
  C=shade(np.stack(colors,axis=-1),self.light,self.co);full=np.zeros((*self.inside.shape,3),float);full.reshape(-1,3)[self.active]=C
  material=np.zeros((self.H,self.W,3),np.float32);s=self.samples
  # Keep the legacy24-output-row reduction layout and summation order.
  for top in range(0,self.H,24):
   bottom=min(top+24,self.H);block=full[top*s:bottom*s].reshape(bottom-top,s,self.W,s,3).sum((1,3));block/=np.maximum(self.count[top:bottom],1)[...,None];material[top:bottom]=block
  return material
