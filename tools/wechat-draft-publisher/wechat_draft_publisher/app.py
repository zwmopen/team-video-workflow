from __future__ import annotations

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse
from typing import Any
import argparse
import json
import mimetypes
import os
import subprocess
import threading
import webbrowser

from .core import TreeNode, flatten_posts, parse_post_folder, scan_library
from .service import ConfigurationError, PublishConflict, publish_folder
from .store import DraftStore, SettingsStore
from .wechat_api import WechatApiError

MAX_JSON_BYTES = 1024 * 1024
LOCAL_HOSTS = {"127.0.0.1", "localhost", "::1"}
SETTINGS = SettingsStore()
DRAFTS = DraftStore()
TREE: TreeNode | None = None
POST_PATHS: dict[str, Path] = {}
SCAN_LOCK = threading.RLock()
PUBLISH_LOCK = threading.Lock()


def public_settings(settings: dict[str, Any]) -> dict[str, Any]:
    accounts = {}
    for key, value in (settings.get("accounts") or {}).items():
        if not isinstance(value, dict):
            continue
        accounts[key] = {
            "name": value.get("name", key),
            "app_id": value.get("app_id", ""),
            "author": value.get("author", ""),
            "credential_source": value.get("app_secret_env", "inline" if value.get("app_secret") else "missing"),
        }
    return {
        "library_root": settings.get("library_root", ""),
        "default_account": settings.get("default_account", "main"),
        "title_trigger": settings.get("title_trigger", 24),
        "title_target": settings.get("title_target", 20),
        "body_soft_limit": settings.get("body_soft_limit", 1000),
        "max_images": settings.get("max_images", 10),
        "accounts": accounts,
        "settings_path": str(SETTINGS.path),
        "history_path": str(DRAFTS.path),
    }


def rescan() -> dict[str, Any]:
    global TREE, POST_PATHS
    with SCAN_LOCK:
        settings = SETTINGS.load()
        root_text = str(settings.get("library_root", "")).strip()
        if not root_text:
            TREE = None
            POST_PATHS = {}
            return {"ok": True, "tree": None, "post_count": 0, "message": "请先设置成品库目录"}
        root = Path(root_text)
        try:
            tree = scan_library(root)
        except FileNotFoundError as exc:
            TREE = None
            POST_PATHS = {}
            return {"ok": False, "tree": None, "post_count": 0, "error": str(exc)}
        paths = {node.id: Path(node.path) for node in flatten_posts(tree)}
        TREE = tree
        POST_PATHS = paths
        return {"ok": True, "tree": tree.to_dict(), "post_count": len(paths)}


def resolve_post(post_id: str) -> Path:
    with SCAN_LOCK:
        path = POST_PATHS.get(post_id)
    if path is None:
        raise KeyError("帖子不存在或成品库尚未刷新")
    settings = SETTINGS.load()
    root = Path(str(settings.get("library_root", ""))).expanduser().resolve(strict=False)
    resolved = path.expanduser().resolve(strict=True)
    try:
        resolved.relative_to(root)
    except ValueError as exc:
        raise PermissionError("帖子路径不在当前成品库内") from exc
    if not resolved.is_dir():
        raise FileNotFoundError("帖子文件夹不存在")
    return resolved


