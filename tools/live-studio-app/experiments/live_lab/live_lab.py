"""Experimental desktop renderer; not a native Live Photo packer."""
import math
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time

import cv2
import numpy as np

MODEL = 'cogvideox-flash'
# Different paths, phases, envelopes and directions; values are fractions of cell size.
TRACKS = ((.052, .00, 'push', -.35, .12), (.042, .13, 'left', .68, -.10),
          (.046, .08, 'pull', -.22, .25), (.036, .19, 'right', -.72, -.13))


def transform(index, t):
    strength, delay, mode, sx, sy = TRACKS[index % len(TRACKS)]
    p = min(1., max(0., (t-delay)/(1-delay)))
    ease = (1-math.cos(math.pi*p))/2
    if mode == 'push':
        delta = strength*(1-(1-p)**3)
    elif mode == 'pull':
        # Start aligned with cover, gently push for a moment, then pull back.
        delta = strength*(1-(1-min(p/.30, 1))**2) if p < .30 else strength*(1-.85*(1-math.cos(math.pi*(p-.30)/.70))/2)
    else:
        delta = strength*min(1., p/.32)**2*(3-2*min(1., p/.32))
    drift = ease if mode != 'pull' else math.sin(math.pi*p)
    x = delta*.5*(sx*drift + .08*math.sin((3.4+index*.7)*math.pi*p))
    y = delta*.5*(sy*drift + .05*math.sin((4.1+index*.9)*math.pi*p))
    return 1+delta, x, y


