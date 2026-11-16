#!/usr/bin/env python3
"""Protocol-aware external config verifier using a pinned sing-box binary.

Only VLESS/Trojan TLS profiles with supported transports are tested here.
Other protocols remain quarantined. A profile is verified only if sing-box
routes a real HTTP request through it and receives HTTP 204.
"""
from __future__ import annotations
import argparse, json, os, socket, subprocess, sys, tempfile, time, urllib.error, urllib.parse, urllib.request
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scripts.telegram_source_intelligence import probe_tcp

TEST_URL = "HTTP://www.gstatic.com/generate_204"
SUPPORTED_SCHEMES = {"vless", "trojan"}
SUPPORTED_TRANSPORTS = {"tcp", "ws", "grpc", "httpupgrade"}


def outbound(uri: str, tag: str):
    u = urllib.parse.urlsplit(uri)
    q = urllib.parse.parse_qs(u.query, keep_blank_values=True)
    get = lambda key: (q.get(key) or [""])[0].strip()
    scheme = u.scheme.lower(); security = get("security").lower()
    transport = (get("type") or "tcp").lower()
    if scheme not in SUPPORTED_SCHEMES or security != "tls" or transport not in SUPPORTED_TRANSPORTS:
        return None
    if not u.hostname or not u.port or not u.username:
        return None
    ob = {"type": scheme, "tag": tag, "server": u.hostname, "server_port": u.port}
    if scheme == "vless":
        ob["uuid"] = urllib.parse.unquote(u.username)
    else:
        ob["password"] = urllib.parse.unquote(u.username)
    sni = get("sni") or get("host") or u.hostname
    tls = {"enabled": True, "server_name": sni, "insecure": get("allowInsecure") in {"1", "true"}}
    tls["utls"] = {"enabled": True, "fingerprint": get("fp") if get("fp") in {"chrome","firefox","edge","safari","ios","android","random"} else "chrome"}
    if get("alpn"):
        tls["alpn"] = [x.strip() for x in get("alpn").split(",") if x.strip()]
    ob["tls"] = tls
    if transport == "ws":
        ws = {"type": "ws", "path": get("path") or "/"}
        if get("host"): ws["headers"] = {"Host": get("host")}
        ob["transport"] = ws
    elif transport == "grpc":
        ob["transport"] = {"type": "grpc", "service_name": get("serviceName")}
    elif transport == "httpupgrade":
        ob["transport"] = {"type": "httpupgrade", "path": get("path") or "/", "host": get("host") or sni}
    return ob


def free_port():
    s=socket.socket(); s.bind(("127.0.0.1",0)); p=s.getsockname()[1]; s.close(); return p


def verify_batch(core: Path, uris: list[str], timeout_ms=10000) -> tuple[list[str], list[dict]]:
    port=free_port(); obs=[]; uri_by_tag={}
    for uri in uris:
        tag=f"node-{len(obs)}"
        try: ob=outbound(uri,tag)
        except Exception: ob=None
        if ob:
            obs.append(ob); uri_by_tag[tag]=uri
    if not obs: return [], []
    cfg={"log":{"level":"error"},
         "dns":{"servers":[{"tag":"dns","type":"https","server":"1.1.1.1","path":"/dns-query"}],"final":"dns","strategy":"prefer_ipv4"},
         "outbounds":obs+[{"type":"direct","tag":"direct"}],
         "experimental":{"clash_api":{"external_controller":f"127.0.0.1:{port}"}}}
    verified=[]; results=[]
    with tempfile.TemporaryDirectory(prefix="aicp-singbox-") as td:
        conf=Path(td)/"config.json"; conf.write_text(json.dumps(cfg),encoding="utf-8")
        proc=subprocess.Popen([str(core),"run","-c",str(conf)],stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,text=True)
        try:
            deadline=time.monotonic()+8
            while time.monotonic()<deadline:
                if proc.poll() is not None:
                    err=(proc.stderr.read() if proc.stderr else "")[-1000:]
                    raise RuntimeError("sing-box failed to start: "+err)
                try:
                    with urllib.request.urlopen(f"http://127.0.0.1:{port}/version",timeout=.4): break
                except Exception: time.sleep(.15)
            else: raise RuntimeError("sing-box Clash API did not start")
            for tag,uri in uri_by_tag.items():
                url=f"http://127.0.0.1:{port}/proxies/{tag}/delay?timeout={timeout_ms}&url={urllib.parse.quote(TEST_URL,safe='')}"
                status="failed"; latency=None
                try:
                    with urllib.request.urlopen(url,timeout=timeout_ms/1000+3) as response:
                        data=json.loads(response.read().decode()); latency=data.get("delay")
                        status="verified" if isinstance(latency,(int,float)) and latency>=0 else "failed"
                except urllib.error.HTTPError as exc:
                    try: detail=exc.read().decode("utf-8","replace")[:500]
                    except Exception: detail=""
                    status=f"HTTP_{exc.code}:{detail}"
                except Exception as exc: status=type(exc).__name__
                results.append({"tag":tag,"status":status,"latency_ms":latency})
                if status=="verified": verified.append(uri)
        finally:
            proc.terminate()
            try: proc.wait(timeout=3)
            except subprocess.TimeoutExpired: proc.kill(); proc.wait(timeout=3)
    return verified,results


