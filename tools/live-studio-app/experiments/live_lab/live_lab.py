"""Experimental desktop renderer and external clip / provider bridge.
Not a standalone replacement for mobile on-device encoders; external cover pairing
explicitly reuses lossless JPEG XMP+MP4 injection when available.
"""
import math
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

import cv2
import numpy as np

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

MODEL = 'cogvideox-flash'

DEFAULT_LIVE_PROMPT = (
    "将上传的静态照片转换为具有自然手机实况照片质感的短视频。"
    "严格保持原始照片的构图、拍摄角度、人物身份、建筑结构、场地设施、空间布局、物品位置、光线和色彩。"
    "根据画面实际内容，仅产生符合真实物理规律的细微动态，镜头基本固定，允许极轻微的自然手持变化。"
    "不要明显推拉镜头，不要大幅度缩放，不要改变场景，不要新增或删除物体，不要改变人物容貌，不要生成夸张动作。"
    "不要出现鬼影、物体变形、闪烁、电影特效或过度运镜。保持原始比例，高清画质，无转场，无新增文字。"
)

SCENE_PROMPTS = {
    "自然风景": DEFAULT_LIVE_PROMPT + " 仅树叶、草木或远景氛围产生极轻微自然微风拂动。",
    "森林徒步": DEFAULT_LIVE_PROMPT + " 仅林间树叶与光斑产生轻微风动感，人物保持自然微呼吸与姿态稳定。",
    "民宿别墅": DEFAULT_LIVE_PROMPT + " 严格保持建筑外观、院落、家具结构不变，仅窗帘、绿植或水面波光细微活动。",
    "海边湖泊": DEFAULT_LIVE_PROMPT + " 仅水面产生克制的自然微澜波纹与倒影波动，岸线和建筑固定不变。",
    "瀑布溪流": DEFAULT_LIVE_PROMPT + " 仅水流顺应重力自然连续流动与水雾微动，岩石山体完全稳定。",
    "露营草坪": DEFAULT_LIVE_PROMPT + " 仅天幕布料、草叶与周围旗帜微动，帐篷与场地结构不变。",
    "围炉煮茶": DEFAULT_LIVE_PROMPT + " 仅茶壶升起极淡的细微热气，炭火轻微暖光闪烁，器皿位置完全固定。",
    "篝火晚会": DEFAULT_LIVE_PROMPT + " 仅火焰与零星火星轻微自然跳动，人群和周围环境维持稳定。",
    "温泉私汤": DEFAULT_LIVE_PROMPT + " 仅池面微澜与少量温热白雾轻缓升腾，泡池建筑完全不变。",
    "滑雪雪景": DEFAULT_LIVE_PROMPT + " 仅细微雪粒飘落或远处树梢轻动，雪道结构与人物姿势不扭曲。",
    "美食聚餐": DEFAULT_LIVE_PROMPT + " 仅热菜上方极轻微热气或桌面光影呼吸，食物摆盘与餐具不变形。",
    "人物合照": DEFAULT_LIVE_PROMPT + " 仅人物轻微眨眼或自然呼吸稳态，保持所有五官与衣着完全一致不扭曲。",
    "户外团队活动": DEFAULT_LIVE_PROMPT + " 保持队形和人员面貌一致，仅发丝、衣角与环境绿植极轻微呼吸微动。",
    "室内空间": DEFAULT_LIVE_PROMPT + " 保持墙面、家具与灯具直线结构稳固，仅窗外树影或室内光影轻微变化。",
    "静物摆拍": DEFAULT_LIVE_PROMPT + " 仅柔和环境光线与极轻微自然手持稳态，主体静物形态完全稳定。",
}


def resolve_bin(name):
    """Find ffmpeg/ffprobe without breaking on missing PATH shims on Windows."""
    if os.name == "nt":
        win_candidate = Path(r"C:\ffmpeg\bin") / f"{name}.exe"
        if win_candidate.is_file():
            return str(win_candidate)
    found = shutil.which(name)
    return found or name
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
        cmd=[resolve_bin('ffmpeg'),'-v','error','-f','rawvideo','-pix_fmt','bgr24','-s',f'{w}x{h}','-r',str(fps),'-i','-','-an','-c:v','libx264','-pix_fmt','yuv420p','-crf','18','-movflags','+faststart',str(dest)]
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
    info=json.loads(subprocess.check_output([resolve_bin('ffprobe'),'-v','error','-select_streams','v:0','-show_entries','stream=duration,width,height','-of','json',str(video)],timeout=20))
    streams=info.get('streams',[])
    if not streams or float(streams[0].get('duration',0))+.001<start+duration:
        raise ValueError('Source video missing or shorter than requested segment')
    output.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as tmp:
        dest=Path(tmp)/'clip.mp4'
        subprocess.run([resolve_bin('ffmpeg'),'-v','error','-ss',str(start),'-i',str(video),'-t',str(duration),'-map','0:v:0','-an','-vf',f'fps={fps},scale=trunc(iw/2)*2:trunc(ih/2)*2','-c:v','libx264','-crf','18','-pix_fmt','yuv420p','-movflags','+faststart',str(dest)],check=True,timeout=120)
        with output.open('xb') as fp:
            fp.write(dest.read_bytes())
    return output


def wrap_external_video_with_cover(cover_image, video_mp4, output_jpg):
    """Pair a prepared external MP4 with an untouched HD static JPEG cover (lossless XMP+MP4 Motion Photo)."""
    cover_image, video_mp4, output_jpg = Path(cover_image), Path(video_mp4), Path(output_jpg)
    if not cover_image.is_file() or not video_mp4.is_file():
        raise FileNotFoundError("Missing cover_image or video_mp4")
    if output_jpg.exists() or output_jpg.resolve() == cover_image.resolve():
        raise FileExistsError(output_jpg)
    scripts_dir = Path(__file__).resolve().parents[3] / "device-share-hub" / "scripts"
    if str(scripts_dir) not in sys.path:
        sys.path.insert(0, str(scripts_dir))
    import motion_photo  # type: ignore

    with tempfile.TemporaryDirectory(dir=output_jpg.parent) as tmp:
        tmp_out = Path(tmp) / "paired.jpg"
        motion_photo.build_motion_photo_file(str(cover_image), str(video_mp4), str(tmp_out), cache_dir=tmp)
        output_jpg.parent.mkdir(parents=True, exist_ok=True)
        with output_jpg.open("xb") as fp:
            fp.write(tmp_out.read_bytes())
    return output_jpg


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
