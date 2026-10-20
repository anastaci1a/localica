"""Focused material reconstruction passes over frozen, recovered inputs.

Inputs stay private. Each invocation performs one named experiment and records
the candidate AND incumbent. A rejected candidate cannot alter accepted output.
"""
from pathlib import Path
from collections import OrderedDict
import argparse, copy, hashlib, json, time
import cv2, numpy as np
from scipy.interpolate import BSpline
from scipy.optimize import minimize, lsq_linear, differential_evolution
from render import geometry, fit_material, shade
from publish import pair

KNOTS = [0]*4+[.015,.04,.09,.2,.45,.75]+[1]*4
STAGES = {
 1: ('Source scattering and base tone', ['sigma']),
 2: ('Normal rim displacement profile', ['amplitude','band','power']),
 3: ('Smooth normal-profile control points', [f'c{i}' for i in range(7)]),
 4: ('Outline-following tangential curvature', ['tangent','tangent_band']),
 5: ('Independent central registration', ['core_x','core_y']),
 6: ('Source versus output-space scattering', ['sigma','post_sigma']),
 7: ('Normal/tangential gradient coupling', ['coupling','tangent','tangent_band']),
 8: ('Monotone input brightness response', ['gamma','sigma']),
 9: ('Directional thin-rim and broad lighting', ['rim_width','thin_width']),
 10: ('Joint optical and scattering settlement', ['sigma','post_sigma','tangent','tangent_band','core_x','core_y','gamma']),
 11: ('Measured silhouette and optical-boundary alignment', ['shape_dx','shape_dy','half_x_delta','half_y_delta','radius_delta']),
 13: ('Distance-dependent source scattering', ['sigma','blur_transition']),
 14: ('Rim-profile and scattering co-refinement', [*[f'c{i}' for i in range(7)],'sigma','blur_transition']),
 15: ('Continuous tangential-falloff curvature', ['tangent_exponent','tangent','tangent_band']),
 16: ('Outline-orientation normal and tangential power', ['cap_normal_gain','cap_tangent_fade','sigma']),
 17: ('Coupled cap and normal-profile refinement', [*[f'c{i}' for i in range(7)],'cap_normal_gain','cap_tangent_fade']),
 18: ('C2 cap-to-body curvature join', ['cap_join_width']),
 19: ('Smooth-cap optical settlement', [*[f'c{i}' for i in range(7)],'sigma','blur_transition','cap_normal_gain']),
 20: ('Joint smooth-cap profile and output-scattering settlement', [*[f'c{i}' for i in range(7)],'sigma','post_sigma','core_x','core_y','gamma']),
}
BOUNDS = dict(sigma=(0,32), amplitude=(0,2), band=(.03,1), power=(1.2,5),
              tangent=(0,.45), tangent_band=(.03,1), core_x=(0,.025),core_y=(0,.18),
              post_sigma=(0,3), coupling=(0,1),gamma=(.65,1.55),
              rim_width=(.008,.30), thin_width=(.003,.05),
              shape_dx=(-.05,.05),shape_dy=(-.05,.05),half_x_delta=(-.05,.05),half_y_delta=(-.05,.05),radius_delta=(-.05,.05),
              blur_transition=(0,1),tangent_exponent=(3,8),
              cap_normal_gain=(-.8,1.8),cap_tangent_fade=(0,1),
              cap_join_width=(0,.35),
              **{f'c{i}':(0,2.5) for i in range(7)})