def run(input_path: Path, output_path: Path, report_path: Path, core: Path, limit=200, batch_size=20):
    candidates=json.loads(input_path.read_text(encoding="utf-8"))
    if not isinstance(candidates,list): raise ValueError("candidate file must be a JSON array")
    eligible_all=[]; skipped=0
    for uri in candidates:
        try: ob=outbound(uri,"probe")
        except Exception: ob=None
        if ob: eligible_all.append(uri)
        else: skipped+=1
    if len(eligible_all) > limit and limit > 1:
        selected=[eligible_all[round(i*(len(eligible_all)-1)/(limit-1))] for i in range(limit)]
    else:
        selected=eligible_all[:limit]
    tcp_eligible=[]
    for uri in selected:
        if probe_tcp(uri, timeout=1.5)["status"] == "tcp_reachable": tcp_eligible.append(uri)
    eligible=tcp_eligible
    verified=[]; tested=0; batch_errors=[]
    for i in range(0,len(eligible),batch_size):
        batch=eligible[i:i+batch_size]
        try:
            good, results=verify_batch(core,batch)
            verified.extend(good); tested+=len(results)
        except Exception as exc:
            batch_errors.append({"batch":i//batch_size,"error":type(exc).__name__,"detail":str(exc)[:600]})
    output_path.parent.mkdir(parents=True,exist_ok=True)
    output_path.write_text(json.dumps(verified,ensure_ascii=False,indent=2)+"\n",encoding="utf-8"); output_path.chmod(0o600)
    report={"generated_at":time.strftime("%Y-%m-%dT%H:%M:%SZ",time.gmtime()),"candidate_total":len(candidates),"supported_candidates_total":len(eligible_all),"selected_for_probe":len(selected),"tcp_reachable_supported":len(tcp_eligible),"untested_supported_candidates":max(0,len(eligible_all)-len(selected)),"skipped_unsupported_or_invalid":skipped,"tested":tested,"protocol_verified":len(verified),"verification":"sing-box authenticated outbound request returned HTTP 204","test_url":TEST_URL,"core_version":subprocess.check_output([str(core),"version"],text=True).splitlines()[0],"batch_errors":batch_errors}
    report_path.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8"); report_path.chmod(0o600)
    return report

if __name__=="__main__":
    p=argparse.ArgumentParser(); p.add_argument("--input",type=Path,default=Path("/var/lib/aicp-vpn-source-intelligence/external-config-candidates.json")); p.add_argument("--output",type=Path,default=Path("/var/lib/aicp-vpn-source-intelligence/external-config-verified.json")); p.add_argument("--report",type=Path,default=Path("/var/lib/aicp-vpn-source-intelligence/external-verification-report.json")); p.add_argument("--core",type=Path,default=Path("/opt/aicp-vpn-tools/sing-box-1.14.4/sing-box")); p.add_argument("--limit",type=int,default=200)
    a=p.parse_args(); print(json.dumps(run(a.input,a.output,a.report,a.core,a.limit),ensure_ascii=False))
