#!/usr/bin/env python3
"""
ContentStudio Unified Multi-Engine Agent Hub (V3.7)

Satu pintu untuk 12 provider gratis:
- Smart Auto Router berbasis tugas (task_router)
- Zero-loss context handoff (context_manager)
- Live quota tracking (usage_tracker)
- Multimodal native: Gemini inlineData & OpenAI-compatible image_url
- CLI agent Antigravity/Cline menerima blok [SESSION HANDOFF]
- Auto-fallback saat 429/402/503/timeout, dengan [META] ke stderr
"""

import os
import sys

# Cegah engine/ membayangi modul stdlib 'operator' saat dijalankan langsung.
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if sys.path and sys.path[0] == _SCRIPT_DIR:
    sys.path.pop(0)
_ROOT_DIR = os.path.dirname(_SCRIPT_DIR)
if _ROOT_DIR not in sys.path:
    sys.path.append(_ROOT_DIR)


import base64
import json
import mimetypes
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from typing import Any, Callable, Dict, List, Optional, Tuple


from engine import context_manager as cm
from engine import provider_config as pc
from engine import task_router as tr
from engine import usage_tracker as ut

IMAGE_EXTENSIONS = (".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic")
VIDEO_EXTENSIONS = (".mp4", ".mov", ".mkv", ".webm", ".avi")
MAX_LOOP = 8
MAX_IMAGE_BYTES = 6 * 1024 * 1024
RESIZE_TARGET_BYTES = 1_500_000


class HubError(Exception):
    def __init__(self, status: int, message: str, provider: str = "", retry_after: int = 0):
        super().__init__(message)
        self.status = int(status or 0)
        self.provider = provider
        self.retry_after = int(retry_after or 0)


def _emit_think(message: str) -> None:
    try:
        print(f"[THINK] {message}", file=sys.stderr, flush=True)
    except Exception:
        pass


def _emit_step(message: str) -> None:
    try:
        print(f"[STEP] {message}", file=sys.stderr, flush=True)
    except Exception:
        pass


def _emit_meta(meta: Dict[str, Any]) -> None:
    try:
        print("[META] " + json.dumps(meta, ensure_ascii=False), file=sys.stderr, flush=True)
    except Exception:
        pass


# ---------------------------------------------------------------------------
# Multimodal helpers
# ---------------------------------------------------------------------------

def _ffmpeg_bin() -> Optional[str]:
    env_bin = os.environ.get("FFMPEG_BIN")
    if env_bin and os.path.exists(env_bin):
        return env_bin
    found = shutil.which("ffmpeg")
    if found:
        return found
    candidates = [
        os.path.join(os.path.expanduser("~"), ".cache", "bin", "ffmpeg"),
        "/data/user/0/com.agy/files/usr/bin/ffmpeg",
    ]
    for c in candidates:
        if os.path.exists(c):
            return c
    return None


def _resize_image(path: str) -> str:
    """Kompres gambar > 1.5 MB memakai Pillow bila tersedia."""
    try:
        if os.path.getsize(path) <= RESIZE_TARGET_BYTES:
            return path
        from PIL import Image
        img = Image.open(path)
        if max(img.size) > 1600:
            img.thumbnail((1600, 1600))
        out_dir = os.path.join(tempfile.gettempdir(), "contentstudio_vision")
        os.makedirs(out_dir, exist_ok=True)
        out = os.path.join(out_dir, os.path.splitext(os.path.basename(path))[0] + "_small.jpg")
        if img.mode not in ("RGB", "L"):
            img = img.convert("RGB")
        quality = 82
        while quality >= 45:
            img.save(out, "JPEG", quality=quality, optimize=True)
            if os.path.getsize(out) <= RESIZE_TARGET_BYTES:
                return out
            quality -= 10
        return out
    except Exception:
        return path


