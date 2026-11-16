#!/usr/bin/env python3
"""Merge only fresh, sing-box-verified external configs into a feed build."""
from __future__ import annotations
import argparse, json, sys
from datetime import datetime, timezone
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scripts.telegram_config_engine import is_safe_config_uri

EXPECTED = "sing-box authenticated outbound request returned HTTP 204"

def merge(validated_path: Path, intelligence_path: Path, state_dir: Path, max_age_hours=30):
    base=json.loads(validated_path.read_text(encoding="utf-8"))
    report_path=state_dir/"external-verification-report.json"
    verified_path=state_dir/"external-config-verified.json"
    meta={"status":"unavailable","included":0,"protocol_verified":0}
    if report_path.is_file() and verified_path.is_file():
        try:
            vr=json.loads(report_path.read_text(encoding="utf-8"))
            generated=datetime.fromisoformat(vr["generated_at"].replace("Z","+00:00"))
            age=(datetime.now(timezone.utc)-generated).total_seconds()/3600
            verified=json.loads(verified_path.read_text(encoding="utf-8"))
            if age <= max_age_hours and vr.get("verification")==EXPECTED and isinstance(verified,list):
                seen={x.split("#",1)[0] for x in base}
                added=0
                for uri in verified:
                    if isinstance(uri,str) and is_safe_config_uri(uri) and uri.split("://",1)[0] in {"vless","trojan"}:
                        key=uri.split("#",1)[0]
                        if key not in seen: base.append(uri); seen.add(key); added+=1
                meta={"status":"fresh_verified_snapshot","generated_at":vr["generated_at"],"age_hours":round(age,2),"included":added,"protocol_verified":len(verified),"verification":"sing-box HTTP 204"}
            else:
                meta={"status":"stale_or_untrusted_snapshot","age_hours":round(age,2),"included":0,"protocol_verified":0}
        except Exception as exc:
            meta={"status":"invalid_snapshot","error":type(exc).__name__,"included":0,"protocol_verified":0}
    validated_path.write_text(json.dumps(base,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    intelligence=json.loads(intelligence_path.read_text(encoding="utf-8"))
    intelligence["external_sources"] = meta
    intelligence_path.write_text(json.dumps(intelligence,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    return {"total_configs":len(base),"external":meta}

if __name__=="__main__":
    p=argparse.ArgumentParser(); p.add_argument("--validated",type=Path,required=True); p.add_argument("--intelligence",type=Path,required=True); p.add_argument("--state-dir",type=Path,default=Path("/var/lib/aicp-vpn-source-intelligence")); a=p.parse_args(); print(json.dumps(merge(a.validated,a.intelligence,a.state_dir),ensure_ascii=False))
