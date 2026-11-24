#!/usr/bin/env python3
"""
ContentStudio Multi-Provider Anti-Limit LLM Router (engine/router.py)
Sprint C: Multi-provider routing (Gemini, Groq, OpenRouter) with rate-limit
          circuit breaking, multi-key rotation, auto-pacing, same-tier enforcement,
          and checkpoint/resume support.

Usage:
  python3 engine/router.py status
  python3 engine/router.py call --task <vision|dna|script|json> --prompt <text|@file>
                                [--image <path.jpg>] [--out <out.txt>]
                                [--checkpoint <path>] [--pace 4]
"""

import sys
import os

# Mencegah direktori engine/ membayangi (shadowing) modul library standar Python seperti 'inspect'
_script_dir = os.path.dirname(os.path.abspath(__file__))
if sys.path and sys.path[0] == _script_dir:
    sys.path.pop(0)
_root_dir = os.path.dirname(_script_dir)
if _root_dir not in sys.path:
    sys.path.append(_root_dir)

import re
import json
import time
import base64
import hashlib
import argparse
import mimetypes
import urllib.request
import urllib.parse
import urllib.error
from datetime import datetime
from typing import Dict, Any, List, Optional, Tuple
from engine.runtime import get_root_dir, get_cache_dir, load_runtime_env

# Provider definitions & priority chains
# Same-Tier Routing:
# - vision: Gemini -> OpenRouter free vision (Groq DIBLOKIR / dilarang fallback ke teks)
# - dna / script: OpenRouter free large -> Gemini -> Groq
# - json: Groq -> Gemini -> OpenRouter free
TASK_CHAINS = {
    "vision": ["gemini", "openrouter"],
    "dna": ["openrouter", "gemini", "groq"],
    "script": ["openrouter", "gemini", "groq"],
    "json": ["groq", "gemini", "openrouter"]
}

DEFAULT_MODELS = {
    "gemini": "gemini-2.5-flash",
    "groq": "openai/gpt-oss-120b",
    "openrouter_text": "meta-llama/llama-3.3-70b-instruct:free",
    "openrouter_vision": "google/gemini-2.0-flash-exp:free"
}

CACHE_DIR_DEFAULT = get_cache_dir()
STATE_FILE_NAME = "quota_state.json"

def get_state_file_path(cache_dir: Optional[str] = None) -> str:
    """Mengembalikan path file state quota."""
    base = cache_dir or CACHE_DIR_DEFAULT
    os.makedirs(base, exist_ok=True)
    return os.path.join(base, STATE_FILE_NAME)

def load_env() -> Dict[str, str]:
    """Membaca environment variables dengan fallback ke file .env ContentStudio."""
    return load_runtime_env()

def parse_keys_for_provider(provider: str, env_vars: Optional[Dict[str, str]] = None) -> List[str]:
    """Mengambil daftar key per provider dari env (dipisah koma)."""
    env = env_vars or load_env()
    key_name = f"{provider.upper()}_API_KEY"
    raw = env.get(key_name, "").strip()
    if not raw:
        return []
    keys = [k.strip() for k in raw.split(",") if k.strip()]
    return keys

def get_key_id(key: str, index: int) -> str:
    """Mengenerate safe identifier untuk key tanpa membocorkan isi key."""
    h = hashlib.sha256(key.encode()).hexdigest()[:8]
    suffix = key[-4:] if len(key) >= 4 else "x"
    return f"key_{index + 1}_{h}_{suffix}"

def load_state(cache_dir: Optional[str] = None) -> Dict[str, Any]:
    """Membaca quota_state.json dari cache."""
    path = get_state_file_path(cache_dir)
    if os.path.exists(path):
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
                if isinstance(data, dict):
                    return data
        except Exception:
            pass
    return {"providers": {}}

def save_state(state: Dict[str, Any], cache_dir: Optional[str] = None):
    """Menyimpan quota_state.json secara atomic."""
    path = get_state_file_path(cache_dir)
    tmp_path = path + ".tmp"
    try:
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(state, f, indent=2)
        os.replace(tmp_path, path)
    except Exception as e:
        if os.path.exists(tmp_path):
            try:
                os.remove(tmp_path)
            except Exception:
                pass

