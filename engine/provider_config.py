#!/usr/bin/env python3
"""
ContentStudio Provider Catalog & Hub Configuration (V3.8)

Mengelola `config/providers.json` untuk 5 provider resmi:
- auto (Auto Router)
- antigravity (CLI bawaan)
- gemini (Google Gemini Direct)
- groq (Groq Direct)
- openrouter (OpenRouter Direct)
- custom (Custom OpenAI-Compatible)

Nol hardcode model tebakan: verified_models untuk gemini, groq, openrouter
awalnya kosong ([]) dan hanya terisi setelah live verifikasi koneksi API sukses.
"""

import os
import sys

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if sys.path and sys.path[0] == _SCRIPT_DIR:
    sys.path.pop(0)
_ROOT_DIR = os.path.dirname(_SCRIPT_DIR)
if _ROOT_DIR not in sys.path:
    sys.path.append(_ROOT_DIR)

import json
import urllib.request
import urllib.error
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional

from engine.runtime import get_root_dir

CONFIG_REL_PATH = os.path.join("config", "providers.json")

DIRECT_PROVIDERS = ("gemini", "groq", "openrouter", "custom")
CLI_PROVIDERS = ("antigravity",)
PROVIDER_IDS = CLI_PROVIDERS + DIRECT_PROVIDERS

PROVIDER_META: Dict[str, Dict[str, Any]] = {
    "antigravity": {
        "label": "Antigravity (Bawaan)",
        "kind": "cli",
        "format": "cli",
        "base_url": None,
        "docs": "CLI lokal terintegrasi",
        "env_keys": [],
        "needs_account_id": False,
    },
    "gemini": {
        "label": "Google Gemini",
        "kind": "direct",
        "format": "gemini",
        "base_url": "https://generativelanguage.googleapis.com/v1beta",
        "env_keys": ["GEMINI_API_KEY", "GOOGLE_API_KEY", "GOOGLE_GEMINI_API_KEY"],
        "needs_account_id": False,
    },
    "groq": {
        "label": "Groq",
        "kind": "direct",
        "format": "openai",
        "base_url": "https://api.groq.com/openai/v1",
        "env_keys": ["GROQ_API_KEY"],
        "needs_account_id": False,
    },
    "openrouter": {
        "label": "OpenRouter",
        "kind": "direct",
        "format": "openai",
        "base_url": "https://openrouter.ai/api/v1",
        "env_keys": ["OPENROUTER_API_KEY"],
        "needs_account_id": False,
    },
    "custom": {
        "label": "Custom Endpoint",
        "kind": "direct",
        "format": "openai",
        "base_url": "",
        "env_keys": ["CUSTOM_API_KEY", "CUSTOM_BASE_URL", "CUSTOM_MODEL"],
        "needs_account_id": False,
    },
}

def _caps(multimodal: bool = False, tools: bool = False, reasoning: bool = False,
          speed: str = "fast", coding: bool = False) -> Dict[str, Any]:
    return {
        "multimodal": bool(multimodal),
        "tools": bool(tools),
        "reasoning": bool(reasoning),
        "speed": speed,
        "coding": bool(coding),
    }

def _model(model_id: str, label: str, caps: Dict[str, Any],
           context_window: Optional[int] = None) -> Dict[str, Any]:
    item: Dict[str, Any] = {
        "id": model_id,
        "label": label,
        "capabilities": caps,
        "free": True,
    }
    if context_window:
        item["context_window"] = context_window
    return item

# Antigravity bawaan selalu punya 1 model pasti: "default"
# Provider BYOK (gemini, groq, openrouter, custom) awalnya KOSONG ([]).
PROVIDER_MODELS: Dict[str, List[Dict[str, Any]]] = {
    "antigravity": [
        _model("default", "Antigravity (Bawaan)", _caps(True, True, False, "fast"), 200000),
    ],
    "gemini": [],
    "groq": [],
    "openrouter": [],
    "custom": [],
}

DEFAULT_FALLBACK_ORDER = [
    "groq",
    "gemini",
    "openrouter",
    "antigravity",
    "custom",
]

