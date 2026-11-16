import json
import sys
from contextlib import redirect_stdout
from io import StringIO
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from scripts.telegram_config_engine import collect, extract_uris, main

class TelegramConfigEngineTests(unittest.TestCase):
    def test_supported_schemes_and_html_entities(self):
        html = """<div class="tgme_widget_message_text">
        <code>vless://id@one.com:443?security=reality&amp;sni=one.com#one</code><br>
        <code>trojan://secret@two.com:443#two</code><br>
        <code>ss://YWVzLTI1Ni1nY206cGFzcw@three.com:443#three</code>
        </div>"""
        result = extract_uris(html)
        self.assertEqual(3, len(result))
        self.assertIn("&sni=one.com", result[0])
        self.assertTrue(result[1].startswith("trojan://"))
        self.assertTrue(result[2].startswith("ss://"))
    def test_ignores_non_message_and_unsupported_schemes(self):
        html = """vless://outside@ignored.example:443
        <div class="tgme_widget_message_text">npv://opaque
        tg://proxy?server=proxy.example&amp;port=443</div>"""
        self.assertEqual([], extract_uris(html))
    def test_deduplicates_without_fragment(self):
        html = """<div class="tgme_widget_message_text">
        vless://id@same.com:443#first<br>vless://id@same.com:443#second</div>"""
        self.assertEqual(1, len(extract_uris(html)))
    def test_empty_source_fails_closed_so_previous_snapshot_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            catalog = Path(directory) / "sources.json"
            catalog.write_text(json.dumps({"schema_version": 1, "sources": [
                {"id": "empty", "channel": "empty_channel", "enabled": True}
            ]}), encoding="utf-8")
            with patch("scripts.telegram_config_engine.fetch_channel",
                       return_value='<div class="tgme_widget_message_text">no configs</div>'):
                with self.assertRaises(RuntimeError):
                    collect(catalog)

    def test_cli_writes_snapshot_from_validated_uris_and_intelligence_report(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory); uris=root/'uris.json'; intelligence=root/'intelligence.json'
            output=root/'feed.txt'; manifest=root/'manifest.json'
            uris.write_text(json.dumps(['vless://id@8.8.8.8:443#one']))
            intelligence.write_text(json.dumps({'source_count':2,'ranked_sources':[],
                'extracted':4,'tested':4,'tcp_reachable':3,'proxy_verified':0,
                'feed_config_count':1,'feed_tested':4,'feed_tcp_reachable':3,'feed_extracted':4,'external_sources':{'status':'fresh_verified_snapshot','included':2,'protocol_verified':2}}))
            argv=['telegram_config_engine','--validated-uris',str(uris),'--intelligence-report',str(intelligence),
                  '--output',str(output),'--manifest',str(manifest)]
            with patch.object(sys,'argv',argv), redirect_stdout(StringIO()):
                self.assertEqual(0,main())
            data=json.loads(manifest.read_text())
            self.assertEqual(1,data['config_count'])
            self.assertEqual('mixed_telegram_tcp_and_external_sing_box_verified',data['health_status'])
            self.assertEqual(2,data['external_protocol_verified_count'])
            self.assertEqual(3,data['tcp_reachable_count'])
            self.assertIn('vless://id@8.8.8.8:443#one',output.read_text())

    def test_rejects_private_and_local_endpoints(self):
        html = """<div class=\"tgme_widget_message_text\">
        vless://id@127.0.0.1:443#loopback<br>
        trojan://secret@192.168.1.2:443#private<br>
        vless://id@localhost:443#local<br>
        vless://id@valid.com:443#public</div>"""
        self.assertEqual(["vless://id@valid.com:443#public"], extract_uris(html))

    def test_rejects_malformed(self):
        html = """<div class="tgme_widget_message_text">
        vless://@missing-host:443<br>vless://id@valid.com:443#ok</div>"""
        self.assertEqual(["vless://id@valid.com:443#ok"], extract_uris(html))

if __name__ == "__main__":
    unittest.main()
