import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from device_authorization import Registry, new_token, digest, fingerprint

class AuthorizationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name)/"auth.db"
        self.reg = Registry(self.path)
        self.token = new_token()
    def test_default_deny(self):
        self.assertFalse(self.reg.authorize(self.token,"GET","/api/online/works"))
        self.assertFalse(self.reg.authorize("bogus","GET","/api/online/works"))
        self.assertEqual(self.reg.request(self.token,"test")["state"],"pending")
        self.assertFalse(self.reg.authorize(self.token,"GET","/api/online/works"))
    def test_approval_roles_and_revocation(self):
        self.reg.request(self.token,"test")
        self.assertTrue(self.reg.decide(digest(self.token),"approved","read"))
        self.assertTrue(self.reg.authorize(self.token,"GET","/api/online/works"))
        self.assertFalse(self.reg.authorize(self.token,"POST","/api/online/use-work"))
        self.assertFalse(self.reg.authorize(new_token(),"GET","/api/online/works"))
        self.reg.decide(digest(self.token),"approved","operate")
        self.assertTrue(self.reg.authorize(self.token,"POST","/api/online/use-work"))
        self.assertFalse(self.reg.authorize(self.token,"POST","/api/online/update-copy"))
        self.reg.decide(digest(self.token),"approved","manage")
        self.assertTrue(self.reg.authorize(self.token,"POST","/api/online/update-copy"))
        self.assertFalse(self.reg.authorize(self.token,"POST","/api/online/delete-work"))
        self.assertFalse(self.reg.authorize(self.token,"GET","/unexpected"))
        self.assertTrue(Registry(self.path).authorize(self.token,"GET","/api/online/works"))
        self.reg.decide(digest(self.token),"revoked")
        self.assertFalse(self.reg.authorize(self.token,"GET","/api/online/works"))
        self.assertEqual(self.reg.request(self.token,"renamed")["state"],"revoked")
        self.assertFalse(self.reg.decide(digest(self.token),"approved","manage"))
    def test_no_raw_token_on_disk(self):
        self.reg.request(self.token,"test")
        self.assertNotIn(self.token.encode(),self.path.read_bytes())
        self.assertEqual(self.reg.status(self.token)["fingerprint"],fingerprint(digest(self.token)))
    def test_revoke_all_includes_pending_and_persists(self):
        another = new_token()
        self.reg.request(self.token,"one")
        self.reg.request(another,"two")
        self.reg.decide(digest(self.token),"approved","manage")
        self.assertEqual(self.reg.revoke_all(),2)
        self.assertEqual(self.reg.revoke_all(),0)
        reloaded=Registry(self.path)
        for token in (self.token,another):
            self.assertEqual(reloaded.status(token)["state"],"revoked")
            self.assertFalse(reloaded.authorize(token,"GET","/api/online/works"))
        self.assertEqual(sum(x["outcome"]=="revoked" for x in reloaded.audit()),2)

    def test_invalid_role_and_token(self):
        with self.assertRaises(ValueError):
            self.reg.request("short","test")
        self.reg.request(self.token,"test")
        with self.assertRaises(ValueError):
            self.reg.decide(digest(self.token),"approved","owner")

if __name__ == "__main__":
    unittest.main()
