import unittest
from scripts.telegram_npv_attachment_index import DocumentParser, safe_document

class TelegramNpvAttachmentIndexTests(unittest.TestCase):
    def test_extracts_npvt_npvs_document_metadata(self):
        html = '''<a class="tgme_widget_message_document_wrap" href="https://t.me/srcvpn/7599"><div class="tgme_widget_message_document_title">Fast⚡116.npvt</div><div class="tgme_widget_message_document_extra">23.8 KB</div></a>'''
        parser=DocumentParser(); parser.feed(html)
        item=safe_document(parser.items[0], 'srcvpn', 'srcvpn-telegram')
        self.assertEqual(item['extension'],'.npvt')
        self.assertEqual(item['message_url'],'https://t.me/srcvpn/7599')
        self.assertEqual(item['display_size'],'23.8 KB')

    def test_rejects_wrong_channel_and_non_npv(self):
        self.assertIsNone(safe_document({'url':'https://t.me/other/123','title':'a.npvs','size':'1 KB'},'srcvpn','srcvpn-telegram'))
        self.assertIsNone(safe_document({'url':'https://t.me/srcvpn/123','title':'a.ovpn','size':'1 KB'},'srcvpn','srcvpn-telegram'))

    def test_rejects_external_or_malformed_document_urls(self):
        self.assertIsNone(safe_document({'url':'https://evil.example/srcvpn/123','title':'a.npvt','size':'1 KB'},'srcvpn','srcvpn-telegram'))
        self.assertIsNone(safe_document({'url':'https://t.me/srcvpn/not-a-message','title':'a.npvt','size':'1 KB'},'srcvpn','srcvpn-telegram'))

if __name__ == '__main__': unittest.main()
