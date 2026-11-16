#!/usr/bin/env python3
"""Index public Telegram NPV attachment metadata without downloading payloads."""
from __future__ import annotations
import argparse, json, re, sys, urllib.request
from datetime import datetime, timezone
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import urlsplit

EXT_RE = re.compile(r"(?i)\.(npvt|npvs)\b")
SIZE_RE = re.compile(r"(?i)^\s*(\d+(?:\.\d+)?)\s*(B|KB|MB)\s*$")

class DocumentParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.current = None
        self.in_title = False
        self.in_extra = False
        self.items = []
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        classes = attrs.get('class', '').split()
        if tag == 'a' and 'tgme_widget_message_document_wrap' in classes:
            self.current = {'url': attrs.get('href', ''), 'title': '', 'size': ''}
        elif self.current is not None and tag == 'div':
            self.in_title = 'tgme_widget_message_document_title' in classes
            self.in_extra = 'tgme_widget_message_document_extra' in classes
        elif self.current is not None and tag == 'br':
            self.in_title = self.in_extra = False
    def handle_data(self, data):
        if self.current is not None:
            if self.in_title: self.current['title'] += data
            if self.in_extra: self.current['size'] += data
    def handle_endtag(self, tag):
        if self.current is None: return
        if tag == 'div': self.in_title = self.in_extra = False
        if tag == 'a':
            self.items.append(self.current)
            self.current = None
            self.in_title = self.in_extra = False

def safe_document(item, channel, source_id):
    title = ' '.join(item['title'].split())
    match = EXT_RE.search(title)
    if not match: return None
    url = item['url']
    try:
        parsed = urlsplit(url)
        parts = parsed.path.strip('/').split('/')
        if parsed.scheme != 'https' or parsed.hostname != 't.me' or len(parts) != 2 or parts[0].lower() != channel.lower() or not parts[1].isdigit():
            return None
    except (ValueError, AttributeError): return None
    size = ' '.join(item['size'].split())
    if size and not SIZE_RE.fullmatch(size): size = ''
    return {'source_id': source_id, 'channel': channel, 'message_url': url,
            'filename': title, 'extension': '.' + match.group(1).lower(), 'display_size': size}

def fetch(channel, timeout=20):
    if not re.fullmatch(r'[A-Za-z0-9_]{4,64}', channel): raise ValueError('invalid channel')
    req = urllib.request.Request(f'https://t.me/s/{channel}', headers={'User-Agent':'AICP-VPN-NPV-Index/1.0'})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        final = urlsplit(response.geturl())
        if final.scheme != 'https' or final.hostname != 't.me': raise ValueError('unexpected redirect')
        data = response.read(4 * 1024 * 1024 + 1)
    if len(data) > 4 * 1024 * 1024: raise ValueError('Telegram preview exceeds size limit')
    return data.decode('utf-8', 'replace')

def build_index(catalog_path):
    catalog = json.loads(catalog_path.read_text(encoding='utf-8'))
    output, errors = [], []
    for source in catalog.get('sources', []):
        if not source.get('enabled') or not source.get('attachment_extensions'): continue
        channel, source_id = source['channel'], source['id']
        try:
            parser = DocumentParser(); parser.feed(fetch(channel))
            allowed = {str(x).lower() for x in source['attachment_extensions']}
            for item in parser.items:
                record = safe_document(item, channel, source_id)
                if record and record['extension'] in allowed: output.append(record)
        except Exception as exc:
            errors.append({'source_id': source_id, 'error': type(exc).__name__})
    # Deduplicate by canonical message URL.
    unique = {item['message_url']: item for item in output}
    return {'schema_version':1, 'generated_at':datetime.now(timezone.utc).isoformat(timespec='seconds').replace('+00:00','Z'),
            'attachment_count':len(unique), 'attachments':list(unique.values()), 'errors':errors,
            'payload_policy':'metadata_only; encrypted NPV payloads are not downloaded or published'}

def main():
    p=argparse.ArgumentParser(); p.add_argument('--catalog',type=Path,default=Path('config/telegram-sources.json')); p.add_argument('--output',type=Path,required=True); a=p.parse_args()
    try:
        result=build_index(a.catalog); a.output.parent.mkdir(parents=True,exist_ok=True); a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8'); print(json.dumps({k:v for k,v in result.items() if k!='attachments'},ensure_ascii=False)); return 0 if not result['errors'] else 2
    except Exception as exc: print(f'ERROR: {type(exc).__name__}: {exc}',file=sys.stderr); return 2
if __name__=='__main__': raise SystemExit(main())