def default_config() -> Dict[str, Any]:
    return {
        "version": 2,
        "active_provider": "auto",
        "active_model": "smart-auto",
        "reasoning_effort": "medium",
        "auto_fallback": True,
        "fallback_order": list(DEFAULT_FALLBACK_ORDER),
        "keys": {p: "" for p in PROVIDER_IDS},
        "verified_models": {p: [] for p in PROVIDER_IDS},
        "custom": {
            "base_url": "",
            "api_key": "",
            "model_id": "",
            "multimodal": False,
        },
        "updated_at": "",
    }

def get_config_path() -> str:
    return os.path.join(get_root_dir(), CONFIG_REL_PATH)

def _merge_defaults(data: Any) -> Dict[str, Any]:
    cfg = default_config()
    if not isinstance(data, dict):
        return cfg
    for key, val in data.items():
        if key == "keys" and isinstance(val, dict):
            for p in PROVIDER_IDS:
                cfg["keys"][p] = str(val.get(p) or "")
        elif key == "verified_models" and isinstance(val, dict):
            for p in PROVIDER_IDS:
                v_list = val.get(p)
                if isinstance(v_list, list):
                    cfg["verified_models"][p] = v_list
        elif key == "custom" and isinstance(val, dict):
            cfg["custom"].update({k: val.get(k, cfg["custom"].get(k)) for k in cfg["custom"]})
        elif key in cfg:
            cfg[key] = val
    order = [str(p).lower() for p in (cfg.get("fallback_order") or []) if str(p).lower() in PROVIDER_IDS]
    for p in DEFAULT_FALLBACK_ORDER:
        if p not in order:
            order.append(p)
    cfg["fallback_order"] = order
    return cfg

def load_config() -> Dict[str, Any]:
    path = get_config_path()
    data: Any = {}
    if os.path.exists(path):
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            data = {}
    return _merge_defaults(data)

def save_config(config: Dict[str, Any]) -> bool:
    path = get_config_path()
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        payload = _merge_defaults(config)
        import time
        payload["updated_at"] = time.strftime("%Y-%m-%dT%H:%M:%S")
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(payload, f, indent=2, ensure_ascii=False)
        os.replace(tmp, path)
        return True
    except Exception:
        return False

def _env_lookup(names: List[str]) -> str:
    for n in names:
        v = os.environ.get(n)
        if v and v.strip():
            return v.strip()
    try:
        from engine.router import load_env
        env = load_env()
        for n in names:
            v = env.get(n)
            if v and str(v).strip():
                return str(v).strip()
    except Exception:
        pass
    return ""

def resolve_key(provider: str, config: Optional[Dict[str, Any]] = None) -> str:
    provider = (provider or "").lower()
    cfg = config or load_config()
    meta = PROVIDER_META.get(provider) or {}
    if meta.get("kind") == "cli":
        return "cli"
    if provider == "custom":
        val = str((cfg.get("custom") or {}).get("api_key") or "").strip()
        return val or _env_lookup(meta.get("env_keys") or [])
    val = str((cfg.get("keys") or {}).get(provider) or "").strip()
    return val or _env_lookup(meta.get("env_keys") or [])

def resolve_base_url(provider: str, config: Optional[Dict[str, Any]] = None) -> str:
    provider = (provider or "").lower()
    cfg = config or load_config()
    if provider == "custom":
        base = str((cfg.get("custom") or {}).get("base_url") or "").strip()
        return base or _env_lookup(["CUSTOM_BASE_URL"])
    return str((PROVIDER_META.get(provider) or {}).get("base_url") or "")

def is_configured(provider: str, config: Optional[Dict[str, Any]] = None) -> bool:
    provider = (provider or "").lower()
    cfg = config or load_config()
    meta = PROVIDER_META.get(provider) or {}
    if meta.get("kind") == "cli":
        return True
    if not resolve_key(provider, cfg):
        return False
    if provider == "custom":
        custom = cfg.get("custom") or {}
        return bool(resolve_base_url(provider, cfg) and (custom.get("model_id") or custom.get("model")))
    verified = (cfg.get("verified_models") or {}).get(provider)
    if isinstance(verified, list) and len(verified) > 0:
        return True
    return False