def get_provider_status(provider: str, keys: List[str], state: Dict[str, Any]) -> Dict[str, Any]:
    """Menghitung status ready/cooldown untuk suatu provider."""
    now = time.time()
    prov_state = state.get("providers", {}).get(provider, {})
    keys_state = prov_state.get("keys", {})
    last_call_ts = prov_state.get("last_call_ts", 0.0)

    total_keys = len(keys)
    ready_count = 0
    cooldown_count = 0
    earliest_cooldown = float("inf")

    for idx, k in enumerate(keys):
        k_id = get_key_id(k, idx)
        k_info = keys_state.get(k_id, {})
        cooldown_until = k_info.get("cooldown_until", 0.0)
        if cooldown_until > now:
            cooldown_count += 1
            if cooldown_until < earliest_cooldown:
                earliest_cooldown = cooldown_until
        else:
            ready_count += 1

    return {
        "provider": provider,
        "total_keys": total_keys,
        "ready": ready_count,
        "cooldown": cooldown_count,
        "earliest_cooldown": earliest_cooldown if cooldown_count > 0 else 0.0,
        "last_call_ts": last_call_ts
    }

def get_all_providers_status(cache_dir: Optional[str] = None) -> Dict[str, Any]:
    """Mengembalikan status terstruktur seluruh provider AI (Groq, Gemini, OpenRouter) tanpa membocorkan key."""
    env = load_env()
    state = load_state(cache_dir)
    now = time.time()
    providers = ["gemini", "groq", "openrouter"]
    result = {}
    for prov in providers:
        keys = parse_keys_for_provider(prov, env)
        st = get_provider_status(prov, keys, state)
        if st["total_keys"] == 0:
            status_label = "NO KEY"
            next_ready_sec = -1
        elif st["ready"] > 0:
            status_label = "READY"
            next_ready_sec = 0
        else:
            status_label = "COOLDOWN"
            next_ready_sec = max(0, int(st["earliest_cooldown"] - now))
        result[prov] = {
            "total_keys": st["total_keys"],
            "ready": st["ready"],
            "cooldown": st["cooldown"],
            "next_ready_sec": next_ready_sec,
            "status": status_label
        }
    return {
        "status": "ok",
        "providers": result,
        "timestamp": now
    }

def print_status_table(cache_dir: Optional[str] = None):
    """Mencetak tabel ringkas status semua provider tanpa membocorkan key."""
    status_info = get_all_providers_status(cache_dir)
    providers = status_info.get("providers", {})

    print("=" * 78)
    print(" CONTENTSTUDIO MULTI-PROVIDER ROUTER STATUS ".center(78))
    print("=" * 78)
    header = f"{'Provider':<14} | {'Total':<6} | {'Ready':<6} | {'Cooldown':<9} | {'Next Ready':<16} | {'Status':<12}"
    print(header)
    print("-" * 78)

    for prov, st in providers.items():
        if st["total_keys"] == 0:
            next_ready = "-"
        elif st["ready"] > 0:
            next_ready = "NOW"
        else:
            next_ready = f"{st['next_ready_sec']}s lagi"

        print(f"{prov.capitalize():<14} | {st['total_keys']:<6} | {st['ready']:<6} | {st['cooldown']:<9} | {next_ready:<16} | {st['status']:<12}")

    print("=" * 78)

def mark_key_cooldown(provider: str, key: str, index: int, error_msg: str,
                      retry_after: Optional[int] = None, cache_dir: Optional[str] = None):
    """Menandai key mengalami cooldown (429 / quota limit) ke state."""
    state = load_state(cache_dir)
    now = time.time()

    # Hitung durasi cooldown:
    # 1. Jika ada header retry-after: gunakan nilai tersebut
    # 2. Jika pesan mengindikasikan kuota harian habis: 3600 detik (1 jam)
    # 3. Default limit per menit (RPM): 65 detik
    err_lower = error_msg.lower()
    if retry_after is not None and retry_after > 0:
        duration = retry_after
    elif any(q in err_lower for q in ["daily", "quota", "per day", "exhausted", "billing"]):
        duration = 3600
    else:
        duration = 65

    cooldown_until = now + duration
    k_id = get_key_id(key, index)

    if "providers" not in state:
        state["providers"] = {}
    if provider not in state["providers"]:
        state["providers"][provider] = {"last_call_ts": 0.0, "keys": {}}
    if "keys" not in state["providers"][provider]:
        state["providers"][provider]["keys"] = {}

    state["providers"][provider]["keys"][k_id] = {
        "cooldown_until": cooldown_until,
        "cooldown_duration": duration,
        "marked_at": now,
        "reason": error_msg[:120]
    }
    save_state(state, cache_dir)
    print(f"[!] Provider {provider.upper()} ({k_id}) rate-limited: cooldown {duration} detik ({error_msg[:60]}...).", file=sys.stderr)