def make_contact_sheet(video_path: str) -> Optional[str]:
    """Buat contact sheet 3x3 dari video memakai ffmpeg."""
    ffmpeg = _ffmpeg_bin()
    if not ffmpeg or not os.path.exists(video_path):
        return None
    out_dir = os.path.join(tempfile.gettempdir(), "contentstudio_vision")
    try:
        os.makedirs(out_dir, exist_ok=True)
        out = os.path.join(out_dir, os.path.splitext(os.path.basename(video_path))[0] + "_sheet.jpg")
        vf = "fps=1/2,scale=480:-1:force_original_aspect_ratio=decrease,tile=3x3"
        cmd = [ffmpeg, "-y", "-i", video_path, "-vf", vf, "-frames:v", "1", "-q:v", "3", out]
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
        if proc.returncode == 0 and os.path.exists(out) and os.path.getsize(out) > 1024:
            return out
    except Exception:
        return None
    return None


def encode_attachment(path: str) -> Optional[Dict[str, Any]]:
    """Siapkan satu lampiran menjadi base64 + mime; video → contact sheet 3x3."""
    try:
        if not path or not os.path.exists(path):
            return None
        ext = os.path.splitext(path)[1].lower()
        source = path
        frames: List[str] = []
        if ext in VIDEO_EXTENSIONS:
            sheet = make_contact_sheet(path)
            if not sheet:
                return {"path": path, "kind": "video", "mime": "video/mp4", "base64": "",
                        "data_url": "", "skipped": "ffmpeg tidak tersedia", "frames": []}
            source = sheet
            frames = [sheet]
        if os.path.getsize(source) > MAX_IMAGE_BYTES:
            return {"path": path, "kind": "image", "skipped": "file terlalu besar", "base64": "",
                    "data_url": "", "mime": "", "frames": frames}
        source = _resize_image(source)
        mime = mimetypes.guess_type(source)[0] or ("image/jpeg" if ext in IMAGE_EXTENSIONS else "application/octet-stream")
        if not mime.startswith("image/"):
            mime = "image/jpeg"
        with open(source, "rb") as f:
            raw = f.read()
        b64 = base64.b64encode(raw).decode("ascii")
        return {
            "path": path,
            "source": source,
            "kind": "image",
            "mime": mime,
            "base64": b64,
            "data_url": f"data:{mime};base64,{b64}",
            "frames": frames,
        }
    except Exception:
        return None


def prepare_attachments(paths: Optional[List[str]]) -> List[Dict[str, Any]]:
    out = []
    for p in paths or []:
        item = encode_attachment(p)
        if item:
            out.append(item)
    return out


def build_vision_content(provider: str, text: str, attachments: Optional[List[str]]) -> Dict[str, Any]:
    """Payload visual siap pakai: Gemini inlineData atau OpenAI image_url."""
    prepared = prepare_attachments(attachments)
    images = [p for p in prepared if p.get("base64")]
    if (provider or "").lower() == "gemini":
        parts: List[Dict[str, Any]] = [{"text": text}]
        for item in images:
            parts.append({"inlineData": {"mimeType": item["mime"], "data": item["base64"]}})
        return {"parts": parts, "images": len(images), "prepared": prepared}
    content: List[Dict[str, Any]] = [{"type": "text", "text": text}]
    for item in images:
        content.append({"type": "image_url", "image_url": {"url": item["data_url"]}})
    return {"content": content, "images": len(images), "prepared": prepared}


# ---------------------------------------------------------------------------
# Reasoning extractor
# ---------------------------------------------------------------------------

def extract_reasoning(text: str, reasoning_content: str = "") -> Tuple[str, str]:
    """Pisahkan  (DeepSeek/R1) dari jawaban final."""
    raw = str(text or "")
    thoughts: List[str] = []

    def _capture(match: re.Match) -> str:
        thoughts.append(match.group(1).strip())
        return ""

    clean = re.sub(r"<think(?:ing)?>([\s\S]*?)</think(?:ing)?>", _capture, raw, flags=re.IGNORECASE)
    clean = re.sub(r"<think(?:ing)?>[\s\S]*$", "", clean, flags=re.IGNORECASE)
    clean = re.sub(r"</think(?:ing)?>", "", clean, flags=re.IGNORECASE).strip()
    if reasoning_content and str(reasoning_content).strip():
        thoughts.insert(0, str(reasoning_content).strip())
    return clean.strip(), "\n".join(t for t in thoughts if t)


# ---------------------------------------------------------------------------
# HTTP adapters
# ---------------------------------------------------------------------------