# Circular controls have no preferred optical axis. Lighting may still be
# directional, but a radial field cannot gain an arbitrary axis to fit noise.
RADIAL_STAGES = {
 4: ('Radial source versus output-space scattering', ['sigma','post_sigma']),
 5: ('Isotropic central registration', ['core_x']),
 6: ('Circular thin-rim and broad lighting', ['rim_width','thin_width']),
 7: ('Radial-profile and central-power refinement', [*[f'c{i}' for i in range(7)],'core_x']),
 10: ('Joint radial optical and scattering settlement', ['sigma','post_sigma','core_x','gamma','rim_width','thin_width']),
 11: ('Circular silhouette and optical-boundary alignment', ['shape_dx','shape_dy','half_x_delta']),
 13: ('Global signed radial-profile restart', ['amplitude','band','power','sigma','core_x']),
 14: ('Signed radial spline and source-scattering refinement', [*[f'c{i}' for i in range(7)],'sigma','core_x']),
 15: ('Radial output-scattering and brightness refinement', ['sigma','post_sigma','gamma','rim_width','thin_width']),
 16: ('Seeded radial-spline neighborhood search', [*[f'c{i}' for i in range(7)],'sigma']),
 17: ('Radial rim-scattering transition', ['sigma','blur_transition']),
 18: ('Radial spline and rim-scattering settlement', [*[f'c{i}' for i in range(7)],'sigma','blur_transition']),
 19: ('Radial shoulder and output-scattering refinement', ['c2','c3','c4','sigma','post_sigma']),
 20: ('Joint radial spline, scattering and source response', [*[f'c{i}' for i in range(7)],'sigma','post_sigma','core_x','gamma']),
}

# Follow-ups start from a fitted spline: amplitude/band/power are inactive in
# that state and must not be counted as new optical refinement experiments.
RADIAL_FOLLOWUP_STAGES = {
 1: ('Follow-up source scattering', ['sigma']),
 2: ('Follow-up outer radial spline', ['c0','c1','c2']),
 3: ('Follow-up inner radial spline', ['c3','c4','c5','c6']),
 4: ('Follow-up output-space scattering', ['post_sigma']),
 5: ('Follow-up isotropic center magnification', ['core_x']),
 6: ('Follow-up source/output scattering separation', ['sigma','post_sigma']),
 7: ('Follow-up broad and thin rim lighting', ['rim_width','thin_width']),
 8: ('Follow-up monotone source response', ['gamma','sigma']),
 9: ('Follow-up circular silhouette registration', ['shape_dx','shape_dy','half_x_delta']),
 10: ('Follow-up joint spline and scattering', [*[f'c{i}' for i in range(7)],'sigma','post_sigma','core_x']),
}
FOLLOWUP_STAGES = {
 **RADIAL_FOLLOWUP_STAGES,
 2: ('Follow-up outer normal spline', ['c0','c1','c2']),
 3: ('Follow-up inner normal spline', ['c3','c4','c5','c6']),
 4: ('Follow-up tangential curvature', ['tangent','tangent_band']),
 5: ('Follow-up central x/y magnification', ['core_x','core_y']),
 7: ('Follow-up coupled normal/tangential response', ['coupling','tangent','tangent_band']),
 9: ('Follow-up outline registration with fixed exterior mask', ['shape_dx','shape_dy','half_x_delta','half_y_delta','radius_delta']),
 10: ('Follow-up joint optical and scattering settlement', ['sigma','post_sigma','tangent','tangent_band','core_x','core_y','gamma']),
}


def initial():
    p=dict(sigma=0.,amplitude=0.,band=.35,power=2.2,tangent=0.,tangent_band=.4,
           core_x=0.,core_y=0.,post_sigma=0.,coupling=0.,gamma=1.,
           rim_width=.07,thin_width=.015,spline=False)
    p.update(shape_dx=0.,shape_dy=0.,half_x_delta=0.,half_y_delta=0.,radius_delta=0.)
    p.update({f'c{i}':0. for i in range(7)})
    return p