def enforce_pacing(provider: str, pace: float, cache_dir: Optional[str] = None):
    """Menahan jeda antar-panggilan ke provider yang sama untuk mencegah spike RPM."""
    state = load_state(cache_dir)
    prov_data = state.get("providers", {}).get(provider, {})
    last_call = prov_data.get("last_call_ts", 0.0)
    now = time.time()
    elapsed = now - last_call

    if elapsed < pace:
        wait_sec = pace - elapsed
        time.sleep(wait_sec)

    # Update last_call_ts
    if "providers" not in state:
        state["providers"] = {}
    if provider not in state["providers"]:
        state["providers"][provider] = {"keys": {}}
    state["providers"][provider]["last_call_ts"] = time.time()
    save_state(state, cache_dir)

# ==============================================================================
# API REQUEST IMPLEMENTATIONS (URLLIB ONLY, ZERO DEPENDENCIES)
# ==============================================================================

def call_gemini_api(key: str, prompt: str, image_path: Optional[str] = None,
                    is_json: bool = False, model: str = DEFAULT_MODELS["gemini"]) -> str:
    """Panggilan native Google Gemini REST API via urllib."""
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={key}"

    parts: List[Dict[str, Any]] = []
    if image_path and os.path.exists(image_path):
        mime, _ = mimetypes.guess_type(image_path)
        mime = mime or "image/jpeg"
        with open(image_path, "rb") as f:
            b64_img = base64.b64encode(f.read()).decode("utf-8")
        parts.append({
            "inlineData": {
                "mimeType": mime,
                "data": b64_img
            }
        })
    parts.append({"text": prompt})

    payload: Dict[str, Any] = {
        "contents": [{"parts": parts}]
    }

    if is_json:
        payload["generationConfig"] = {
            "responseMimeType": "application/json"
        }

    body_bytes = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body_bytes,
        headers={"Content-Type": "application/json"},
        method="POST"
    )

    with urllib.request.urlopen(req, timeout=60) as resp:
        resp_data = json.loads(resp.read().decode("utf-8"))
        candidates = resp_data.get("candidates", [])
        if not candidates:
            raise RuntimeError("Gemini mengembalikan response kosong (candidates=[])")
        first_cand = candidates[0]
        content_parts = first_cand.get("content", {}).get("parts", [])
        if not content_parts:
            raise RuntimeError("Gemini tidak mengembalikan teks part.")
        return content_parts[0].get("text", "")

def call_groq_api(key: str, prompt: str, is_json: bool = False,
                  model: str = DEFAULT_MODELS["groq"]) -> str:
    """Panggilan native Groq OpenAI-compatible REST API via urllib."""
    url = "https://api.groq.com/openai/v1/chat/completions"

    payload: Dict[str, Any] = {
        "model": model,
        "messages": [
            {"role": "user", "content": prompt}
        ],
        "temperature": 0.5
    }

    if is_json:
        payload["response_format"] = {"type": "json_object"}

    body_bytes = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body_bytes,
        headers={
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json",
            "User-Agent": "ContentStudio-Router/1.0"
        },
        method="POST"
    )

    with urllib.request.urlopen(req, timeout=60) as resp:
        resp_data = json.loads(resp.read().decode("utf-8"))
        choices = resp_data.get("choices", [])
        if not choices:
            raise RuntimeError("Groq mengembalikan response kosong (choices=[])")
        return choices[0].get("message", {}).get("content", "")

def call_openrouter_api(key: str, prompt: str, image_path: Optional[str] = None,
                        is_json: bool = False, task: str = "dna") -> str:
    """Panggilan native OpenRouter OpenAI-compatible REST API via urllib."""
    url = "https://openrouter.ai/api/v1/chat/completions"

    if image_path and os.path.exists(image_path):
        model = DEFAULT_MODELS["openrouter_vision"]
        mime, _ = mimetypes.guess_type(image_path)
        mime = mime or "image/jpeg"
        with open(image_path, "rb") as f:
            b64_img = base64.b64encode(f.read()).decode("utf-8")
        data_uri = f"data:{mime};base64,{b64_img}"
        content_items = [
            {"type": "text", "text": prompt},
            {"type": "image_url", "image_url": {"url": data_uri}}
        ]
        messages = [{"role": "user", "content": content_items}]
    else:
        model = DEFAULT_MODELS["openrouter_text"]
        messages = [{"role": "user", "content": prompt}]

    payload: Dict[str, Any] = {
        "model": model,
        "messages": messages,
        "temperature": 0.7
    }

    if is_json:
        payload["response_format"] = {"type": "json_object"}

    body_bytes = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body_bytes,
        headers={
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json",
            "HTTP-Referer": "https://contentstudio.local",
            "X-Title": "ContentStudio Router"
        },
        method="POST"
    )

    with urllib.request.urlopen(req, timeout=90) as resp:
        resp_data = json.loads(resp.read().decode("utf-8"))
        choices = resp_data.get("choices", [])
        if not choices:
            raise RuntimeError("OpenRouter mengembalikan response kosong (choices=[])")
        return choices[0].get("message", {}).get("content", "")