def _headers_for(provider: str, key: str, model: str) -> Dict[str, str]:
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if key and key != "cli":
        headers["Authorization"] = f"Bearer {key}"
    if provider == "openrouter":
        headers["HTTP-Referer"] = "https://contentstudio.local"
        headers["X-Title"] = "ContentStudio V3.7"
    return headers


def _endpoint(provider: str, model: str, cfg: Dict[str, Any], stream: bool = False) -> Tuple[str, Dict[str, str]]:
    key = pc.resolve_key(provider, cfg)
    base = pc.resolve_base_url(provider, cfg).rstrip("/")
    headers = _headers_for(provider, key, model)
    if provider == "gemini":
        action = "streamGenerateContent?alt=sse" if stream else "generateContent"
        sep = "&" if "?" in action else "?"
        return f"{base}/models/{model}:{action}{sep}key={key}", headers
    if not base:
        raise HubError(0, "Base URL provider kosong", provider)
    return f"{base}/chat/completions", headers


def _headers_to_dict(headers: Any) -> Dict[str, str]:
    try:
        return {str(k): str(v) for k, v in headers.items()}
    except Exception:
        return {}


def _raise_for_http(provider: str, exc: "urllib.error.HTTPError") -> None:
    status = int(getattr(exc, "code", 0) or 0)
    retry_after = 0
    try:
        retry_after = int(exc.headers.get("retry-after") or 0)
    except Exception:
        retry_after = 0
    try:
        body = exc.read().decode("utf-8", errors="replace")[:400]
    except Exception:
        body = ""
    raise HubError(status, body or f"HTTP {status}", provider, retry_after)


def _post_json(provider: str, url: str, headers: Dict[str, str],
               payload: Dict[str, Any], timeout: float = 180.0) -> Dict[str, Any]:
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            ut.update_from_headers(provider, _headers_to_dict(resp.headers))
            body = resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as exc:
        try:
            ut.update_from_headers(provider, _headers_to_dict(exc.headers))
        except Exception:
            pass
        _raise_for_http(provider, exc)
    except Exception as exc:
        raise HubError(0, f"Koneksi gagal: {exc}", provider)
    try:
        return json.loads(body)
    except Exception:
        raise HubError(0, f"Respons bukan JSON valid: {body[:200]}", provider)


def _parse_gemini_response(data: Dict[str, Any]) -> Dict[str, Any]:
    candidates = data.get("candidates") or []
    cand = candidates[0] if candidates else {}
    parts = ((cand.get("content") or {}).get("parts") or [])
    texts, tool_calls = [], []
    for part in parts:
        if isinstance(part.get("text"), str):
            texts.append(part["text"])
        fc = part.get("functionCall")
        if isinstance(fc, dict):
            tool_calls.append({
                "id": f"gemini_{len(tool_calls) + 1}",
                "name": fc.get("name", ""),
                "arguments": fc.get("args") if isinstance(fc.get("args"), dict) else {},
            })
    usage = data.get("usageMetadata") or {}
    return {
        "content": "\n".join(texts).strip(),
        "tool_calls": tool_calls,
        "tokens": int(usage.get("totalTokenCount") or 0),
        "model": str(cand.get("modelVersion") or ""),
    }


def _parse_openai_response(data: Dict[str, Any]) -> Dict[str, Any]:
    choices = data.get("choices") or []
    msg = (choices[0].get("message") if choices else {}) or {}
    content = msg.get("content") or ""
    if isinstance(content, list):
        content = " ".join(str(p.get("text") or "") for p in content if isinstance(p, dict))
    reasoning = msg.get("reasoning_content") or msg.get("reasoning") or ""
    tool_calls = []
    for tc in msg.get("tool_calls") or []:
        fn = tc.get("function") or {}
        raw_args = fn.get("arguments")
        args: Dict[str, Any] = {}
        if isinstance(raw_args, dict):
            args = raw_args
        elif isinstance(raw_args, str):
            try:
                args = json.loads(raw_args)
            except Exception:
                args = {}
        tool_calls.append({"id": tc.get("id") or f"call_{len(tool_calls) + 1}",
                           "name": fn.get("name", ""), "arguments": args})
    usage = data.get("usage") or {}
    return {
        "content": str(content),
        "reasoning": str(reasoning),
        "tool_calls": tool_calls,
        "tokens": int(usage.get("total_tokens") or 0),
        "model": str(data.get("model") or ""),
    }


