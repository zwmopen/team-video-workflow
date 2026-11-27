import unittest
from types import SimpleNamespace as NS
import numpy as np
from live_lab import transform, prepare, frame, request_zhipu, boxes, MODEL

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

if __name__=='__main__':unittest.main()
