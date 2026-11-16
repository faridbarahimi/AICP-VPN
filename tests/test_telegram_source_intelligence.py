import json, tempfile, unittest
from pathlib import Path
from datetime import datetime, timezone
from unittest.mock import patch
from scripts.telegram_source_intelligence import endpoint, probe_tcp, run, extract_channel_candidates

class SourceIntelligenceTests(unittest.TestCase):
    def test_extracts_only_public_channel_slugs_from_repository_text(self):
        text = 'https://t.me/Valid_Channel and t.me/another_channel and https://t.me/s/ignored_channel'
        self.assertEqual(['another_channel', 'Valid_Channel'], extract_channel_candidates(text))

    def test_rejects_private_literal_endpoint(self):
        self.assertEqual('rejected_unsafe', probe_tcp('vless://id@127.0.0.1:443')['status'])

    def test_rejects_non_public_dns_answer(self):
        fake = [(2, 1, 6, '', ('10.0.0.2', 443))]
        with patch('scripts.telegram_source_intelligence.socket.getaddrinfo', return_value=fake):
            self.assertEqual('rejected_unsafe', probe_tcp('vless://id@safe.example:443')['status'])

    def test_endpoint_extracts_vless_host_port(self):
        self.assertEqual(('8.8.8.8', 443), endpoint('vless://id@8.8.8.8:443#x'))

    def test_invalid_port_rejected(self):
        with self.assertRaises(ValueError): endpoint('vless://id@8.8.8.8:70000')

    def test_discovered_sources_are_persisted_disabled_and_not_activated(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d); catalog = root/'catalog.json'; history = root/'history.jsonl'
            registry = root/'discovered.json'; report = root/'report.json'
            catalog.write_text(json.dumps({'schema_version':1,'sources':[{'id':'known','channel':'known_channel','enabled':True}]}))
            def fake_page(url):
                host = '8.8.8.8' if 'known' in url else '9.9.9.9'
                return f'<div class="tgme_widget_message_wrap"><time datetime="2026-11-16T10:00:00Z"></time><div class="tgme_widget_message_text">vless://id@{host}:443#one</div></div>'
            with patch('scripts.telegram_source_intelligence.discover_channels', return_value=(['new_channel'], [{'status':'ok'}])), \
                 patch('scripts.telegram_source_intelligence.fetch_channel', side_effect=fake_page), \
                 patch('scripts.telegram_source_intelligence.probe_tcp', return_value={'status':'tcp_reachable','latency_ms':5}):
                result = run(catalog, history, report, True, registry, None, root/'validated.json')
            stored = json.loads(registry.read_text())
            self.assertEqual(1, len(stored['sources']))
            self.assertFalse(stored['sources'][0]['enabled'])
            self.assertEqual(2, result['source_count'])
            self.assertEqual(1, result['feed_config_count'])
            self.assertEqual(['vless://id@8.8.8.8:443#one'], json.loads((root/'validated.json').read_text()))
            self.assertEqual(0, result['proxy_verified'])
            self.assertEqual(2, len(history.read_text().splitlines()))

    def test_rotation_ranks_existing_sources_and_excludes_stale_sources(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); cat=root/'cat.json'; reg=root/'reg.json'; effective=root/'effective.json'
            cat.write_text(json.dumps({'schema_version':1,'sources':[
                {'id':'weak','channel':'weak_chan','enabled':True},
                {'id':'strong','channel':'strong_chan','enabled':True}]}))
            def fake_inspect(source):
                status='stale' if source['id']=='weak' else 'active'
                return ({'id':source['id'],'channel':source['channel'],'enabled':True,'discovered':False,
                         'status':status,'extracted':5,'safe_configs':5 if status=='active' else 0,'tested':5,'tcp_reachable':4 if status=='active' else 0,
                         'proxy_verified':0,'last_seen_at':'now'}, [])
            with patch('scripts.telegram_source_intelligence.inspect_source', side_effect=fake_inspect):
                result=run(cat,root/'hist',root/'report',False,reg,effective)
            rotated=json.loads(effective.read_text())
            self.assertEqual(['strong'],[x['id'] for x in rotated['sources']])
            self.assertFalse(result['rotation']['blocked'])
            self.assertEqual('stale',result['rotation']['excluded_sources'][0]['reason'])

    def test_rotation_blocks_when_enabled_source_fetch_errors(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); cat=root/'cat.json'; reg=root/'reg.json'; effective=root/'effective.json'
            cat.write_text(json.dumps({'schema_version':1,'sources':[{'id':'bad','channel':'bad_chan','enabled':True}]}))
            def fake_inspect(source):
                return ({'id':'bad','channel':'bad_chan','enabled':True,'discovered':False,
                         'status':'error','extracted':0,'safe_configs':0,'tested':0,'tcp_reachable':0,
                         'proxy_verified':0,'last_seen_at':'now'}, [])
            with patch('scripts.telegram_source_intelligence.inspect_source', side_effect=fake_inspect):
                result=run(cat,root/'hist',root/'report',False,reg,effective)
            self.assertTrue(result['rotation']['blocked'])
            self.assertEqual([],json.loads(effective.read_text())['sources'])

    def test_channel_with_old_posts_is_marked_stale(self):
        old_page = '<div class="tgme_widget_message_wrap"><time datetime="2026-09-01T10:00:00Z"></time><div class="tgme_widget_message_text">vless://id@8.8.8.8:443#old</div></div>'
        with patch('scripts.telegram_source_intelligence.fetch_channel', return_value=old_page), \
             patch('scripts.telegram_source_intelligence.probe_tcp', return_value={'status':'tcp_reachable','latency_ms':5}):
            from scripts.telegram_source_intelligence import inspect_source
            result, _ = inspect_source({'id':'old','channel':'old_chan','enabled':False}, now=datetime(2026,11,16,tzinfo=timezone.utc))
        self.assertEqual('stale', result['status'])
        self.assertGreater(result['age_days'], 30)

    def test_channel_with_no_messages_is_marked_inactive(self):
        with patch('scripts.telegram_source_intelligence.fetch_channel', return_value='<div class="tgme_channel_info">empty</div>'):
            from scripts.telegram_source_intelligence import inspect_source
            result, uris = inspect_source({'id':'empty','channel':'empty_chan','enabled':False})
        self.assertEqual('inactive', result['status'])
        self.assertEqual(0, result['message_count'])
        self.assertEqual([], uris)

    def test_no_discovery_uses_persisted_candidates(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); cat=root/'cat.json'; reg=root/'reg.json'
            cat.write_text(json.dumps({'schema_version':1,'sources':[]}))
            reg.write_text(json.dumps({'schema_version':1,'sources':[{'id':'discovered-candidate','channel':'candidate_chan','enabled':False,'discovered':True}]}))
            with patch('scripts.telegram_source_intelligence.fetch_channel', return_value='<div class="tgme_widget_message_text">none</div>'):
                result=run(cat,root/'hist',root/'report',False,reg)
            self.assertEqual(1,result['source_count'])
            self.assertEqual(1,result['disabled_discovered_candidates'])

if __name__ == '__main__': unittest.main()
