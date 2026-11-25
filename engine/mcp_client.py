#!/usr/bin/env python3
"""
ContentStudio Remote MCP Client (engine/mcp_client.py)
Implementasi klien Remote Model Context Protocol (MCP) JSON-RPC 2.0 (Streamable HTTP / SSE)
menggunakan Python standard library murni (urllib/http.client):
- Membaca konfigurasi dari mcp.json di root data persisten
- Auto-Discovery tools: tools/list -> mcp__<server>__<tool>
- Eksekusi aman: tools/call dengan timeout 8 detik anti-crash
"""

import sys
import os

# Mencegah direktori engine/ membayangi modul library standar Python
_script_dir = os.path.dirname(os.path.abspath(__file__))
if sys.path and sys.path[0] == _script_dir:
    sys.path.pop(0)
_root_dir = os.path.dirname(_script_dir)
if _root_dir not in sys.path:
    sys.path.append(_root_dir)

import json
import urllib.request
import urllib.parse
import urllib.error
from typing import Dict, Any, List, Optional, Tuple
from engine.runtime import get_root_dir

def get_mcp_config_path() -> str:
    """Membaca path file mcp.json (memprioritaskan /sdcard/ContentStudio/mcp.json jika writable)."""
    sdcard_path = "/sdcard/ContentStudio/mcp.json"
    if os.path.exists("/sdcard/ContentStudio") and os.access("/sdcard/ContentStudio", os.W_OK):
        return sdcard_path
    return os.path.join(get_root_dir(), "mcp.json")

def load_mcp_config() -> Dict[str, Any]:
    """Membaca konfigurasi server MCP."""
    cfg_file = get_mcp_config_path()
    if os.path.exists(cfg_file):
        try:
            with open(cfg_file, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, dict):
                    if "servers" not in data:
                        data["servers"] = {}
                    return data
        except Exception:
            pass
    return {"servers": {}}

def save_mcp_config(data: Dict[str, Any]) -> None:
    """Menyimpan konfigurasi server MCP ke file mcp.json."""
    cfg_file = get_mcp_config_path()
    os.makedirs(os.path.dirname(cfg_file), exist_ok=True)
    with open(cfg_file, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)

def list_mcp_servers() -> Dict[str, Any]:
    """Mengembalikan daftar seluruh server MCP yang terkonfigurasi."""
    cfg = load_mcp_config()
    servers = cfg.get("servers", {})
    res = []
    for name, s_info in servers.items():
        res.append({
            "name": name,
            "url": s_info.get("url", ""),
            "enabled": bool(s_info.get("enabled", True)),
            "has_headers": bool(s_info.get("headers"))
        })
    return {"status": "ok", "total": len(res), "servers": res}

def save_mcp_server(name: str, url: str, headers: Optional[Dict[str, str]] = None, enabled: bool = True) -> Dict[str, Any]:
    """Menambah atau memperbarui konfigurasi server MCP."""
    clean_name = name.strip()
    if not clean_name:
        return {"status": "error", "message": "Nama server tidak boleh kosong."}
    if not url.strip():
        return {"status": "error", "message": "URL server tidak boleh kosong."}

    cfg = load_mcp_config()
    cfg.setdefault("servers", {})[clean_name] = {
        "url": url.strip(),
        "headers": headers or {},
        "enabled": enabled
    }
    save_mcp_config(cfg)
    return {"status": "ok", "message": f"Server MCP '{clean_name}' berhasil disimpan."}

def delete_mcp_server(name: str) -> Dict[str, Any]:
    """Menghapus konfigurasi server MCP."""
    clean_name = name.strip()
    cfg = load_mcp_config()
    servers = cfg.get("servers", {})
    if clean_name in servers:
        del servers[clean_name]
        save_mcp_config(cfg)
        return {"status": "ok", "message": f"Server MCP '{clean_name}' berhasil dihapus."}
    return {"status": "error", "message": f"Server MCP '{clean_name}' tidak ditemukan."}

# ==============================================================================
# REMOTE JSON-RPC 2.0 PROTOCOL ENGINE (STREAMABLE HTTP & POST)
# ==============================================================================

def _send_jsonrpc(url: str, headers: Dict[str, str], method: str, params: Optional[Dict[str, Any]] = None, req_id: int = 1) -> Dict[str, Any]:
    """Mengirim request JSON-RPC 2.0 via HTTP POST murni dengan timeout 8 detik."""
    payload = {
        "jsonrpc": "2.0",
        "id": req_id,
        "method": method,
        "params": params or {}
    }
    body_bytes = json.dumps(payload).encode("utf-8")

    all_headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "User-Agent": "ContentStudio-MCPClient/1.0"
    }
    if headers:
        for k, v in headers.items():
            if v:
                all_headers[str(k)] = str(v)

    req = urllib.request.Request(
        url,
        data=body_bytes,
        headers=all_headers,
        method="POST"
    )

    with urllib.request.urlopen(req, timeout=8) as resp:
        resp_bytes = resp.read()
        resp_text = resp_bytes.decode("utf-8")

        # Jika server merespons dalam format SSE (data: {...})
        if "data:" in resp_text:
            lines = [l.strip() for l in resp_text.splitlines() if l.startswith("data:")]
            if lines:
                last_data = lines[-1][5:].strip()
                try:
                    return json.loads(last_data)
                except Exception:
                    pass

        try:
            return json.loads(resp_text)
        except Exception:
            return {"error": {"code": -32700, "message": f"Invalid JSON response: {resp_text[:100]}"}}