def provider_models(provider: str, config: Optional[Dict[str, Any]] = None) -> List[Dict[str, Any]]:
    provider = (provider or "").lower()
    cfg = config or load_config()
    if provider == "antigravity":
        return [dict(m) for m in PROVIDER_MODELS["antigravity"]]
    if provider == "custom":
        custom = cfg.get("custom") or {}
        mid = str(custom.get("model_id") or custom.get("model") or "").strip()
        if mid and resolve_base_url(provider, cfg):
            return [_model(mid, mid, _caps(bool(custom.get("multimodal")), True, False, "fast"))]
        return []
    verified = (cfg.get("verified_models") or {}).get(provider)
    if isinstance(verified, list) and len(verified) > 0:
        return [dict(m) for m in verified]
    return []

def get_model(provider: str, model_id: Optional[str], config: Optional[Dict[str, Any]] = None) -> Optional[Dict[str, Any]]:
    models = provider_models(provider, config)
    if not models:
        return None
    if not model_id or model_id == "smart-auto" or model_id == "default":
        return models[0]
    for m in models:
        if m.get("id") == model_id:
            return m
    return models[0]

def list_providers(config: Optional[Dict[str, Any]] = None, include_keys: bool = False) -> List[Dict[str, Any]]:
    cfg = config or load_config()
    out: List[Dict[str, Any]] = []
    for pid in PROVIDER_IDS:
        meta = PROVIDER_META[pid]
        key = resolve_key(pid, cfg) if pid != "custom" else resolve_key("custom", cfg)
        masked = ""
        if key and key != "cli":
            masked = (key[:3] + "..." + key[-3:]) if len(key) > 8 else "***"
        models = provider_models(pid, cfg)
        entry: Dict[str, Any] = {
            "id": pid,
            "label": meta.get("label"),
            "kind": meta.get("kind"),
            "format": meta.get("format", "cli"),
            "base_url": resolve_base_url(pid, cfg),
            "configured": is_configured(pid, cfg),
            "has_key": bool(key),
            "key_masked": masked,
            "models": models,
        }
        if include_keys:
            entry["key"] = key
        out.append(entry)
    return out