def _base_system_prompt(mode: str = "director") -> str:
    mode_line = {
        "director": "Mode SUTRADARA: pimpin produksi dari naskah sampai render.",
        "scriptwriter": "Mode PENULIS NASKAH: fokus naskah, hook, pacing.",
        "researcher": "Mode PENELITI: fokus riset, referensi, dan kurasi bahan.",
        "fast": "Mode CEPAT: jawab dan eksekusi seefisien mungkin.",
    }.get(mode, "Mode SUTRADARA.")
    return (
        "Kamu adalah ContentStudio Autonomous AI Agent V3.7, sutradara & produser video.\n"
        f"{mode_line}\n"
        "ATURAN KERAS:\n"
        "1. Dukung short & long-form serta rasio 16:9, 9:16, 1:1, 4:5.\n"
        "2. Jangan menyuruh user klik manual; gunakan tool untuk mengeksekusi.\n"
        "3. Progres hanya ke stderr lewat marker [STEP]/[THINK]; jawaban utama bersih.\n"
        "4. Bahasa Indonesia ringkas, langsung ke inti, tanpa filler.\n"
        "5. Jangan membaca ulang seluruh proyek dari nol bila konteks handoff sudah tersedia.\n"
    )


TOOL_SCHEMAS: List[Dict[str, Any]] = [
    {"type": "function", "function": {
        "name": "run_studio_cli",
        "description": "Menjalankan Master CLI ContentStudio (project, script, scene, media, render, dna, topic-history).",
        "parameters": {"type": "object", "properties": {
            "args": {"type": "array", "items": {"type": "string"},
                     "description": "Argumen CLI, contoh: ['project','create','--name','demo']"}},
            "required": ["args"]}}},
    {"type": "function", "function": {
        "name": "read_workspace_file",
        "description": "Membaca file teks proyek (project.json, plan.json, dsb).",
        "parameters": {"type": "object", "properties": {
            "path": {"type": "string"}}, "required": ["path"]}}},
    {"type": "function", "function": {
        "name": "write_workspace_file",
        "description": "Menulis/memperbarui file teks proyek.",
        "parameters": {"type": "object", "properties": {
            "path": {"type": "string"}, "content": {"type": "string"}},
            "required": ["path", "content"]}}},
]


