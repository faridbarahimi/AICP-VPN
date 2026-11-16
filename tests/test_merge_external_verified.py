import json, tempfile, unittest
from pathlib import Path
from datetime import datetime, timezone
from scripts.merge_external_verified import merge, EXPECTED

class MergeExternalVerifiedTests(unittest.TestCase):
    def test_merges_only_fresh_protocol_verified_safe_configs(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); state=root/'state'; state.mkdir()
            base='vless://12345678-1234-1234-1234-123456789abc@8.8.8.8:443?security=tls&type=ws'
            verified='trojan://password@1.1.1.1:443?security=tls&type=ws'
            (root/'validated.json').write_text(json.dumps([base]))
            (root/'intelligence.json').write_text(json.dumps({'schema_version':1}))
            (state/'external-config-verified.json').write_text(json.dumps([verified,'vless://id@127.0.0.1:443']))
            (state/'external-verification-report.json').write_text(json.dumps({'generated_at':datetime.now(timezone.utc).isoformat(),'verification':EXPECTED,'protocol_verified':1}))
            result=merge(root/'validated.json',root/'intelligence.json',state)
            self.assertEqual(2,result['total_configs'])
            self.assertEqual(1,result['external']['included'])
            self.assertEqual([base,verified],json.loads((root/'validated.json').read_text()))

    def test_stale_or_untrusted_verification_is_never_merged(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); state=root/'state'; state.mkdir()
            (root/'validated.json').write_text(json.dumps(['vless://12345678-1234-1234-1234-123456789abc@8.8.8.8:443?security=tls&type=ws']))
            (root/'intelligence.json').write_text('{}')
            (state/'external-config-verified.json').write_text(json.dumps(['trojan://pass@1.1.1.1:443?security=tls&type=ws']))
            (state/'external-verification-report.json').write_text(json.dumps({'generated_at':'2020-01-01T00:00:00Z','verification':EXPECTED}))
            result=merge(root/'validated.json',root/'intelligence.json',state)
            self.assertEqual(1,result['total_configs'])
            self.assertEqual('stale_or_untrusted_snapshot',result['external']['status'])

if __name__=='__main__': unittest.main()