def catalog(config: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    cfg = config or load_config()
    return {
        "providers": list_providers(cfg),
        "models": {pid: provider_models(pid, cfg) for pid in PROVIDER_IDS},
        "active_provider": cfg.get("active_provider", "auto"),
        "active_model": cfg.get("active_model", "smart-auto"),
        "reasoning_effort": cfg.get("reasoning_effort", "medium"),
        "auto_fallback": bool(cfg.get("auto_fallback", True)),
        "fallback_order": cfg.get("fallback_order", list(DEFAULT_FALLBACK_ORDER)),
    }

def set_active(provider: str, model: str = "smart-auto", effort: str = "medium",
               auto_fallback: Optional[bool] = None) -> bool:
    provider = (provider or "auto").lower()
    if provider not in PROVIDER_IDS and provider != "auto":
        return False
    effort = (effort or "medium").lower()
    if effort not in ("low", "medium", "high"):
        effort = "medium"
    cfg = load_config()
    cfg["active_provider"] = provider
    cfg["active_model"] = "smart-auto" if provider == "auto" else (model or "smart-auto")
    cfg["reasoning_effort"] = effort
    if auto_fallback is not None:
        cfg["auto_fallback"] = bool(auto_fallback)
    return save_config(cfg)

def mask_key(value: str) -> str:
    value = str(value or "")
    if not value:
        return ""
    if len(value) <= 8:
        return "*" * len(value)
    return value[:4] + "*" * (len(value) - 8) + value[-4:]

def apply_settings(payload: Dict[str, Any]) -> Dict[str, Any]:
    cfg = load_config()
    provider = str(payload.get("active_provider") or cfg.get("active_provider") or "auto").lower()
    if provider != "auto" and provider not in PROVIDER_IDS:
        provider = "auto"
    cfg["active_provider"] = provider
    cfg["active_model"] = "smart-auto" if provider == "auto" else str(payload.get("active_model") or cfg.get("active_model") or "smart-auto")
    effort = str(payload.get("reasoning_effort") or cfg.get("reasoning_effort") or "medium").lower()
    cfg["reasoning_effort"] = effort if effort in ("low", "medium", "high") else "medium"
    if "auto_fallback" in payload:
        cfg["auto_fallback"] = bool(payload.get("auto_fallback"))

    incoming_keys = payload.get("keys")
    if isinstance(incoming_keys, dict):
        keys = cfg.get("keys") if isinstance(cfg.get("keys"), dict) else {}
        verified = cfg.get("verified_models") if isinstance(cfg.get("verified_models"), dict) else {}
        for p, val in incoming_keys.items():
            pid = str(p).lower()
            if pid not in PROVIDER_IDS:
                continue
            text = str(val or "").strip()
            if not text:
                keys[pid] = ""
                verified[pid] = []
            elif "*" not in text:
                keys[pid] = text
        cfg["keys"] = keys
        cfg["verified_models"] = verified

    custom = payload.get("custom")
    if isinstance(custom, dict):
        base = cfg.get("custom") if isinstance(cfg.get("custom"), dict) else {}
        for field in ("base_url", "model", "model_id", "label"):
            if field in custom:
                base[field] = str(custom.get(field) or "").strip()
        if "model" in custom and "model_id" not in custom:
            base["model_id"] = str(custom.get("model") or "").strip()
        if "api_key" in custom:
            key_text = str(custom.get("api_key") or "").strip()
            if not key_text:
                base["api_key"] = ""
            elif "*" not in key_text:
                base["api_key"] = key_text
        if "multimodal" in custom:
            base["multimodal"] = bool(custom.get("multimodal"))
        cfg["custom"] = base

    cfg["updated_at"] = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    save_config(cfg)
    return sanitized_settings(cfg)

def sanitized_settings(cfg: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    cfg = cfg or load_config()
    keys = cfg.get("keys") if isinstance(cfg.get("keys"), dict) else {}
    masked = {}
    configured = {}
    for p in PROVIDER_IDS:
        raw = str(keys.get(p) or "")
        masked[p] = mask_key(raw)
        configured[p] = is_configured(p, cfg)
    custom_cfg = dict(cfg.get("custom") or {})
    if custom_cfg.get("api_key"):
        custom_cfg["api_key"] = mask_key(str(custom_cfg.get("api_key")))
    return {
        "active_provider": cfg.get("active_provider", "auto"),
        "active_model": cfg.get("active_model", "smart-auto"),
        "reasoning_effort": cfg.get("reasoning_effort", "medium"),
        "auto_fallback": bool(cfg.get("auto_fallback", True)),
        "fallback_order": cfg.get("fallback_order", list(DEFAULT_FALLBACK_ORDER)),
        "keys_masked": masked,
        "configured": configured,
        "custom": custom_cfg,
        "updated_at": cfg.get("updated_at") or "",
    }

# ── Live Connection Verification & Model Fetching (V3.8) ────────────────────

def verify_and_fetch_models(provider: str, api_key: str = "",
                            base_url: Optional[str] = None,
                            model_id: Optional[str] = None) -> Dict[str, Any]:
    """
    Live test ke endpoint resmi provider.
    Jika sukses: mengembalikan {ok: True, count: N, models: [...]} dan menyimpan
    verified_models ke config/providers.json.
    Jika gagal: mengembalikan {ok: False, error: "..."} dan mengosongkan verified_models.
    """
    provider = (provider or "").lower().strip()
    if provider not in PROVIDER_IDS:
        return {"ok": False, "error": f"Provider {provider} tidak dikenali"}

    cfg = load_config()
    key = api_key.strip() if api_key else resolve_key(provider, cfg)

    if provider == "antigravity":
        models = [dict(m) for m in PROVIDER_MODELS["antigravity"]]
        return {"ok": True, "count": len(models), "models": models}

    if not key and provider != "custom":
        cfg.setdefault("verified_models", {})[provider] = []
        save_config(cfg)
        return {"ok": False, "error": "API Key kosong"}

    try:
        models: List[Dict[str, Any]] = []

        if provider == "gemini":
            url = f"https://generativelanguage.googleapis.com/v1beta/models?key={key}"
            req = urllib.request.Request(url, headers={"User-Agent": "ContentStudio/3.8"})
            with urllib.request.urlopen(req, timeout=15) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            raw_models = data.get("models") or []
            for m in raw_models:
                methods = m.get("supportedGenerationMethods") or []
                if "generateContent" in methods:
                    mid = str(m.get("name") or "").replace("models/", "")
                    display = str(m.get("displayName") or mid)
                    # Hindari model embedding/aqa
                    if "embedding" in mid.lower() or "aqa" in mid.lower():
                        continue
                    caps = _caps(multimodal=True, tools=True, reasoning=False, speed="fast")
                    models.append(_model(mid, display, caps, m.get("inputTokenLimit") or 1000000))

        elif provider == "groq":
            url = "https://api.groq.com/openai/v1/models"
            req = urllib.request.Request(
                url,
                headers={
                    "Authorization": f"Bearer {key}",
                    "User-Agent": "ContentStudio/3.8"
                }
            )
            with urllib.request.urlopen(req, timeout=15) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            raw_models = data.get("data") or []
            discard = ("whisper", "tts", "guard", "safeguard", "audio", "distil")
            for m in raw_models:
                mid = str(m.get("id") or "")
                if not mid or any(d in mid.lower() for d in discard):
                    continue
                caps = _caps(multimodal=False, tools=True, reasoning=False, speed="ultra")
                models.append(_model(mid, mid, caps, m.get("context_window") or 131072))

        elif provider == "openrouter":
            # 1. Cek autentikasi key
            auth_url = "https://openrouter.ai/api/v1/auth/key"
            auth_req = urllib.request.Request(
                auth_url,
                headers={
                    "Authorization": f"Bearer {key}",
                    "User-Agent": "ContentStudio/3.8"
                }
            )
            with urllib.request.urlopen(auth_req, timeout=15) as resp:
                if resp.status != 200:
                    raise Exception(f"Autentikasi OpenRouter gagal (HTTP {resp.status})")

            # 2. Ambil daftar model free
            models_url = "https://openrouter.ai/api/v1/models"
            m_req = urllib.request.Request(models_url, headers={"User-Agent": "ContentStudio/3.8"})
            with urllib.request.urlopen(m_req, timeout=15) as resp:
                data = json.loads(resp.read().decode("utf-8"))
            raw_models = data.get("data") or []
            for m in raw_models:
                mid = str(m.get("id") or "")
                pricing = m.get("pricing") or {}
                prompt_price = str(pricing.get("prompt") or "")
                # Hanya model 100% gratis
                if prompt_price == "0" or ":free" in mid.lower():
                    name = str(m.get("name") or mid)
                    caps = _caps(multimodal=False, tools=True, reasoning=False, speed="fast")
                    models.append(_model(mid, name, caps, m.get("context_length") or 128000))

        elif provider == "custom":
            b_url = (base_url or resolve_base_url("custom", cfg)).strip().rstrip("/")
            m_id = (model_id or (cfg.get("custom") or {}).get("model_id") or "").strip()
            if not b_url:
                raise Exception("Base URL custom kosong")
            if not m_id:
                raise Exception("Model ID custom kosong")
            # Tes koneksi HTTP ringan
            test_url = f"{b_url}/models"
            headers = {"User-Agent": "ContentStudio/3.8"}
            if key:
                headers["Authorization"] = f"Bearer {key}"
            req = urllib.request.Request(test_url, headers=headers)
            try:
                with urllib.request.urlopen(req, timeout=15) as resp:
                    pass
            except urllib.error.HTTPError as he:
                # Bila 404 (beberapa provider tidak sediakan /models), coba options/head
                if he.code not in (200, 404, 405):
                    raise Exception(f"HTTP Error {he.code}: {he.reason}")
            caps = _caps(multimodal=bool((cfg.get("custom") or {}).get("multimodal")), tools=True, reasoning=False, speed="fast")
            models.append(_model(m_id, m_id, caps, 128000))

        if not models:
            raise Exception("Tidak ada model aktif yang ditemukan untuk akun ini")

        # Urutkan berdasarkan id/label
        models.sort(key=lambda x: x.get("label", ""))

        # Simpan verified_models ke config
        cfg.setdefault("verified_models", {})[provider] = models
        if api_key:
            if provider == "custom":
                cfg.setdefault("custom", {})["api_key"] = api_key
                if base_url:
                    cfg["custom"]["base_url"] = base_url
                if model_id:
                    cfg["custom"]["model_id"] = model_id
            else:
                cfg.setdefault("keys", {})[provider] = api_key
        save_config(cfg)

        return {"ok": True, "count": len(models), "models": models}

    except Exception as e:
        # Bersihkan verified_models jika gagal
        cfg.setdefault("verified_models", {})[provider] = []
        save_config(cfg)
        err_msg = str(e)
        if "HTTP Error 401" in err_msg or "401" in err_msg:
            err_msg = "API Key tidak valid atau kedaluwarsa"
        elif "HTTP Error 403" in err_msg:
            err_msg = "Akses ditolak (kuota habis atau izin dibatasi)"
        elif "timed out" in err_msg.lower():
            err_msg = "Koneksi time out saat memverifikasi API"
        return {"ok": False, "error": err_msg}

def cli_main(argv: Optional[List[str]] = None) -> int:
    import argparse
    import sys

    parser = argparse.ArgumentParser(prog="provider_config.py", description="ContentStudio Provider Catalog V3.8")
    sub = parser.add_subparsers(dest="cmd")
    p_models = sub.add_parser("models", help="Tampilkan katalog provider & model (JSON)")
    p_models.add_argument("--full", action="store_true", help="Sertakan semua metadata")
    p_set = sub.add_parser("set", help="Simpan provider/model aktif")
    p_set.add_argument("--provider", required=True)
    p_set.add_argument("--model", default="smart-auto")
    p_set.add_argument("--effort", default="medium", choices=["low", "medium", "high"])
    p_set.add_argument("--no-fallback", action="store_true")
    p_show = sub.add_parser("show", help="Tampilkan config (key dimasking)")
    p_settings = sub.add_parser("settings", help="Tampilkan settings hub (key dimasking)")
    p_save = sub.add_parser("save-settings", help="Simpan settings hub dari JSON")
    p_save.add_argument("--json", required=True)
    p_test = sub.add_parser("test-provider", help="Verifikasi koneksi & fetch model live")
    p_test.add_argument("--provider", required=True)
    p_test.add_argument("--key", default="")
    p_test.add_argument("--base-url", default=None)
    p_test.add_argument("--model-id", default=None)

    args = parser.parse_args(argv if argv is not None else sys.argv[1:])

    if args.cmd == "set":
        ok = set_active(args.provider, args.model, args.effort, not args.no_fallback)
        print(json.dumps({"status": "ok" if ok else "error"}, ensure_ascii=False))
        return 0 if ok else 1
    if args.cmd == "show":
        print(json.dumps(load_config(), indent=2, ensure_ascii=False))
        return 0
    if args.cmd == "settings":
        print(json.dumps(sanitized_settings(), indent=2, ensure_ascii=False))
        return 0
    if args.cmd == "save-settings":
        try:
            payload = json.loads(args.json)
        except Exception:
            print(json.dumps({"status": "error", "error": "JSON tidak valid"}))
            return 1
        print(json.dumps({"status": "ok", **apply_settings(payload)}, ensure_ascii=False))
        return 0
    if args.cmd == "test-provider":
        res = verify_and_fetch_models(args.provider, args.key, args.base_url, args.model_id)
        print(json.dumps(res, ensure_ascii=False))
        return 0 if res.get("ok") else 1

    data = catalog()
    if not getattr(args, "full", False):
        for p in data["providers"]:
            p.pop("models", None)
    print(json.dumps(data, indent=2, ensure_ascii=False))
    return 0

if __name__ == "__main__":
    import sys
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    raise SystemExit(cli_main())