def choose_folder_windows(initial: str = "") -> str:
    if os.name != "nt":
        raise RuntimeError("目录选择按钮仅在 Windows 可用，请直接粘贴路径")
    initial_escaped = initial.replace("'", "''")
    script = f"""
Add-Type -AssemblyName System.Windows.Forms
$dialog = New-Object System.Windows.Forms.FolderBrowserDialog
$dialog.Description = '选择公众号成品库目录'
$dialog.ShowNewFolderButton = $false
if ('{initial_escaped}') {{ $dialog.SelectedPath = '{initial_escaped}' }}
if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {{
  [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
  Write-Output $dialog.SelectedPath
}}
"""
    result = subprocess.run(
        ["powershell", "-NoProfile", "-STA", "-Command", script],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=180,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "打开目录选择器失败")
    return result.stdout.strip()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args: object) -> None:
        return

    def do_GET(self) -> None:  # noqa: N802
        if not self._authorize():
            return
        parsed = urlparse(self.path)
        path = unquote(parsed.path)
        query = parse_qs(parsed.query)
        try:
            if path == "/":
                self._html(INDEX_HTML)
            elif path == "/api/health":
                self._json({"ok": True})
            elif path == "/api/settings":
                self._json({"ok": True, **public_settings(SETTINGS.load())})
            elif path == "/api/tree":
                self._json(rescan() if TREE is None else {"ok": True, "tree": TREE.to_dict(), "post_count": len(POST_PATHS)})
            elif path == "/api/post":
                post_id = query.get("id", [""])[0]
                folder = resolve_post(post_id)
                settings = SETTINGS.load()
                post = parse_post_folder(
                    folder,
                    title_trigger=int(settings.get("title_trigger", 24)),
                    title_target=int(settings.get("title_target", 20)),
                    body_soft_limit=int(settings.get("body_soft_limit", 1000)),
                    max_images=int(settings.get("max_images", 10)),
                )
                payload = post.to_dict()
                payload["image_urls"] = [f"/api/image?id={post.id}&index={index}" for index in range(len(post.images))]
                self._json({"ok": True, "post": payload})
            elif path == "/api/image":
                post_id = query.get("id", [""])[0]
                index = int(query.get("index", ["-1"])[0])
                folder = resolve_post(post_id)
                post = parse_post_folder(folder, max_images=1000)
                if index < 0 or index >= len(post.images):
                    self.send_error(404)
                    return
                image = Path(post.images[index]).resolve(strict=True)
                image.relative_to(folder.resolve(strict=True))
                self._file(image, mimetypes.guess_type(str(image))[0] or "application/octet-stream")
            elif path == "/api/history":
                limit = int(query.get("limit", ["100"])[0])
                self._json({"ok": True, "items": DRAFTS.history(limit)})
            else:
                self.send_error(404)
        except (KeyError, FileNotFoundError, PermissionError, ValueError) as exc:
            self._json({"ok": False, "error": str(exc)}, status=400)
        except Exception as exc:
            self._json({"ok": False, "error": str(exc)}, status=500)

    def do_POST(self) -> None:  # noqa: N802
        if not self._authorize(require_json=True):
            return
        path = unquote(urlparse(self.path).path)
        try:
            payload = self._read_json()
            if path == "/api/settings/library-root":
                raw_root = str(payload.get("path", "")).strip()
                if not raw_root:
                    raise ValueError("成品库目录不能为空")
                root = Path(raw_root).expanduser().resolve(strict=False)
                if not root.exists() or not root.is_dir():
                    raise ValueError(f"成品库目录不存在：{root}")
                settings = SETTINGS.load()
                settings["library_root"] = str(root)
                SETTINGS.save(settings)
                self._json(rescan())
            elif path == "/api/pick-library-root":
                current = str(SETTINGS.load().get("library_root", ""))
                selected = choose_folder_windows(current)
                if not selected:
                    self._json({"ok": True, "cancelled": True})
                    return
                settings = SETTINGS.load()
                settings["library_root"] = selected
                SETTINGS.save(settings)
                result = rescan()
                result["selected"] = selected
                self._json(result)
            elif path == "/api/rescan":
                self._json(rescan())
            elif path == "/api/publish":
                if not PUBLISH_LOCK.acquire(blocking=False):
                    self._json({"ok": False, "error": "已有公众号草稿任务正在执行"}, status=409)
                    return
                try:
                    post_id = str(payload.get("post_id", ""))
                    folder = resolve_post(post_id)
                    settings = SETTINGS.load()
                    result = publish_folder(
                        folder=folder,
                        settings=settings,
                        store=DRAFTS,
                        account_id=str(payload.get("account_id") or settings.get("default_account", "main")),
                        title_override=str(payload.get("title", "")).strip() or None,
                        body_override=str(payload.get("body", "")) if "body" in payload else None,
                        force=bool(payload.get("force", False)),
                        dry_run=bool(payload.get("dry_run", False)),
                    )
                    self._json(result)
                finally:
                    PUBLISH_LOCK.release()
            else:
                self.send_error(404)
        except PublishConflict as exc:
            self._json({"ok": False, "error": str(exc), "code": "DUPLICATE", "existing": exc.existing}, status=409)
        except (ConfigurationError, KeyError, FileNotFoundError, PermissionError, ValueError) as exc:
            self._json({"ok": False, "error": str(exc)}, status=400)
        except WechatApiError as exc:
            self._json({"ok": False, "error": str(exc), "code": exc.code, "retryable": exc.retryable}, status=502)
        except Exception as exc:
            self._json({"ok": False, "error": str(exc)}, status=500)

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length < 0 or length > MAX_JSON_BYTES:
            raise ValueError("请求体超过 1 MB 限制")
        raw = self.rfile.read(length)
        payload = json.loads(raw.decode("utf-8")) if raw else {}
        if not isinstance(payload, dict):
            raise ValueError("请求体必须是 JSON 对象")
        return payload

    def _authorize(self, require_json: bool = False) -> bool:
        raw_host = str(self.headers.get("Host", "")).strip().lower()
        parsed_host = urlparse("//" + raw_host)
        host = parsed_host.hostname or ""
        port = parsed_host.port
        expected_port = int(getattr(self.server, "server_port", 8876))
        if host not in LOCAL_HOSTS or port not in (None, expected_port):
            self._json({"ok": False, "error": "仅允许本机访问"}, status=403)
            return False
        if str(self.headers.get("Sec-Fetch-Site", "")).lower() == "cross-site":
            self._json({"ok": False, "error": "拒绝跨站请求"}, status=403)
            return False
        for header in ("Origin", "Referer"):
            raw = str(self.headers.get(header, "")).strip()
            if raw:
                parsed = urlparse(raw)
                if parsed.hostname not in LOCAL_HOSTS or parsed.port not in (None, expected_port):
                    self._json({"ok": False, "error": "仅允许本机同源页面访问"}, status=403)
                    return False
        if require_json and str(self.headers.get("Content-Type", "")).split(";", 1)[0].strip().lower() != "application/json":
            self._json({"ok": False, "error": "POST 必须使用 application/json"}, status=415)
            return False
        return True

    def _headers(self, content_type: str, length: int, status: int = 200, cache: str = "no-store") -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(length))
        self.send_header("Cache-Control", cache)
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Cross-Origin-Resource-Policy", "same-origin")
        self.end_headers()

    def _json(self, payload: object, status: int = 200) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self._headers("application/json; charset=utf-8", len(body), status)
        self.wfile.write(body)

    def _html(self, content: str) -> None:
        body = content.encode("utf-8")
        self._headers("text/html; charset=utf-8", len(body))
        self.wfile.write(body)

    def _file(self, path: Path, content_type: str) -> None:
        body = path.read_bytes()
        self._headers(content_type, len(body), cache="private, max-age=60")
        self.wfile.write(body)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="微信公众号贴图草稿发布器")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=int(os.environ.get("WECHAT_DRAFT_PORT", "8876")))
    parser.add_argument("--no-browser", action="store_true")
    args = parser.parse_args(argv)
    if args.host not in LOCAL_HOSTS:
        raise SystemExit("安全限制：当前版本只允许监听 127.0.0.1/localhost")
    rescan()
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    url = f"http://127.0.0.1:{args.port}"
    print(f"公众号贴图草稿发布器已启动：{url}")
    print(f"配置文件：{SETTINGS.path}")
    if not args.no_browser:
        threading.Timer(0.6, lambda: webbrowser.open(url)).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