class Study:
    def __init__(self, folder):
        self.folder=Path(folder)
        self.case=json.loads((self.folder/'case.json').read_text())
        for name,digest in self.case.get('source_sha256',{}).items():
            with (self.folder/name).open('rb') as f:
                assert hashlib.file_digest(f,'sha256').hexdigest()==digest, f'Changed frozen input: {name}'
        self.source=cv2.imread(str(self.folder/'source.png'))[:,:,::-1].astype(np.float32)/255
        self.target=cv2.imread(str(self.folder/'target.png'))[:,:,::-1].astype(np.float32)/255
        known=cv2.imread(str(self.folder/'known.png'),cv2.IMREAD_GRAYSCALE)>0
        self.L=min(self.case['half'])
        self.half=np.array(self.case['half'])/self.L
        self.radius=self.case['radius']/self.L
        self.crop=self.case['crop'];x0,y0,x1,y1=self.crop
        Y,X=np.mgrid[y0:y1,x0:x1].astype(float)
        self.x=(X-self.case['center'][0])/self.L
        self.y=(Y-self.case['center'][1])/self.L
        sd,*_=geometry(self.x,self.y,self.half,self.radius)
        valid=(sd < -1/self.L)&known[y0:y1,x0:x1]
        for a,b,c,d in self.case['foreground']:
            valid&=~((X>=a)&(X<=c)&(Y>=b)&(Y<=d))
        self.valid=valid;self.sd=sd;self.rim=valid&(-sd<.2)
        self.held=valid&((X//32+3*(Y//32))%4==0)
        train=np.flatnonzero(valid&~self.held)
        np.random.default_rng(222).shuffle(train)
        self.indices=train[:5000]
        self.weights=np.where(-sd.ravel()[self.indices]<.05,2.,np.where(-sd.ravel()[self.indices]<.2,1.3,1.))
        self.cache=OrderedDict()
        self.backdrop_shift=[0.,0.]
        self.target_crop=self.target[y0:y1,x0:x1]
        self.expected=self.target_crop.reshape(-1,3)[self.indices]
        self.foreground_layers=[];self.transmission_layers=[];self.vector_layers={};self.artwork_layers={}
        for layer in self.case.get('foreground_layers',[]):
            a,b,c,d=layer['box'];T=self.target[b:d,a:c].copy()
            vector=None
            if 'vector_asset' in layer:
                from vector_foreground import coverage
                vector=coverage(self.folder,layer)
                self.vector_layers[tuple(layer['box'])]=layer
            if layer['mode']=='transmission':
                seed=vector>.995 if vector is not None else (T[:,:,0]-T[:,:,1]>.25)&(T[:,:,0]-T[:,:,2]>.15)
                self.transmission_layers.append(dict(box=layer['box'],target=T,seed=seed,fit=None))
                continue
            if vector is not None:
                self.foreground_layers.append((layer['box'],vector,np.broadcast_to(np.asarray(layer['color']),T.shape)))
                continue
            if layer['mode']=='artwork':
                # Reference cover artwork is content, not a glass shader.
                yy,xx=np.mgrid[:d-b,:c-a]
                sd,*_=geometry(xx-(c-a-1)/2,yy-(d-b-1)/2,[(c-a)/2,(d-b)/2],layer.get('radius',16))
                alpha=np.clip(.5-sd,0,1);color=T
                if 'artwork_asset' in layer:
                    asset=self.folder/layer['artwork_asset']
                    assert hashlib.sha256(asset.read_bytes()).hexdigest()==layer['artwork_sha256'],'Changed frozen artwork input'
                    original=cv2.imread(str(asset))[:,:,::-1].astype(np.float32)/255
                    self.artwork_layers[tuple(layer['box'])]=(original,layer.get('radius',16))
                    color=cv2.resize(original,(c-a,d-b),interpolation=cv2.INTER_AREA)
            else:
                fill=np.asarray(layer['color'])
                seed=(T.max(2)<layer['threshold'])
                if layer.get('local_contrast'):
                    lum=T.mean(2).astype(np.float32)
                    closed=cv2.morphologyEx(lum,cv2.MORPH_CLOSE,np.ones((11,11),np.uint8))
                    seed&=(closed-lum>layer['local_contrast'])
                if layer.get('neutral_only'):
                    seed&=(T.max(2)-T.min(2)<.035)
                stencil=cv2.dilate(seed.astype(np.uint8),np.ones((3,3),np.uint8))
                # Recover only the local backdrop beneath the glyph for matte
                # estimation; never feed this inpaint into the glass score.
                bg=cv2.inpaint(np.uint8(np.rint(T*255)),stencil*255,5,cv2.INPAINT_TELEA).astype(float)/255
                delta=bg-fill
                alpha=np.clip(np.sum((bg-T)*delta,2)/np.maximum(np.sum(delta*delta,2),1e-9),0,1)*stencil
                color=np.broadcast_to(fill,T.shape)
            self.foreground_layers.append((layer['box'],alpha,color))

    def blur(self,sigma):
        # Quarter-native-pixel source-kernel bins are explicit implementation
        # resolution. No fixed image-space bin enters the mathematical recipe.
        key=round(sigma*4)/4
        if key not in self.cache:
            self.cache[key]=cv2.GaussianBlur(self.source,(0,0),key) if key else self.source
        self.cache.move_to_end(key)
        while len(self.cache)>8:self.cache.popitem(last=False)
        return self.cache[key]

    def shape(self,x,y,p):
        x=x-p.get('shape_dx',0);y=y-p.get('shape_dy',0)
        radial=self.case.get('radial',False)
        dy=p.get('half_x_delta',0) if radial else p.get('half_y_delta',0)
        half=self.half+[p.get('half_x_delta',0),dy]
        radius=min(self.radius+p.get('radius_delta',0),min(half))
        if radial:radius=min(half)
        join=p.get('cap_join_width',0)
        if join>1e-8 and abs(half[0]-half[1])>join:
            from curvature import capsule_offset
            # An explicit C2 implicit footprint, with its analytic normal.
            # Its radial residual is a boundary coordinate, NOT an exact
            # Euclidean signed distance within the small smoothed join.
            horizontal=half[0]>half[1]
            major,minor=(x,y) if horizontal else (y,x)
            R=min(half);a=max(half)-R
            q,qd=capsule_offset(np.abs(major)-a,join)
            r=np.hypot(q,minor)
            na=q*qd*np.sign(major);nb=minor;size=np.maximum(np.hypot(na,nb),1e-12)
            nx,ny=(na/size,nb/size) if horizontal else (nb/size,na/size)
            return x,y,half,R,(r-R,nx,ny)
        return x,y,half,radius,geometry(x,y,half,radius)

    def geometry_field(self,x,y,p):
        x,y,half,radius,(sd,nx,ny)=self.shape(x,y,p);d=np.maximum(-sd,0)
        if p['spline']:
            basis=BSpline.design_matrix(np.clip(d/radius,0,1).ravel(),KNOTS,3).toarray()[:,:7].reshape((*x.shape,7))
            a=basis@np.array([p[f'c{i}'] for i in range(7)])
        else:
            a=p['amplitude']*np.maximum(1-d/p['band'],0)**p['power']
        # A shape-relative cap weight distinguishes normal orientation along
        # the long axis. It is continuous at the straight/cap join; no image
        # feature coordinates or painted spatial corrections enter the map.
        cap_weight=nx*nx if half[0]>=half[1] else ny*ny
        a*=1+p.get('cap_normal_gain',0)*cap_weight
        u=np.clip(d/p['tangent_band'],0,1);v=1-u
        exponent=p.get('tangent_exponent',4)
        W=v**exponent*(1+exponent*u);Wd=-exponent*(exponent+1)*u*v**(exponent-1)/p['tangent_band']
        W*=1-p.get('cap_tangent_fade',0)*cap_weight
        tangent_pos=-ny*x+nx*y
        radial=self.case.get('radial',False)
        core_y=p['core_x'] if radial else p['core_y']
        X=(1-p['core_x'])*x-a*nx+p['tangent']*W*tangent_pos*ny
        Y=(1-core_y)*y-a*ny-p['tangent']*W*tangent_pos*nx
        # A normal-coupling comparator inspired by a gradient potential. The
        # full tensor field (especially with orientation weights) is empirical;
        # adding this term does not prove integrability or a physical solid.
        coupling=.5*p['coupling']*p['tangent']*Wd*x*x
        X-=coupling*nx;Y-=coupling*ny
        X+=p.get('shape_dx',0);Y+=p.get('shape_dy',0)
        thin=np.exp(-(d/p['thin_width'])**2)
        e=np.exp(-(d/p['rim_width'])**2);f=np.exp(-(d/(4*p['rim_width']))**2)
        xx=x/half[0];yy=y/half[1];z=np.clip(d/.3,0,1);core=z**3*(z*(6*z-15)+10)
        light=np.stack([thin,thin*nx,thin*ny,e,e*nx,e*ny,f,f*nx,f*ny,xx,yy,xx*xx,yy*yy,xx*yy,core],axis=-1)
        return X,Y,d,light

    def field(self,x,y,p):
        X,Y,d,light=self.geometry_field(x,y,p)
        texture=self.blur(p['sigma'])
        sample=cv2.remap(texture,(X*self.L+self.case['center'][0]+self.backdrop_shift[0]).astype(np.float32),
            (Y*self.L+self.case['center'][1]+self.backdrop_shift[1]).astype(np.float32),cv2.INTER_LINEAR)
        sample=sample.reshape((*x.shape,3))
        if p.get('blur_transition',0)>1e-8:
            clear=cv2.remap(self.source,(X*self.L+self.case['center'][0]+self.backdrop_shift[0]).astype(np.float32),
                (Y*self.L+self.case['center'][1]+self.backdrop_shift[1]).astype(np.float32),cv2.INTER_LINEAR).reshape((*x.shape,3))
            u=np.clip(d/p['blur_transition'],0,1);blend=u**3*(u*(6*u-15)+10)
            sample=clear+(sample-clear)*blend[...,None]
        return np.maximum(sample,0)**p['gamma'],light

    def fit(self,p):
        x=self.x.ravel()[self.indices];y=self.y.ravel()[self.indices]
        if p['post_sigma']>.001:
            # Four equally weighted offsets per axis realize weights1:2:1.
            # Average linear design products before fitting, not RGB/light
            # separately. Final scoring uses a native Gaussian, not this probe.
            h=np.sqrt(2)*p['post_sigma']/self.L
            dx,dy=np.meshgrid([-h,0,0,h],[-h,0,0,h])
            sample,light=self.field(x[:,None]+dx.ravel(),y[:,None]+dy.ravel(),p)
        else:
            sample,light=self.field(x,y,p)
        co,_=fit_material(sample,light,self.expected,self.weights)
        pred=shade(sample,light,co)
        if pred.ndim==3:pred=pred.mean(1)
        loss=float(np.mean(((pred-self.expected)*self.weights[:,None])**2))
        return loss,co

    def raster_tiles(self,scale=1):
        assert isinstance(scale,int) and scale>=1
        x0,y0,x1,y1=self.crop
        H,W=(y1-y0)*scale,(x1-x0)*scale
        # Re-evaluate the normalized field at two subpixels per OUTPUT axis.
        # Input coordinates and source scattering remain in reference units;
        # only the output kernel scales with the presentation resolution.
        offset=.5-.25/scale
        # Bound spline/lighting temporaries for wide high-resolution exports.
        # A strip still contains exactly the same four samples per output pixel.
        rows=min(24*scale,max(1,131072//(4*W)))
        for row in range(0,H,rows):
            bottom=min(row+rows,H)
            yy,xx=np.mgrid[y0+row/scale:y0+bottom/scale:.5/scale,x0:x1:.5/scale]
            x=(xx-offset-self.case['center'][0])/self.L;y=(yy-offset-self.case['center'][1])/self.L
            yield row,bottom,W,x,y

    def render(self,p,co,scale=1):
        x0,y0,x1,y1=self.crop
        result=np.empty(((y1-y0)*scale,(x1-x0)*scale,3),np.float32)
        for row,bottom,W,x,y in self.raster_tiles(scale):
            C,J=self.field(x,y,p);C=shade(C,J,co)
            result[row:bottom]=C.reshape(bottom-row,2,W,2,3).mean((1,3))
        if p['post_sigma']>.001:result=cv2.GaussianBlur(result,(0,0),p['post_sigma']*scale)
        return result

    def shape_coverage(self,p,scale=1):
        x0,y0,x1,y1=self.crop
        result=np.empty(((y1-y0)*scale,(x1-x0)*scale),np.float32)
        for row,bottom,W,x,y in self.raster_tiles(scale):
            *_,(sd,_,_)=self.shape(x,y,p)
            result[row:bottom]=(sd<=0).reshape(bottom-row,2,W,2).mean((1,3))
        return result

    def scores(self,pred,include_ssim=True):
        error=(pred-self.target_crop)*255
        result={}
        for name,mask in [('glass',self.valid),('rim',self.rim),('held',self.held),('interior',self.valid&~self.rim)]:
            result[name]=dict(pixels=int(mask.sum()),rmse=float(np.sqrt(np.mean(error[mask]**2))))
        if include_ssim:
            A=pred.mean(2)*255;B=self.target_crop.mean(2)*255
            mean=lambda q:cv2.GaussianBlur(q,(11,11),1.5)
            a,b=mean(A),mean(B)
            ss=((2*a*b+6.5025)*(2*(mean(A*B)-a*b)+58.5225))/((a*a+b*b+6.5025)*(mean(A*A)-a*a+mean(B*B)-b*b+58.5225))
            safe=cv2.erode(self.valid.astype(np.uint8),np.ones((11,11),np.uint8))>0
            result['ssim']=float(ss[safe].mean())
        # Frozen overall score: weighted squared native RGB and rim mismatch.
        result['loss']=.7*result['glass']['rmse']**2+.3*result['rim']['rmse']**2
        return result

    def comparison(self,pred,dest,p,scale=1):
        x0,y0,x1,y1=self.crop
        H,W=(y1-y0)*scale,(x1-x0)*scale;offset=.5-.25/scale
        original=np.uint8(np.rint(self.target_crop*255))
        if scale>1:original=cv2.resize(original,(W,H),interpolation=cv2.INTER_LANCZOS4)
        cov=self.shape_coverage(p,scale)
        context=self.target_crop.copy() if scale==1 else original.astype(np.float32)/255
        yy,xx=np.mgrid[:H,:W].astype(np.float32)
        px=x0-.5+(xx+.5)/scale;py=y0-.5+(yy+.5)/scale
        x=(px-self.case['center'][0])/self.L;y=(py-self.case['center'][1])/self.L
        if 'shadow' in p:
            q=p['shadow'];*_,(shadow_sd,_,_)=self.shape(x-q['offset'][0],y-q['offset'][1],p)
            opacity=q['opacity']*np.exp(-.5*(np.maximum(shadow_sd,0)/q['sigma'])**2)
            source=cv2.remap(self.source,px,py,cv2.INTER_LINEAR)*(1-opacity[...,None])
            *_,(native_sd,_,_)=self.shape(x,y,p)
            u=np.clip((native_sd-.20)/.15,0,1);blend=u**3*(u*(6*u-15)+10)
            known=cv2.imread(str(self.folder/'known.png'),cv2.IMREAD_GRAYSCALE)
            known=cv2.remap(known,px,py,cv2.INTER_NEAREST)>0
            context=np.where(known[...,None],source*(1-blend[...,None])+context*blend[...,None],context)
        out=pred*cov[...,None]+context*(1-cov[...,None])
        out=self.apply_foreground(out,scale)
        labels=('Apple source','Reconstruction (4x)') if scale==4 else ('Apple','Reconstruction')
        pair(original,np.uint8(np.rint(np.clip(out,0,1)*255)),labels,font_size=16*scale).save(dest)

    def apply_foreground(self,out,scale=1,stop_before=None):
        x0,y0,_,_=self.crop
        for box,alpha,color in self.foreground_layers:
            if stop_before is not None and tuple(box)==tuple(stop_before):return out
            a,b,c,d=box
            region=out[(b-y0)*scale:(d-y0)*scale,(a-x0)*scale:(c-x0)*scale]
            vector=self.vector_layers.get(tuple(box))
            style=self.case.get('foreground_styles',{}).get(vector.get('style_key',vector['vector_asset']),{}) if vector else {}
            if 'color' in style:color=np.broadcast_to(np.asarray(style['color'],np.float32),(*alpha.shape,3))
            if scale>1:
                size=((c-a)*scale,(d-b)*scale)
                artwork=self.artwork_layers.get(tuple(box))
                if artwork is not None:
                    yy,xx=np.mgrid[:size[1],:size[0]]
                    sd,*_=geometry((xx+.5)/scale-(c-a)/2,(yy+.5)/scale-(d-b)/2,[(c-a)/2,(d-b)/2],artwork[1])
                    alpha=np.clip(.5-sd*scale,0,1)
                elif tuple(box) not in self.vector_layers:alpha=np.clip(cv2.resize(alpha,size,interpolation=cv2.INTER_CUBIC),0,1)
                source=artwork[0] if artwork is not None else np.asarray(color,dtype=np.float32)
                color=np.clip(cv2.resize(source,size,interpolation=cv2.INTER_AREA if artwork is not None else cv2.INTER_CUBIC),0,1)
            if tuple(box) in self.vector_layers:
                from vector_foreground import coverage
                vector=self.vector_layers[tuple(box)]
                alpha=coverage(self.folder,vector,scale)*style.get('opacity',vector.get('opacity',1.))
                if 'response' in style:
                    from foreground_response import response
                    alpha=coverage(self.folder,vector,scale)
                    color=response(region,style['response'])
            region[:]=region*(1-alpha[...,None])+color*alpha[...,None]
        for layer in self.transmission_layers:
            from foreground_response import calibrate,response
            a,b,c,d=layer['box'];region=out[(b-y0)*scale:(d-y0)*scale,(a-x0)*scale:(c-x0)*scale]
            if layer['fit'] is None:
                background=region if scale==1 else cv2.resize(region,(c-a,d-b),interpolation=cv2.INTER_AREA)
                layer['fit']=calibrate(background,layer['target'],layer['seed'],self.case.get('foreground_response'))
            params,alpha,_=layer['fit']
            if tuple(layer['box']) in self.vector_layers:
                from vector_foreground import coverage
                alpha=coverage(self.folder,self.vector_layers[tuple(layer['box'])],scale)
            elif scale>1:alpha=np.clip(cv2.resize(alpha,((c-a)*scale,(d-b)*scale),interpolation=cv2.INTER_CUBIC),0,1)
            region[:]=region*(1-alpha[...,None])+response(region,params)*alpha[...,None]
        return out


def main():
    ap=argparse.ArgumentParser();ap.add_argument('folder',type=Path);ap.add_argument('--pass',dest='number',type=int,required=True);ap.add_argument('--budget',type=int,default=900);args=ap.parse_args()
    assert not (args.folder/f'pass-{args.number:02}'/'record.json').exists(), 'Keep completed passes immutable'
    t0=time.time();study=Study(args.folder)
    stages={**STAGES,**RADIAL_STAGES} if study.case.get('radial') else STAGES
    if study.case.get('continuation'):stages.update(RADIAL_FOLLOWUP_STAGES if study.case.get('radial') else FOLLOWUP_STAGES)
    stage,keys=stages[args.number]
    accepted_path=args.folder/'accepted.json'
    if accepted_path.exists():old=json.loads(accepted_path.read_text());p=old['parameters']
    else:
        p=initial();_,co=study.fit(p);pred=study.render(p,co)
        old=dict(parameters=p,material=co.tolist(),scores=study.scores(pred));accepted_path.write_text(json.dumps(old,indent=2)+'\n')
    start=copy.deepcopy(p)
    global_radial=study.case.get('radial') and args.number==13
    if global_radial:
        assert study.case.get('radial'), 'Radial comparator only'
        start.update(spline=False,post_sigma=0.,amplitude=.5,band=.6,power=2.2,sigma=2.)
    if args.number in (3,14) and not start['spline']:
        d=np.linspace(0,1,501)
        basis=BSpline.design_matrix(d,KNOTS,3).toarray()[:,:7]
        a=start['amplitude']*np.maximum(1-d/start['band'],0)**start['power']
        c=lsq_linear(basis,a,bounds=(-2.5,2.5) if study.case.get('radial') else (0,2.5)).x
        start.update({f'c{i}':float(c[i]) for i in range(7)});start['spline']=True
    evaluations=0;best=[float('inf'),None,None]
    def objective(v):
        nonlocal evaluations
        candidate=copy.deepcopy(start);candidate.update(dict(zip(keys,map(float,v))))
        loss,co=study.fit(candidate);evaluations+=1
        if loss<best[0]:best[:]=[loss,candidate,co]
        if evaluations%250==0:
            partial=dict(pass_number=args.number,evaluations=evaluations,training_loss=best[0],parameters=best[1],material=best[2].tolist(),seconds=time.time()-t0)
            (args.folder/f'running-{args.number:02}.json').write_text(json.dumps(partial,indent=2)+'\n')
            print(json.dumps({k:partial[k] for k in ['pass_number','evaluations','training_loss','seconds']}),flush=True)
        return loss
    x=np.array([start.get(k,4. if k=='tangent_exponent' else 0.) for k in keys]);bounds=[BOUNDS[k] for k in keys]
    if study.case.get('radial'):
        bounds=[(-2.5,2.5) if k.startswith('c') and k[1:].isdigit() else b for k,b in zip(keys,bounds)]
    # Powell handles the explicit finite bilinear-sampling resolution without
    # claiming infinitesimal derivatives through a quantized texture lookup.
    if study.case.get('radial') and args.number==16:
        bounds=[(max(b[0],v-.2),min(b[1],v+.2)) if k!='sigma' else (max(0,v-2),min(32,v+2)) for k,v,b in zip(keys,x,bounds)]
        result=differential_evolution(objective,bounds,x0=x,seed=222,popsize=8,
            maxiter=max(1,args.budget//(8*len(keys))-1),tol=1e-8,polish=False)
    elif global_radial:
        # Test both signed displacement families rather than remaining in the
        # high-blur local minimum produced by an identity-map initialization.
        bounds=[(-2,2),(.03,1.5),(1.2,5),(0,16),(0,.15)]
        result=differential_evolution(objective,bounds,seed=222,popsize=8,
            maxiter=max(1,args.budget//40-1),tol=1e-8,polish=False)
    else:
        result=minimize(objective,x,method='Powell',bounds=bounds,
            options=dict(maxfev=args.budget,maxiter=15,xtol=.0005,ftol=1e-7))
    candidate,co=best[1:]
    pred=study.render(candidate,co);candidate_scores=study.scores(pred)
    if 'exterior' in old['scores'] and args.number!=11:
        candidate_scores['exterior']=old['scores']['exterior']
    changes_outline=bool(set(keys)&{'shape_dx','shape_dy','half_x_delta','half_y_delta','radius_delta','cap_join_width'})
    exterior_check='shadow' in candidate and (args.number==18 or changes_outline)
    if exterior_check and 'exterior' in old['scores']:
        from extended_shadow import refit_fixed_region
        reference=study.case.get('exterior_reference_shape')
        if reference is None:reference=json.loads((args.folder/'pass-12'/'record.json').read_text())['current']['parameters']
        candidate,exterior=refit_fixed_region(study,candidate,reference)
        candidate_scores['exterior']=exterior
    advance=(candidate_scores['loss']<=old['scores']['loss']+1e-9 and
             candidate_scores['glass']['rmse']<=old['scores']['glass']['rmse']+1e-9)
    if exterior_check and 'exterior' in old['scores']:
        advance=advance and candidate_scores['exterior']['rmse']<=old['scores']['exterior']['rmse']+1e-8
    current=dict(parameters=candidate,material=co.tolist(),scores=candidate_scores) if advance else old
    record=dict(pass_number=args.number,experiment=stage,keys=keys,evaluations=evaluations,
        seconds=time.time()-t0,candidate=dict(parameters=candidate,material=co.tolist(),scores=candidate_scores),
        accepted=bool(advance),incumbent_scores=old['scores'],current=current,
        gate='Frozen overall weighted MSE and glass RMSE cannot regress. Rejected trials retain incumbent.',
        limits='Same-frame calibration; source recovery/coverage assumptions are fixed, not independent validation.')
    dest=args.folder/f'pass-{args.number:02}';dest.mkdir(exist_ok=True)
    (dest/'record.json').write_text(json.dumps(record,indent=2)+'\n')
    study.comparison(pred,dest/'candidate.png',candidate)
    kept=study.render(current['parameters'],np.array(current['material']))
    study.comparison(kept,dest/'accepted.png',current['parameters'])
    np.save(dest/'material.npy',kept)
    accepted_path.write_text(json.dumps(current,indent=2)+'\n')
    print(json.dumps(dict(pass_number=args.number,experiment=stage,accepted=advance,
        incumbent=old['scores']['loss'],candidate=candidate_scores['loss'],current=current['scores'],evaluations=evaluations,seconds=time.time()-t0)),flush=True)


if __name__=='__main__':main()