def boxes(w, h, rows, cols):
    if rows*cols not in (2,4) or min(rows,cols)<1 or min(w//cols,h//rows)<32:
        raise ValueError('Only confirmed 1x2, 2x1 or 2x2 grids with adequate resolution')
    return [(c*w//cols,r*h//rows,(c+1)*w//cols,(r+1)*h//rows)
            for r in range(rows) for c in range(cols)]


def prepare(img, rows, cols, mask=None, confirmed_no_title=False):
    if img is None or img.ndim != 3 or img.shape[2] != 3:
        raise ValueError('Expected decoded BGR image')
    h,w = img.shape[:2]
    regions = boxes(w,h,rows,cols)
    if mask is None and not confirmed_no_title:
        raise ValueError('Confirm no overlay or supply a manually verified title mask')
    if mask is not None and (mask.shape != (h,w) or not np.any(mask>127)):
        raise ValueError('Mask must be nonempty and match image dimensions')
    clean = img.copy()
    if mask is not None:
        mask = (mask>127).astype(np.uint8)*255
        # Repair per cell; never use adjacent cell pixels across seams.
        kernel = np.ones((5,5),np.uint8)
        for x0,y0,x1,y1 in regions:
            m = cv2.dilate(mask[y0:y1,x0:x1],kernel)
            if np.count_nonzero(m) > m.size*.15:
                raise ValueError('Title area too large for conservative repair')
            if m.any():
                clean[y0:y1,x0:x1] = cv2.inpaint(img[y0:y1,x0:x1],m,3,cv2.INPAINT_TELEA)
    return regions, clean, mask


def frame(img, clean, regions, mask, t):
    if t <= 0:
        return img.copy()
    out = np.empty_like(img)
    for i,(x0,y0,x1,y1) in enumerate(regions):
        tile = clean[y0:y1,x0:x1]
        h,w = tile.shape[:2]
        z,dx,dy = transform(i,t)
        m = np.float32([[z,0,(1-z)*(w-1)/2+dx*(w-1)], [0,z,(1-z)*(h-1)/2+dy*(h-1)]])
        out[y0:y1,x0:x1] = cv2.warpAffine(tile,m,(w,h),flags=cv2.INTER_CUBIC,borderMode=cv2.BORDER_REPLICATE)
    if mask is not None:
        out[mask>0] = img[mask>0]
    return out


def render(image, output, rows=2, cols=2, mask_path=None, confirmed_no_title=False, duration=2., fps=30):
    image, output = Path(image), Path(output)
    if output.exists() or output.resolve()==image.resolve():
        raise FileExistsError(output)
    if not 1 <= duration <= 3.5 or fps not in (24,30):
        raise ValueError('Unsupported duration/fps')
    img = cv2.imdecode(np.fromfile(image,dtype=np.uint8),cv2.IMREAD_COLOR)
    mask = cv2.imdecode(np.fromfile(mask_path,dtype=np.uint8),cv2.IMREAD_GRAYSCALE) if mask_path else None
    if mask_path and mask is None:
        raise ValueError('Could not decode title mask')
    regions, clean, mask = prepare(img,rows,cols,mask,confirmed_no_title)
    h,w=img.shape[:2]
    if h%2 or w%2 or max(h,w)>4096:
        raise ValueError('Video dimensions must be even and at most 4096 pixels per side')
    output.parent.mkdir(parents=True,exist_ok=True)
    count=round(duration*fps)
    with tempfile.TemporaryDirectory(dir=output.parent) as tmp:
        dest=Path(tmp)/'motion.mp4'
        cmd=['ffmpeg','-v','error','-f','rawvideo','-pix_fmt','bgr24','-s',f'{w}x{h}','-r',str(fps),'-i','-','-an','-c:v','libx264','-pix_fmt','yuv420p','-crf','18','-movflags','+faststart',str(dest)]
        proc=subprocess.Popen(cmd,stdin=subprocess.PIPE)
        try:
            for i in range(count):
                proc.stdin.write(frame(img,clean,regions,mask,i/max(1,count-1)).tobytes())
            proc.stdin.close()
            if proc.wait(timeout=120):
                raise RuntimeError('FFmpeg failed')
        finally:
            if proc.poll() is None:
                proc.kill()
                proc.wait()
            if not proc.stdin.closed:
                proc.stdin.close()
        # Do not overwrite a file created by another task.
        with output.open('xb') as fp:
            fp.write(dest.read_bytes())
    return output


def prepare_external_video(video, output, *, start=0., duration=2., fps=30):
    """Import a local video (including Flow downloads), with no network calls.
    Keep aspect ratio; never silently stretch or pad a short clip.
    """
    video,output=Path(video),Path(output)
    if output.exists() or video.resolve()==output.resolve():
        raise FileExistsError(output)
    if not math.isfinite(start) or start<0 or not 1<=duration<=3.5 or fps not in (24,30):
        raise ValueError('Invalid clip parameters')
    info=json.loads(subprocess.check_output(['ffprobe','-v','error','-select_streams','v:0','-show_entries','stream=duration,width,height','-of','json',str(video)],timeout=20))
    streams=info.get('streams',[])
    if not streams or float(streams[0].get('duration',0))+.001<start+duration:
        raise ValueError('Source video missing or shorter than requested segment')
    output.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as tmp:
        dest=Path(tmp)/'clip.mp4'
        subprocess.run(['ffmpeg','-v','error','-ss',str(start),'-i',str(video),'-t',str(duration),'-map','0:v:0','-an','-vf',f'fps={fps},scale=trunc(iw/2)*2:trunc(ih/2)*2','-c:v','libx264','-crf','18','-pix_fmt','yuv420p','-movflags','+faststart',str(dest)],check=True,timeout=120)
        with output.open('xb') as fp:
            fp.write(dest.read_bytes())
    return output


def request_zhipu(image_data_url, prompt, *, allow_network=False, client=None, timeout=180, poll=5, clock=time.monotonic, sleep=time.sleep):
    """Submit exactly one Flash job; return result URL, never auto-switch models.
    Caller must verify pricing/entitlement and authorize sending the image first.
    URL download and real account smoke test are separate integration steps.
    """
    if not allow_network:
        raise PermissionError('Explicit network and quota approval required')
    if not image_data_url.startswith(('data:image/jpeg;base64,','data:image/png;base64,')) or not prompt.strip():
        raise ValueError('Expected explicit JPEG/PNG data URL and prompt')
    if len(image_data_url)>14_000_000 or not 0<timeout<=600 or not 0<poll<=30:
        raise ValueError('Invalid input size or polling settings')
    if client is None:
        key=os.environ.get('ZHIPUAI_API_KEY')
        if not key:
            raise RuntimeError('ZHIPUAI_API_KEY not configured')
        from zhipuai import ZhipuAI
        client=ZhipuAI(api_key=key,timeout=20,max_retries=0)
    deadline=clock()+timeout
    task=client.videos.generations(model=MODEL,image_url=image_data_url,prompt=prompt)
    if not getattr(task,'id',None):
        raise RuntimeError('Missing provider task id')
    while clock()<deadline:
        result=client.videos.retrieve_videos_result(id=task.id)
        status=str(result.task_status).upper()
        if status=='SUCCESS':
            videos=getattr(result,'video_result',None) or []
            if not videos or not getattr(videos[0],'url','').startswith('https://'):
                raise RuntimeError('Missing HTTPS video result')
            return videos[0].url
        if status not in ('PENDING','PROCESSING'):
            raise RuntimeError('Provider task failed: '+status)
        sleep(min(poll,max(0,deadline-clock())))
    raise TimeoutError('Provider poll timed out; do not automatically resubmit')


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('image'); parser.add_argument('output')
    parser.add_argument('--rows',type=int,default=2); parser.add_argument('--cols',type=int,default=2)
    parser.add_argument('--mask'); parser.add_argument('--confirm-no-title',action='store_true')
    args=parser.parse_args()
    render(args.image,args.output,args.rows,args.cols,args.mask,args.confirm_no_title)