# ==============================================================================
# MAIN ROUTING ENGINE
# ==============================================================================

def route_call(task: str, prompt: str, image_path: Optional[str] = None,
               checkpoint_path: Optional[str] = None, pace: float = 4.0,
               out_path: Optional[str] = None, cache_dir: Optional[str] = None,
               raise_on_error: bool = False) -> str:
    """
    Menjalankan pemanggilan AI dengan Same-Tier Routing, Multi-Key Rotation,
    Auto-Pacing, Circuit Breaker, dan Checkpoint Resume.
    """
    task = task.lower()
    if task not in TASK_CHAINS:
        raise ValueError(f"Task '{task}' tidak valid. Pilihan: {list(TASK_CHAINS.keys())}")

    # 1. Checkpoint & Resume
    if checkpoint_path and os.path.exists(checkpoint_path) and os.path.getsize(checkpoint_path) > 0:
        try:
            with open(checkpoint_path, "r", encoding="utf-8") as f:
                cached_text = f.read()
            print(f"[CHECKPOINT RESUME] Menggunakan hasil cache dari {checkpoint_path} (0 panggilan API).")
            if out_path:
                os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
                with open(out_path, "w", encoding="utf-8") as out_f:
                    out_f.write(cached_text)
            return cached_text
        except Exception as e:
            print(f"[!] Gagal membaca checkpoint: {e}. Melanjutkan panggilan live.", file=sys.stderr)

    # Khusus task vision: validasi ketersediaan file gambar
    if task == "vision" and not image_path:
        raise ValueError("Task 'vision' memerlukan argumen --image <path>.")

    env = load_env()
    state = load_state(cache_dir)
    chain = TASK_CHAINS[task]

    # Evaluasi ketersediaan key
    total_keys_in_chain = 0
    earliest_cooldown_overall = float("inf")

    for prov in chain:
        keys = parse_keys_for_provider(prov, env)
        total_keys_in_chain += len(keys)
        st = get_provider_status(prov, keys, state)
        if st["earliest_cooldown"] > 0 and st["earliest_cooldown"] < earliest_cooldown_overall:
            earliest_cooldown_overall = st["earliest_cooldown"]

    if total_keys_in_chain == 0:
        err_msg = f"Belum ada API key yang terkonfigurasi untuk task '{task}'. Rantai provider: {chain}"
        print(f"[!] Error: {err_msg}", file=sys.stderr)
        if raise_on_error:
            raise RuntimeError(err_msg)
        sys.exit(2)

    # Jalankan iterasi provider & key rotation
    now = time.time()
    for prov in chain:
        keys = parse_keys_for_provider(prov, env)
        if not keys:
            continue

        prov_state = state.get("providers", {}).get(prov, {})
        keys_state = prov_state.get("keys", {})

        for idx, key in enumerate(keys):
            k_id = get_key_id(key, idx)
            cooldown_until = keys_state.get(k_id, {}).get("cooldown_until", 0.0)

            if cooldown_until > now:
                # Key sedang dalam masa cooldown, skip ke key berikutnya
                continue

            # Key siap dipakai! Terapkan auto-pacing
            enforce_pacing(prov, pace, cache_dir)

            try:
                result_text = ""
                is_json_task = (task == "json")

                if prov == "gemini":
                    result_text = call_gemini_api(key, prompt, image_path, is_json=is_json_task)
                elif prov == "groq":
                    result_text = call_groq_api(key, prompt, is_json=is_json_task)
                elif prov == "openrouter":
                    result_text = call_openrouter_api(key, prompt, image_path, is_json=is_json_task, task=task)
                else:
                    raise RuntimeError(f"Provider tidak dikenali: {prov}")

                # Berhasil! Simpan checkpoint jika diminta
                if checkpoint_path:
                    os.makedirs(os.path.dirname(os.path.abspath(checkpoint_path)), exist_ok=True)
                    with open(checkpoint_path, "w", encoding="utf-8") as cf:
                        cf.write(result_text)

                if out_path:
                    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
                    with open(out_path, "w", encoding="utf-8") as of:
                        of.write(result_text)

                return result_text

            except urllib.error.HTTPError as he:
                err_body = ""
                try:
                    err_body = he.read().decode("utf-8")
                except Exception:
                    pass

                retry_after_val: Optional[int] = None
                ra_header = he.headers.get("Retry-After")
                if ra_header and ra_header.isdigit():
                    retry_after_val = int(ra_header)

                if he.code == 429:
                    mark_key_cooldown(prov, key, idx, f"HTTP 429: {err_body[:100]}", retry_after_val, cache_dir)
                    # Refresh state & lanjut ke key/provider berikutnya
                    state = load_state(cache_dir)
                    continue
                else:
                    print(f"[!] Provider {prov.upper()} gagal (HTTP {he.code}): {err_body[:100]}", file=sys.stderr)
                    # Jika error bukan 429 tapi provider bermasalah, catat cooldown singkat 30s
                    mark_key_cooldown(prov, key, idx, f"HTTP {he.code}: {err_body[:100]}", 30, cache_dir)
                    state = load_state(cache_dir)
                    continue

            except Exception as e:
                err_str = str(e)
                print(f"[!] Kesalahan pada provider {prov.upper()} ({k_id}): {err_str[:100]}", file=sys.stderr)
                # Tandai cooldown darurat 30 detik agar tidak terjebak loop error
                mark_key_cooldown(prov, key, idx, f"Error: {err_str[:100]}", 30, cache_dir)
                state = load_state(cache_dir)
                continue

    # Jika loop selesai dan tidak ada return: seluruh provider sedang cooldown
    now = time.time()
    err_msg = f"Seluruh provider untuk task '{task}' sedang cooldown ({diff}s lagi)."
    print(f"[!] {err_msg}", file=sys.stderr)
    print(f"    Cooldown tercepat selesai dalam {diff} detik (sekitar pukul {earliest_str}).", file=sys.stderr)
    if raise_on_error:
        raise RuntimeError(err_msg)
    sys.exit(42)

