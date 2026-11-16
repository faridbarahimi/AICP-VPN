import base64, json, tempfile, unittest
from pathlib import Path
from unittest.mock import patch
from scripts.external_source_intelligence import (
    decode_subscription, extract_config_candidates, fetch_text,
    parse_host_ports, parse_ip_or_cidr_lines, parse_proxy_records, run,
)

class ExternalSourceIntelligenceTests(unittest.TestCase):
    def test_decodes_base64_only_when_it_contains_proxy_links(self):
        raw = 'vless://12345678-1234-1234-1234-123456789abc@8.8.8.8:443?security=tls&type=ws'
        self.assertIn('vless://', decode_subscription(base64.b64encode(raw.encode()).decode()))
        self.assertEqual('ordinary text', decode_subscription('ordinary text'))

    def test_extracts_unique_safe_public_config_links(self):
        good = 'vless://12345678-1234-1234-1234-123456789abc@8.8.8.8:443?security=tls&type=ws'
        body = good + '\n' + good + '\n' + 'vless://id@127.0.0.1:443'
        self.assertEqual([good], extract_config_candidates(body))

    def test_rejects_unapproved_hosts_and_non_https_sources(self):
        with self.assertRaises(ValueError): fetch_text('https://example.com/list.txt')
        with self.assertRaises(ValueError): fetch_text('http://raw.githubusercontent.com/a/b/c')

    def test_parses_only_global_ip_cidrs(self):
        self.assertEqual(['8.8.8.0/24'], parse_ip_or_cidr_lines('8.8.8.0/24\n127.0.0.1/32\nnot-an-ip'))

    def test_parses_only_global_ip_port_endpoints(self):
        self.assertEqual([('8.8.8.8', 443)], parse_host_ports('8.8.8.8:443\n127.0.0.1:80\n8.8.8.8:99999'))

    def test_parses_proxifly_json_records(self):
        body = json.dumps([{'ip':'8.8.8.8','port':1080},{'ip':'127.0.0.1','port':1080}])
        self.assertEqual([('8.8.8.8',1080)], parse_proxy_records(body))

    def test_external_candidates_are_quarantined_not_published(self):
        with tempfile.TemporaryDirectory() as d:
            good = 'vless://12345678-1234-1234-1234-123456789abc@8.8.8.8:443?security=tls&type=ws'
            def fake_fetch(url, timeout=8):
                if 'free-proxy-list' in url: return '[{"ip":"8.8.8.8","port":1080}]'
                if 'warp-endpoints' in url: return '8.8.8.8:2408\n127.0.0.1:2408'
                if '/clean-ip/' in url: return 'placeholder text'
                if 'cloudflare.com/ips-v4' in url: return '173.245.48.0/20'
                if 'sub_merge' in url: return base64.b64encode(good.encode()).decode()
                return good
            with patch('scripts.external_source_intelligence.fetch_text', side_effect=fake_fetch), \
                 patch('scripts.external_source_intelligence.probe_tcp', return_value={'status':'tcp_reachable','latency_ms':10}):
                report = run(Path(d))
            self.assertEqual(1, report['unique_safe_config_candidates'])
            self.assertEqual(1, report['tcp_reachable_candidates'])
            self.assertEqual(0, report['protocol_verified_candidates'])
            self.assertIn('quarantined', report['publication_policy'])
            stored = json.loads((Path(d)/'external-config-candidates.json').read_text())
            self.assertEqual([good], stored)
            self.assertEqual(0o600, (Path(d)/'external-config-candidates.json').stat().st_mode & 0o777)
            self.assertTrue((Path(d)/'external-network-datasets.json').exists())

if __name__ == '__main__': unittest.main()