def get_server_tools(server_name: str, server_info: Dict[str, Any]) -> List[Dict[str, Any]]:
    """
    Mengambil daftar tool dari suatu server MCP (tools/list)
    dan mengonversinya ke format skema OpenAI function dengan nama mcp__<server>__<tool>.
    """
    url = server_info.get("url", "")
    headers = server_info.get("headers", {})
    if not url:
        return []

    try:
        # 1. Coba initialize (jika server membutuhkan handshake)
        try:
            _send_jsonrpc(url, headers, "initialize", {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "ContentStudio", "version": "1.0"}
            }, req_id=1)
        except Exception:
            pass

        # 2. Panggil tools/list
        rpc_resp = _send_jsonrpc(url, headers, "tools/list", {}, req_id=2)
        if "error" in rpc_resp:
            print(f"[!] MCP '{server_name}' error: {rpc_resp['error'].get('message')}", file=sys.stderr)
            return []

        tools_raw = rpc_resp.get("result", {}).get("tools", [])
        converted_tools = []

        for t in tools_raw:
            raw_name = t.get("name", "")
            if not raw_name:
                continue

            scoped_name = f"mcp__{server_name}__{raw_name}"
            desc = t.get("description") or f"MCP tool '{raw_name}' from server '{server_name}'"
            input_schema = t.get("inputSchema") or {"type": "object", "properties": {}}

            converted_tools.append({
                "type": "function",
                "function": {
                    "name": scoped_name,
                    "description": f"[{server_name}] {desc}",
                    "parameters": input_schema
                }
            })

        return converted_tools

    except (urllib.error.URLError, TimeoutError, OSError) as e:
        print(f"[!] Server MCP '{server_name}' offline/timeout: {e}", file=sys.stderr)
        return []
    except Exception as e:
        print(f"[!] Gagal mengambil tools dari MCP '{server_name}': {e}", file=sys.stderr)
        return []

def get_all_mcp_tools() -> List[Dict[str, Any]]:
    """Mengumpulkan seluruh tools dari seluruh server MCP yang aktif (enabled=True)."""
    cfg = load_mcp_config()
    servers = cfg.get("servers", {})
    all_tools = []

    for name, s_info in servers.items():
        if not s_info.get("enabled", True):
            continue
        s_tools = get_server_tools(name, s_info)
        all_tools.extend(s_tools)

    return all_tools

def is_mcp_tool(name: str) -> bool:
    """Mengecek apakah suatu nama fungsi adalah tool MCP."""
    return name.startswith("mcp__")

def parse_mcp_tool_name(scoped_name: str) -> Tuple[str, str]:
    """Memecah 'mcp__server__tool' menjadi ('server', 'tool')."""
    parts = scoped_name.split("__", 2)
    if len(parts) >= 3 and parts[0] == "mcp":
        return parts[1], parts[2]
    return "", scoped_name

def call_tool(server_name: str, tool_name: str, arguments: Optional[Dict[str, Any]] = None) -> str:
    """Mengeksekusi tool MCP jarak jauh berdasarkan nama server dan nama tool (tools/call)."""
    if not server_name or not tool_name:
        return f"[ERR] Nama server/tool MCP tidak valid: server={server_name}, tool={tool_name}"

    cfg = load_mcp_config()
    server_info = cfg.get("servers", {}).get(server_name)
    if not server_info:
        return f"[ERR] Server MCP '{server_name}' tidak ditemukan di mcp.json"

    url = server_info.get("url", "")
    headers = server_info.get("headers", {})

    try:
        rpc_resp = _send_jsonrpc(
            url=url,
            headers=headers,
            method="tools/call",
            params={
                "name": tool_name,
                "arguments": arguments or {}
            },
            req_id=3
        )

        if "error" in rpc_resp:
            err = rpc_resp["error"]
            return f"[ERR MCP] {err.get('message', 'Unknown error')} (code {err.get('code')})"

        res_obj = rpc_resp.get("result", {})
        content_items = res_obj.get("content", [])
        if content_items:
            texts = []
            for item in content_items:
                if isinstance(item, dict):
                    if item.get("type") == "text":
                        texts.append(item.get("text", ""))
                    else:
                        texts.append(json.dumps(item))
                else:
                    texts.append(str(item))
            return "\n".join(texts)

        return json.dumps(res_obj, ensure_ascii=False)

    except (urllib.error.URLError, TimeoutError, OSError) as e:
        return f"[ERR MCP] Server '{server_name}' offline atau timeout (batas 8 detik): {e}"
    except Exception as e:
        return f"[ERR MCP] Gagal mengeksekusi tool '{tool_name}': {e}"

def call_mcp_tool(scoped_name: str, arguments: Dict[str, Any]) -> str:
    """Mengeksekusi tool MCP jarak jauh (tools/call) dari nama ber-prefix mcp__<server>__<tool>."""
    server_name, actual_tool_name = parse_mcp_tool_name(scoped_name)
    if not server_name:
        return f"[ERR] Format nama tool MCP tidak valid: {scoped_name}"
    return call_tool(server_name, actual_tool_name, arguments)

if __name__ == "__main__":
    print("Remote MCP Client Status:")
    print(json.dumps(list_mcp_servers(), indent=2))