def main():
    parser = argparse.ArgumentParser(description="ContentStudio Multi-Provider Anti-Limit LLM Router")
    subparsers = parser.add_subparsers(dest="subcommand", help="Subcommand")

    # Subcommand: status
    parser_status = subparsers.add_parser("status", help="Tampilkan status provider, key aktif, dan cooldown")
    parser_status.add_argument("--cache-dir", default=None, help="Direktori cache kustom")

    # Subcommand: call
    parser_call = subparsers.add_parser("call", help="Jalankan pemanggilan router")
    parser_call.add_argument("--task", required=True, choices=["vision", "dna", "script", "json"],
                             help="Tipe task (vision | dna | script | json)")
    parser_call.add_argument("--prompt", required=True,
                             help="Teks prompt langsung atau @path/ke/file")
    parser_call.add_argument("--image", default=None,
                             help="Path file gambar (wajib untuk vision)")
    parser_call.add_argument("--out", default=None,
                             help="Path file output untuk menyimpan teks respon")
    parser_call.add_argument("--checkpoint", default=None,
                             help="Path file checkpoint untuk resume otomatis (0 panggilan API)")
    parser_call.add_argument("--pace", type=float, default=4.0,
                             help="Jeda minimum antar-panggilan per provider dalam detik (default: 4.0)")
    parser_call.add_argument("--cache-dir", default=None,
                             help="Direktori cache kustom")

    args = parser.parse_args()

    if not args.subcommand:
        parser.print_help()
        sys.exit(1)

    if args.subcommand == "status":
        print_status_table(cache_dir=args.cache_dir)
        sys.exit(0)

    elif args.subcommand == "call":
        # Resolve prompt jika memakai @file
        prompt_text = args.prompt
        if prompt_text.startswith("@"):
            prompt_file = prompt_text[1:]
            if not os.path.exists(prompt_file):
                print(f"[!] File prompt tidak ditemukan: {prompt_file}", file=sys.stderr)
                sys.exit(1)
            with open(prompt_file, "r", encoding="utf-8") as pf:
                prompt_text = pf.read()

        result = route_call(
            task=args.task,
            prompt=prompt_text,
            image_path=args.image,
            checkpoint_path=args.checkpoint,
            pace=args.pace,
            out_path=args.out,
            cache_dir=args.cache_dir
        )

        if not args.out:
            print(result)

        sys.exit(0)

if __name__ == "__main__":
    main()
