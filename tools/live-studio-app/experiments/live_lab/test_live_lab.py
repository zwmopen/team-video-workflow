import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace as NS
import numpy as np
from live_lab import (
    DEFAULT_LIVE_PROMPT,
    MODEL,
    SCENE_PROMPTS,
    boxes,
    frame,
    prepare,
    prepare_external_video,
    render,
    request_zhipu,
    resolve_bin,
    transform,
    wrap_external_video_with_cover,
)

class Tests(unittest.TestCase):
    def test_paths_and_bounds(self):
        paths=[]
        for i in range(4):
            path=[]
            for t in np.linspace(0,1,61):
                z,x,y=transform(i,t)
                self.assertGreaterEqual(z,1)
                self.assertLessEqual(abs(x),(z-1)/2+1e-9)
                self.assertLessEqual(abs(y),(z-1)/2+1e-9)
                path.append((z,x,y))
            paths.append(tuple(path))
        self.assertEqual(len(set(paths)),4)
    def test_fixed_title(self):
        img=np.random.default_rng(3).integers(0,256,(128,128,3),dtype=np.uint8)
        mask=np.zeros((128,128),np.uint8);mask[60:68,58:70]=255
        b,c,m=prepare(img,2,2,mask)
        self.assertTrue(np.array_equal(frame(img,c,b,m,0),img))
        out=frame(img,c,b,m,.8)
        self.assertTrue(np.array_equal(out[m>0],img[m>0]))
        self.assertFalse(np.array_equal(out[m==0],img[m==0]))
    def test_no_unsafe_auto(self):
        with self.assertRaises(ValueError): prepare(np.zeros((128,128,3),np.uint8),2,2)
        with self.assertRaises(ValueError): boxes(128,128,3,3)
    def test_provider(self):
        class Fake:
            def __init__(self): self.calls=[]; self.videos=self
            def generations(self,**kw): self.calls.append(kw);return NS(id='test')
            def retrieve_videos_result(self,**kw): return NS(task_status='SUCCESS',video_result=[NS(url='https://example.com/video.mp4')])
        f=Fake()
        with self.assertRaises(PermissionError): request_zhipu('data:image/jpeg;base64,a','prompt',client=f)
        self.assertEqual(f.calls,[])
        self.assertEqual(request_zhipu('data:image/jpeg;base64,a','prompt',allow_network=True,client=f),'https://example.com/video.mp4')
        self.assertEqual(f.calls[0]['model'],MODEL)
        self.assertEqual(len(f.calls),1)
    def test_timeout_no_resubmit(self):
        class Fake:
            videos=None
            def __init__(self): self.videos=self;self.n=0
            def generations(self,**kw): self.n+=1;return NS(id='test')
            def retrieve_videos_result(self,**kw):return NS(task_status='PROCESSING')
        t=[0]; f=Fake()
        def sleep(s):t[0]+=s
        with self.assertRaises(TimeoutError):request_zhipu('data:image/jpeg;base64,a','prompt',allow_network=True,client=f,timeout=3,poll=1,clock=lambda:t[0],sleep=sleep)
        self.assertEqual(f.n,1)

    def test_scene_prompts_and_cover_pairing(self):
        self.assertEqual(len(SCENE_PROMPTS), 15)
        self.assertIn("民宿别墅", SCENE_PROMPTS)
        self.assertTrue(DEFAULT_LIVE_PROMPT.startswith("将上传的静态照片"))
        self.assertTrue(bool(resolve_bin("ffmpeg")))

        with tempfile.TemporaryDirectory() as tmp:
            tdir = Path(tmp)
            img = np.zeros((160, 120, 3), dtype=np.uint8)
            img[20:140, 20:100] = (40, 180, 90)
            cover = tdir / "cover.jpg"
            cv_ok = __import__("cv2").imwrite(str(cover), img, [int(__import__("cv2").IMWRITE_JPEG_QUALITY), 96])
            self.assertTrue(cv_ok)
            raw_vid = tdir / "raw.mp4"
            render(cover, raw_vid, rows=2, cols=2, confirmed_no_title=True, duration=1.5, fps=30)
            clip = tdir / "clip.mp4"
            prepare_external_video(raw_vid, clip, start=0.0, duration=1.2, fps=30)
            out_live = tdir / "out_live.jpg"
            wrap_external_video_with_cover(cover, clip, out_live)
            payload = out_live.read_bytes()
            self.assertIn(b"GCamera:MotionPhoto=\"1\"", payload)
            self.assertIn(b"MicroVideoOffset", payload)

if __name__=='__main__':unittest.main()

if __name__=='__main__':unittest.main()
