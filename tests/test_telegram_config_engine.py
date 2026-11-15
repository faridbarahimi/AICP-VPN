import unittest
from scripts.telegram_config_engine import extract_uris

class TelegramConfigEngineTests(unittest.TestCase):
    def test_supported_schemes_and_html_entities(self):
        html = """<div class="tgme_widget_message_text">
        <code>vless://id@one.example:443?security=reality&amp;sni=one.example#one</code><br>
        <code>trojan://secret@two.example:443#two</code><br>
        <code>ss://YWVzLTI1Ni1nY206cGFzcw@three.example:443#three</code>
        </div>"""
        result = extract_uris(html)
        self.assertEqual(3, len(result))
        self.assertIn("&sni=one.example", result[0])
        self.assertTrue(result[1].startswith("trojan://"))
        self.assertTrue(result[2].startswith("ss://"))
    def test_ignores_non_message_and_unsupported_schemes(self):
        html = """vless://outside@ignored.example:443
        <div class="tgme_widget_message_text">npv://opaque
        tg://proxy?server=proxy.example&amp;port=443</div>"""
        self.assertEqual([], extract_uris(html))
    def test_deduplicates_without_fragment(self):
        html = """<div class="tgme_widget_message_text">
        vless://id@same.example:443#first<br>vless://id@same.example:443#second</div>"""
        self.assertEqual(1, len(extract_uris(html)))
    def test_rejects_malformed(self):
        html = """<div class="tgme_widget_message_text">
        vless://@missing-host:443<br>vless://id@valid.example:443#ok</div>"""
        self.assertEqual(["vless://id@valid.example:443#ok"], extract_uris(html))

if __name__ == "__main__":
    unittest.main()