def _gemini_tools(schemas: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    decls = []
    for schema in schemas:
        fn = schema.get("function") or {}
        decls.append({"name": fn.get("name"), "description": fn.get("description"),
                      "parameters": fn.get("parameters")})
    return [{"functionDeclarations": decls}]


def _execute_tool(name: str, args: Dict[str, Any]) -> str:
    try:
        from engine.operator import execute_local_tool, load_session
        mapping = {"run_studio_cli": "run_studio_cli", "read_workspace_file": "read_file",
                   "write_workspace_file": "write_file"}
        tool_name = mapping.get(name, name)
        _emit_step(f"Menjalankan tool: {name}")
        return execute_local_tool(tool_name, args, load_session())
    except Exception as exc:
        return f"[ERR] Tool {name} gagal: {exc}"


def direct_chat(provider: str, model: str, handoff: Dict[str, Any], user_prompt: str,
                attachments: Optional[List[str]] = None, effort: str = "medium",
                max_tokens: int = 4096, tools: bool = True,
                on_delta: Optional[Callable[[str], None]] = None,
                cfg: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """Satu panggilan Direct API lengkap dengan loop tool-calling (maks 8)."""
    cfg = cfg or pc.load_config()
    provider = (provider or "").lower()
    base_messages = list(handoff.get("messages") or [])
    system = str(handoff.get("system") or "")

    if provider == "gemini":
        vision = build_vision_content("gemini", user_prompt, attachments)
        contents = list(handoff.get("contents") or [])
        contents.append({"role": "user", "parts": vision["parts"]})
        payload: Dict[str, Any] = {
            "systemInstruction": {"parts": [{"text": system}]} if system else None,
            "contents": contents,
            "generationConfig": {"temperature": 0.7, "maxOutputTokens": max_tokens},
        }
        if tools:
            payload["tools"] = _gemini_tools(TOOL_SCHEMAS)
    else:
        vision = build_vision_content("openai", user_prompt, attachments)
        messages = [{"role": "system", "content": system}] if system else []
        messages.extend(base_messages)
        messages.append({"role": "user", "content": vision["content"] if vision["images"] else user_prompt})
        payload = {"model": model, "messages": messages, "temperature": 0.7, "max_tokens": max_tokens}
        if provider in ("groq", "mistral", "zai", "nvidia", "sambanova", "openrouter", "custom", "cloudflare", "cohere"):
            payload["stream"] = False
        if tools:
            payload["tools"] = TOOL_SCHEMAS
            payload["tool_choice"] = "auto"

    if effort in ("low", "medium", "high"):
        payload["reasoning_effort"] = effort

    steps: List[str] = []
    total_tokens = 0
    final_text = ""
    loop_messages = payload.get("messages")
    loop_contents = payload.get("contents")

    for iteration in range(MAX_LOOP):
        url, headers = _endpoint(provider, model, cfg)
        data = _post_json(provider, url, headers, payload)
        if provider == "gemini":
            parsed = _parse_gemini_response(data)
        else:
            parsed = _parse_openai_response(data)
        total_tokens += int(parsed.get("tokens") or 0)
        clean, thought = extract_reasoning(parsed.get("content") or "", parsed.get("reasoning") or "")
        if thought:
            for line in thought.splitlines()[:6]:
                if line.strip():
                    _emit_think(line.strip()[:220])
        if clean:
            final_text = clean
        calls = parsed.get("tool_calls") or []
        if not calls:
            break
        # Catat tool_call assistant + hasil tool.
        if provider == "gemini":
            loop_contents.append({"role": "model", "parts": [
                {"functionCall": {"name": c["name"], "args": c["arguments"]}} for c in calls
            ]})
            for call in calls:
                output = _execute_tool(call["name"], call["arguments"] or {})
                steps.append(f"{call['name']}: {str(output)[:160]}")
                loop_contents.append({"role": "user", "parts": [
                    {"functionResponse": {"name": call["name"], "response": {"result": str(output)[:4000]}}}
                ]})
            payload["contents"] = loop_contents
        else:
            assistant_msg: Dict[str, Any] = {"role": "assistant", "content": clean or None,
                                             "tool_calls": [
                                                 {"id": c["id"], "type": "function",
                                                  "function": {"name": c["name"],
                                                               "arguments": json.dumps(c["arguments"])}}
                                                 for c in calls]}
            loop_messages.append(assistant_msg)
            for call in calls:
                output = _execute_tool(call["name"], call["arguments"] or {})
                steps.append(f"{call['name']}: {str(output)[:160]}")
                loop_messages.append({"role": "tool", "tool_call_id": call["id"],
                                      "name": call["name"], "content": str(output)[:6000]})
            payload["messages"] = loop_messages
        if iteration == MAX_LOOP - 1:
            final_text = final_text or "Batas langkah tool tercapai. Lanjutkan dengan instruksi berikutnya."

    if on_delta and final_text:
        on_delta(final_text)
    return {
        "content": final_text,
        "tokens": total_tokens,
        "tool_steps": steps,
        "multimodal": bool(vision.get("images")),
        "model": model,
        "provider": provider,
    }


# ---------------------------------------------------------------------------
# CLI agent adapters
# ---------------------------------------------------------------------------

def _cli_binary(provider: str) -> Optional[str]:
    if provider == "antigravity":
        return os.environ.get("ANTIGRAVITY_BIN") or shutil.which("antigravity")
    if provider == "cline":
        return os.environ.get("CLINE_BIN") or shutil.which("cline")
    return None


def run_cli_agent(provider: str, model: str, handoff_block: str, prompt: str,
                  effort: str = "medium", timeout: int = 900) -> Dict[str, Any]:
    """Jalankan Antigravity/Cline headless dengan blok handoff dari context_manager."""
    provider = (provider or "").lower()
    binary = _cli_binary(provider)
    if not binary:
        raise HubError(0, f"CLI {provider} tidak ditemukan", provider)
    full_prompt = handoff_block + "\n\n" + prompt if handoff_block else prompt
    if provider == "antigravity":
        args = [binary, "--dangerously-skip-permissions"]
        if model and model not in ("default", "smart-auto"):
            args += ["--model", model]
        if effort in ("low", "medium", "high"):
            args += ["--effort", effort]
        args += ["-p", full_prompt]
    else:
        args = [binary, "--json", "--auto-approve", "true"]
        if model and model not in ("default", "smart-auto"):
            args += ["--model", model]
        args += ["-p", full_prompt]
    _emit_step(f"Menjalankan {provider} headless")
    try:
        proc = subprocess.run(args, capture_output=True, text=True, timeout=timeout,
                              cwd=_ROOT_DIR, env={**os.environ, "PYTHONUNBUFFERED": "1"})
    except subprocess.TimeoutExpired:
        raise HubError(0, f"{provider} timeout", provider)
    if proc.returncode != 0:
        raise HubError(proc.returncode, (proc.stderr or proc.stdout)[-400:], provider)
    raw = proc.stdout or ""
    if provider == "cline":
        lines = [ln for ln in raw.splitlines() if ln.strip()]
        texts = []
        for line in lines:
            try:
                obj = json.loads(line)
                if isinstance(obj, dict):
                    texts.append(str(obj.get("text") or obj.get("content") or ""))
            except Exception:
                continue
        if texts:
            raw = "\n".join(t for t in texts if t)
    clean, thought = extract_reasoning(raw, proc.stderr)
    if thought:
        _emit_think(thought[:220])
    return {"content": clean.strip(), "tokens": 0, "tool_steps": [], "provider": provider,
            "model": model, "multimodal": False}


# ---------------------------------------------------------------------------
# Working memory sync & turn orchestration
# ---------------------------------------------------------------------------

def _sync_working_memory(workspace: str, session_id: Optional[str]) -> None:
    project = workspace or ""
    patch: Dict[str, Any] = {"active_project": project}
    if project:
        try:
            from engine.runtime import get_workspaces_dir
            ws_dir = os.path.join(get_workspaces_dir(), project)
        except Exception:
            ws_dir = os.path.join(_ROOT_DIR, "workspaces", project)
        project_file = os.path.join(ws_dir, "project.json")
        if os.path.exists(project_file):
            try:
                with open(project_file, "r", encoding="utf-8") as f:
                    data = json.load(f)
                if isinstance(data, dict):
                    patch["aspect_ratio"] = data.get("aspect_ratio") or data.get("ratio") or ""
                    patch["format_scale"] = data.get("format_scale") or ("long" if data.get("long_form") else "")
            except Exception:
                pass
        try:
            from engine.reference_dna import load_reference_dna
            dna = load_reference_dna(project)
            patch["format_scale"] = dna.get("format_scale") or patch.get("format_scale") or ""
            patch["aspect_ratio"] = dna.get("aspect_ratio") or patch.get("aspect_ratio") or ""
        except Exception:
            pass
        mem_file = os.path.join(ws_dir, "memory.json")
        decisions: List[str] = []
        if os.path.exists(mem_file):
            try:
                with open(mem_file, "r", encoding="utf-8") as f:
                    mem = json.load(f)
                if isinstance(mem, list):
                    decisions = [str(m.get("note") or "") for m in mem if isinstance(m, dict)
                                 and str(m.get("category") or "") in ("decision", "rejected", "style")]
            except Exception:
                pass
        if decisions:
            patch["locked_decisions"] = decisions[-8:]
        report = os.path.join(ws_dir, "assets", "resolve_report.json")
        if os.path.exists(report):
            try:
                with open(report, "r", encoding="utf-8") as f:
                    rep = json.load(f)
                steps = [f"aset scene {r.get('index')} valid ({r.get('worker')})"
                         for r in (rep.get("results") or []) if r.get("status") in ("resolved", "cached")]
                if steps:
                    patch["completed_steps"] = steps
            except Exception:
                pass
    cm.refresh_working_memory(session_id, project, patch)


def _resolve_route(provider: str, model: Optional[str], effort: str,
                   user_prompt: str, attachments: Optional[List[str]],
                   workspace: str, cfg: Dict[str, Any]) -> Dict[str, Any]:
    provider = (provider or "auto").lower()
    usage_summary = ut.summary()
    if provider == "auto":
        profile = tr.classify_task(user_prompt, attachments,
                                   {"active_project": workspace, "aspect_ratio": cfg.get("active_model")})
        route = tr.select_best_route(profile, pc.catalog(cfg), usage_summary)
        if route.get("provider"):
            _emit_think(
                f"Mode Auto mendeteksi tugas {route.get('label')} -> memilih "
                f"{route.get('provider')}/{route.get('model')} (terbaik & kuota aman)."
            )
            return route
        # Tidak ada provider terkonfigurasi: tetap coba provider aktif manual.
        provider = cfg.get("active_provider") or "groq"
        model = None

    configured = pc.is_configured(provider, cfg)
    chosen_model = model or (
        cfg.get("active_model") if cfg.get("active_provider") == provider and cfg.get("active_model") != "smart-auto"
        else None
    )
    model_info = pc.get_model(provider, chosen_model, cfg)
    if not model_info:
        raise HubError(0, f"Model untuk {provider} tidak tersedia", provider)
    profile = tr.classify_task(user_prompt, attachments, {"active_project": workspace})
    return {
        "provider": provider,
        "model": model_info.get("id"),
        "kind": (pc.PROVIDER_META.get(provider) or {}).get("kind", "direct"),
        "configured": configured,
        "task_type": profile["task_type"],
        "label": profile["label"],
        "profile": profile,
        "fallback_chain": [],
    }


def _fallback_chain_for(route: Dict[str, Any], cfg: Dict[str, Any],
                        profile: Dict[str, Any]) -> List[Dict[str, Any]]:
    if not cfg.get("auto_fallback", True):
        return []
    catalog = pc.catalog(cfg)
    selected = tr.select_best_route(profile, catalog, ut.summary())
    chain = []
    for item in selected.get("fallback_chain") or []:
        if item.get("provider") == route.get("provider") and item.get("model") == route.get("model"):
            continue
        chain.append(item)
    return chain


def _is_retryable(exc: Exception) -> bool:
    if isinstance(exc, HubError):
        return exc.status in (0, 402, 408, 409, 429, 500, 502, 503, 504) or exc.status >= 500
    return isinstance(exc, (TimeoutError, ConnectionError, OSError))


def run_agent_turn(
    user_prompt: str,
    workspace: str = "",
    session_id: Optional[str] = None,
    attachments: Optional[List[str]] = None,
    provider: str = "auto",
    model: Optional[str] = None,
    effort: Optional[str] = None,
    mode: str = "director",
    on_delta: Optional[Callable[[str], None]] = None,
    event_callback: Optional[Callable[[Dict[str, Any]], None]] = None,
) -> Dict[str, Any]:
    """Eksekusi satu giliran agen lewat hub: route → handoff → fallback → record."""
    cfg = pc.load_config()
    effort = (effort or cfg.get("reasoning_effort") or "medium").lower()
    attachments = [a for a in (attachments or []) if a]
    started = time.time()
    _sync_working_memory(workspace, session_id)

    route = _resolve_route(provider, model, effort, user_prompt, attachments, workspace, cfg)
    if not route.get("configured", True) and not cfg.get("auto_fallback", True):
        raise HubError(0, f"Provider {route.get('provider')} belum dikonfigurasi", route.get("provider", ""))

    profile = route.get("profile") or tr.classify_task(user_prompt, attachments)
    chain = _fallback_chain_for(route, cfg, profile)
    candidates = [{"provider": route.get("provider"), "model": route.get("model"),
                   "kind": route.get("kind", "direct")}] + chain

    switched_from = ""
    last_error = ""
    result: Optional[Dict[str, Any]] = None
    used: Dict[str, Any] = {}

    for idx, cand in enumerate(candidates):
        pid = str(cand.get("provider") or "")
        mid = str(cand.get("model") or "")
        if not pid:
            continue
        if pid not in pc.CLI_PROVIDERS and not pc.is_configured(pid, cfg):
            continue
        if ut.quota_status(pid).get("status") == "exhausted" and idx < len(candidates) - 1:
            last_error = f"{pid} kuota habis"
            next_pid = str((candidates[idx + 1] or {}).get("provider") or "")
            switched_from = switched_from or pid
            _emit_think(f"Kuota {pid} habis, mengalihkan otomatis ke {next_pid}")
            continue
        try:
            handoff = cm.build_handoff_payload(
                session_id, pid, mid, workspace, extra_system=_base_system_prompt(mode)
            )
            if pid in pc.CLI_PROVIDERS:
                result = run_cli_agent(pid, mid, handoff.get("handoff_block", ""), user_prompt, effort)
            else:
                result = direct_chat(pid, mid, handoff, user_prompt, attachments, effort,
                                     on_delta=on_delta, cfg=cfg)
            used = {"provider": pid, "model": result.get("model") or mid}
            break
        except Exception as exc:
            last_error = str(exc)[:200]
            ut.record_usage(pid, mid, requests=1, error=True)
            if isinstance(exc, HubError) and exc.retry_after:
                ut.mark_retry_after(pid, exc.retry_after)
            elif isinstance(exc, HubError) and exc.status == 429:
                ut.mark_retry_after(pid, 60)
            if idx < len(candidates) - 1 and _is_retryable(exc):
                switched_from = switched_from or pid
                next_pid = str((candidates[idx + 1] or {}).get("provider") or "")
                _emit_think(f"Mengalihkan otomatis ke {next_pid} setelah {last_error[:120]}")
                continue
            raise

    if result is None:
        raise HubError(0, last_error or "Semua provider gagal", route.get("provider", ""))

    reply = str(result.get("content") or "").strip()
    tool_steps = result.get("tool_steps") or []
    tokens = int(result.get("tokens") or 0)
    used_provider = used.get("provider") or route.get("provider") or ""
    used_model = used.get("model") or route.get("model") or ""
    ut.record_usage(used_provider, used_model, tokens=tokens, requests=1)

    visual_summaries: Dict[str, str] = {}
    if attachments and result.get("multimodal"):
        summary_source = reply[:1200] if reply else "Analisis visual selesai."
        for path in attachments:
            visual_summaries[path] = summary_source
    cm.record_turn("user", user_prompt, used_model, used_provider, "", session_id,
                   workspace, attachments=attachments)
    cm.record_turn("assistant", reply, used_model, used_provider,
                   " ; ".join(tool_steps)[:600], session_id, workspace,
                   attachments=attachments, visual_summaries=visual_summaries)
    for step in tool_steps:
        cm.add_completed_step(step, session_id, workspace)

    meta = {
        "mode": "auto" if (provider or "auto").lower() == "auto" else "manual",
        "taskType": profile.get("task_type"),
        "provider": used_provider,
        "model": used_model,
        "multimodal": bool(result.get("multimodal")),
        "tokens": tokens,
        "latencyMs": int((time.time() - started) * 1000),
        "switchedFrom": switched_from,
    }
    _emit_meta(meta)
    if event_callback:
        try:
            event_callback({"type": "meta", **meta})
        except Exception:
            pass

    return {
        "status": "ok",
        "reply": reply or "Permintaan telah diproses.",
        "provider": used_provider,
        "model": used_model,
        "task_type": profile.get("task_type"),
        "meta": meta,
        "steps": [{"tool": s.split(":")[0], "status": "done", "detail": s[:160]} for s in tool_steps],
    }


def cli_main(argv: Optional[List[str]] = None) -> int:
    import argparse

    parser = argparse.ArgumentParser(prog="llm_hub.py", description="ContentStudio Unified Agent Hub V3.7")
    parser.add_argument("prompt", nargs="?", default="")
    parser.add_argument("--workspace", default="")
    parser.add_argument("--session-id", default=None)
    parser.add_argument("--provider", default="auto")
    parser.add_argument("--model", default=None)
    parser.add_argument("--effort", default=None, choices=["low", "medium", "high"])
    parser.add_argument("--attachment", action="append", default=[])
    parser.add_argument("--mode", default="director")
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])
    if not args.prompt:
        print("ContentStudio Agent Hub V3.7 siap.")
        return 0
    res = run_agent_turn(args.prompt, args.workspace, args.session_id, args.attachment,
                         args.provider, args.model, args.effort, args.mode)
    print(res["reply"])
    return 0


if __name__ == "__main__":
    raise SystemExit(cli_main())