INDEX_HTML = r'''<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>公众号贴图草稿发布器</title>
<style>
:root{--bg:#f4f6f8;--panel:#fff;--line:#dfe4e8;--text:#20252b;--muted:#747d87;--green:#238b57;--warn:#a76b00;--bad:#b42318}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:14px/1.5 "Microsoft YaHei",sans-serif;height:100vh;overflow:hidden}
header{height:58px;background:var(--panel);border-bottom:1px solid var(--line);display:flex;align-items:center;gap:10px;padding:0 16px}header b{font-size:18px}.spacer{flex:1}
button,input,textarea,select{font:inherit}button{border:1px solid var(--line);background:#fff;border-radius:8px;padding:8px 12px;cursor:pointer}button.primary{background:var(--green);color:#fff;border-color:var(--green)}button:disabled{opacity:.5;cursor:not-allowed}
.shell{display:grid;grid-template-columns:320px minmax(0,1fr);height:calc(100vh - 58px)}aside{border-right:1px solid var(--line);background:var(--panel);display:flex;flex-direction:column;min-width:0}.rootbar{padding:12px;border-bottom:1px solid var(--line)}.rootbar input{width:100%;padding:9px;border:1px solid var(--line);border-radius:8px;margin-bottom:8px}.root-actions{display:flex;gap:8px}.tree{overflow:auto;padding:10px;flex:1}.node{margin:2px 0}.node-row{display:flex;align-items:center;gap:6px;padding:7px 8px;border-radius:7px;cursor:pointer}.node-row:hover{background:#f0f4f2}.node-row.active{background:#e5f3eb;color:#14683e}.children{padding-left:16px}.badge{font-size:11px;color:var(--muted);margin-left:auto}.invalid{color:var(--bad)}
main{overflow:auto;padding:18px}.empty{height:100%;display:grid;place-items:center;color:var(--muted)}.post{display:grid;grid-template-columns:minmax(360px,1fr) minmax(320px,520px);gap:18px;max-width:1400px;margin:auto}.panel{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:16px}.panel h2,.panel h3{margin:0 0 12px}.gallery{display:grid;grid-template-columns:repeat(auto-fill,minmax(130px,1fr));gap:10px}.gallery img{width:100%;aspect-ratio:3/4;object-fit:cover;border-radius:9px;background:#eee}.field{margin:12px 0}.field label{display:flex;justify-content:space-between;color:var(--muted);margin-bottom:6px}.field input,.field textarea,.field select{width:100%;border:1px solid var(--line);border-radius:8px;padding:10px;background:#fff}.field textarea{min-height:340px;resize:vertical}.warning{background:#fff7e6;color:var(--warn);padding:9px 11px;border-radius:8px;margin:7px 0}.error{background:#fff0ee;color:var(--bad);padding:9px 11px;border-radius:8px;margin:7px 0}.status{white-space:pre-wrap;padding:10px;background:#f6f8fa;border-radius:8px;min-height:42px}.history{margin-top:14px}.history-item{border-top:1px solid var(--line);padding:9px 0}.small{font-size:12px;color:var(--muted)}
@media(max-width:900px){.shell{grid-template-columns:260px 1fr}.post{grid-template-columns:1fr}.field textarea{min-height:220px}}
</style></head><body>
<header><b>公众号贴图草稿发布器</b><span class="small">纯官方 API · 只保存草稿，不正式发布</span><span class="spacer"></span><button id="openWechat">打开公众号后台</button><button id="rescan">刷新成品库</button></header>
<div class="shell"><aside><div class="rootbar"><input id="rootPath" placeholder="设置本地成品库目录"><div class="root-actions"><button id="pickRoot">选择目录</button><button id="saveRoot">使用此目录</button></div><div id="rootHint" class="small"></div></div><div id="tree" class="tree"></div></aside>
<main><div id="empty" class="empty">从左侧选择一篇帖子</div><div id="post" class="post" hidden><section class="panel"><h2 id="postName"></h2><div id="messages"></div><div id="gallery" class="gallery"></div></section><section class="panel"><h3>草稿内容</h3><div class="field"><label><span>公众号账号</span></label><select id="account"></select></div><div class="field"><label><span>标题</span><span id="titleCount"></span></label><input id="title"></div><div class="field"><label><span>正文</span><span id="bodyCount"></span></label><textarea id="body"></textarea></div><label><input type="checkbox" id="dryRun" checked> 测试模式（不调用微信接口）</label><label style="margin-left:12px"><input type="checkbox" id="force"> 强制重复创建</label><div style="margin-top:12px"><button class="primary" id="publish">创建公众号草稿</button></div><div id="status" class="status" style="margin-top:12px">尚未执行</div><div class="history"><h3>最近记录</h3><div id="history"></div></div></section></div></main></div>
<script>
const $=id=>document.getElementById(id);let settings={},selected=null;
async function api(url,options={}){const r=await fetch(url,options);const j=await r.json();if(!r.ok||j.ok===false)throw Object.assign(new Error(j.error||`HTTP ${r.status}`),{data:j,status:r.status});return j}
function postJson(url,data){return api(url,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(data)})}
function esc(s){return String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
async function loadSettings(){const data=await api('/api/settings');settings=data;$('rootPath').value=data.library_root||'';$('rootHint').textContent=`配置：${data.settings_path}`;const select=$('account');select.innerHTML='';Object.entries(data.accounts||{}).forEach(([id,a])=>{const o=document.createElement('option');o.value=id;o.textContent=a.name||id;if(id===data.default_account)o.selected=true;select.appendChild(o)});if(!select.options.length){const o=document.createElement('option');o.textContent='请先在配置文件中添加公众号账号';o.value='';select.appendChild(o)}}
function renderNode(node,parent){const wrap=document.createElement('div');wrap.className='node';const row=document.createElement('div');row.className='node-row';const icon=node.kind==='post'?'🖼️':node.kind==='root'?'📦':'📁';const bad=node.post&&!node.post.valid;row.innerHTML=`<span>${icon}</span><span class="${bad?'invalid':''}">${esc(node.name)}</span>${node.kind==='post'?`<span class="badge">${node.post.image_count}图</span>`:''}`;wrap.appendChild(row);if(node.kind==='post'){row.onclick=()=>selectPost(node,row)}else{const kids=document.createElement('div');kids.className='children';(node.children||[]).forEach(c=>renderNode(c,kids));wrap.appendChild(kids);row.onclick=()=>{kids.hidden=!kids.hidden}}parent.appendChild(wrap)}
async function loadTree(){const data=await api('/api/tree');const tree=$('tree');tree.innerHTML='';if(!data.tree){tree.innerHTML='<div class="small">请先设置成品库目录</div>';return}renderNode(data.tree,tree);$('rootHint').textContent=`已识别 ${data.post_count} 篇帖子 · ${settings.library_root||''}`}
async function selectPost(node,row){document.querySelectorAll('.node-row.active').forEach(x=>x.classList.remove('active'));row.classList.add('active');const data=await api('/api/post?id='+encodeURIComponent(node.id));selected=data.post;$('empty').hidden=true;$('post').hidden=false;$('postName').textContent=selected.name;$('title').value=selected.suggested_title||selected.original_title;$('body').value=selected.body;updateCounts();const messages=$('messages');messages.innerHTML=[...(selected.warnings||[]).map(x=>`<div class="warning">${esc(x)}</div>`),...(selected.errors||[]).map(x=>`<div class="error">${esc(x)}</div>`)].join('');$('gallery').innerHTML=selected.image_urls.map((u,i)=>`<div><img src="${u}"><div class="small">${i+1}. ${esc(selected.image_names[i])}</div></div>`).join('');$('publish').disabled=!selected.valid;}
function updateCounts(){$('titleCount').textContent=`${Array.from($('title').value).length} 字`;$('bodyCount').textContent=`${Array.from($('body').value).length} 字`}
async function loadHistory(){const data=await api('/api/history?limit=12');$('history').innerHTML=(data.items||[]).map(x=>`<div class="history-item"><b>${esc(x.final_title)}</b><div class="small">${esc(x.status)} · ${new Date(x.created_at*1000).toLocaleString()} · ${esc(x.draft_media_id||x.error_message||'')}</div></div>`).join('')||'<div class="small">暂无记录</div>'}
$('title').oninput=updateCounts;$('body').oninput=updateCounts;
$('saveRoot').onclick=async()=>{try{await postJson('/api/settings/library-root',{path:$('rootPath').value});await loadSettings();await loadTree()}catch(e){alert(e.message)}};
$('pickRoot').onclick=async()=>{try{const data=await postJson('/api/pick-library-root',{});if(data.selected)$('rootPath').value=data.selected;await loadSettings();await loadTree()}catch(e){alert(e.message)}};
$('rescan').onclick=async()=>{try{await postJson('/api/rescan',{});await loadTree()}catch(e){alert(e.message)}};
$('openWechat').onclick=()=>window.open('https://mp.weixin.qq.com/','_blank','noopener');
$('publish').onclick=async()=>{if(!selected)return;const btn=$('publish');btn.disabled=true;$('status').textContent='正在读取图片并创建草稿…';try{const data=await postJson('/api/publish',{post_id:selected.id,account_id:$('account').value,title:$('title').value,body:$('body').value,dry_run:$('dryRun').checked,force:$('force').checked});$('status').textContent=`成功：${data.status}\n草稿 media_id：${data.draft_media_id}\n${(data.warnings||[]).join('\n')}`;await loadHistory()}catch(e){if(e.data&&e.data.code==='DUPLICATE'){$('status').textContent=`已阻止重复创建。上次草稿：${e.data.existing.draft_media_id||''}`}else{$('status').textContent='失败：'+e.message}}finally{btn.disabled=!(selected&&selected.valid)}};
(async()=>{try{await loadSettings();await loadTree();await loadHistory()}catch(e){$('rootHint').textContent=e.message}})();
</script></body></html>'''


if __name__ == "__main__":
    raise SystemExit(main())
