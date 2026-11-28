#!/usr/bin/env python3
"""
ContentStudio Provider Catalog & Hub Configuration (V3.7)

Mengelola `config/providers.json` untuk 12 provider gratis (termasuk mode
"auto"). Semua metadata kapabilitas bersifat data-driven: tidak ada logika
khusus niche maupun nama model yang ditanam di modul lain.
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


import json
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional


from engine.runtime import get_root_dir

CONFIG_REL_PATH = os.path.join("config", "providers.json")

DIRECT_PROVIDERS = (
    "gemini",
    "groq",
    "mistral",
    "zai",
    "cohere",
    "nvidia",
    "sambanova",
    "cloudflare",
    "openrouter",
    "custom",
)
CLI_PROVIDERS = ("antigravity", "cline")
PROVIDER_IDS = CLI_PROVIDERS + DIRECT_PROVIDERS

PROVIDER_META: Dict[str, Dict[str, Any]] = {
    "antigravity": {
        "label": "Antigravity (CLI Built-in)",
        "kind": "cli",
        "base_url": None,
        "docs": "CLI lokal terintegrasi",
        "env_keys": [],
        "needs_account_id": False,
    },
    "cline": {
        "label": "Cline CLI (Headless Agent)",
        "kind": "cli",
        "base_url": None,
        "docs": "cline --json --auto-approve true",
        "env_keys": [],
        "needs_account_id": False,
    },
    "gemini": {
        "label": "Google Gemini Direct",
        "kind": "direct",
        "format": "gemini",
        "base_url": "https://generativelanguage.googleapis.com/v1beta",
        "env_keys": ["GEMINI_API_KEY", "GOOGLE_API_KEY", "GOOGLE_GEMINI_API_KEY"],
        "needs_account_id": False,
    },
    "groq": {
        "label": "Groq Direct",
        "kind": "direct",
        "format": "openai",
        "base_url": "https://api.groq.com/openai/v1",
        "env_keys": ["GROQ_API_KEY"],
        "needs_account_id": False,
    },
    "mistral": {
        "label": "Mistral AI Direct",
        "kind": "direct",
        "format": "openai",
        "base_url": "https://api.mistral.ai/v1",
        "env_keys": ["MISTRAL_API_KEY"],
        "needs_account_id": False,
    },
    "zai": {
        "label": "Z.ai / GLM Direct",
        "kind": "direct",
        "format": "openai",
        "base_url": "https://api.z.ai/api/paas/v4",
        "env_keys": ["ZAI_API_KEY", "GLM_API_KEY", "ZHIPU_API_KEY"],
        "needs_account_id": False,
    },
    "cohere": {
        "label": "Cohere Direct",
        "kind": "direct",
        "format": "cohere",
        "base_url": "https://api.cohere.ai/compatibility/v1",
        "env_keys": ["COHERE_API_KEY", "CO_API_KEY"],
        "needs_account_id": False,
    },
    "nvidia": {
        "label": "NVIDIA NIM",
        "kind": "direct",
        "format": "openai",
        "base_url": "https://integrate.api.nvidia.com/v1",
        "env_keys": ["NVIDIA_API_KEY", "NVIDIA_NIM_API_KEY"],
        "needs_account_id": False,
    },
    "sambanova": {
        "label": "SambaNova Cloud",
        "kind": "direct",
        "format": "openai",
        "base_url": "https://api.sambanova.ai/v1",
        "env_keys": ["SAMBANOVA_API_KEY", "SAMBANOVA_CLOUD_API_KEY"],
        "needs_account_id": False,
    },
    "cloudflare": {
        "label": "Cloudflare Workers AI",
        "kind": "direct",
        "format": "openai",
        "base_url": "https://api.cloudflare.com/client/v4/accounts",
        "env_keys": ["CLOUDFLARE_API_KEY", "CF_API_TOKEN"],
        "needs_account_id": True,
    },
    "openrouter": {
        "label": "OpenRouter Direct",
        "kind": "direct",
        "format": "openai",
        "base_url": "https://openrouter.ai/api/v1",
        "env_keys": ["OPENROUTER_API_KEY"],
        "needs_account_id": False,
    },
    "custom": {
        "label": "Custom OpenAI-Compatible",
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


PROVIDER_MODELS: Dict[str, List[Dict[str, Any]]] = {
    "antigravity": [
        _model("gemini-4-hydrogen", "Gemini 4 Hydrogen", _caps(True, True, False, "fast"), 200000),
        _model("gemini-4-neon", "Gemini 4 Neon", _caps(True, True, False, "deep"), 400000),
        _model("gemini-4-carbon", "Gemini 4 Carbon", _caps(True, False, True, "deep"), 400000),
        _model("claude-sonnet-5.5", "Claude Sonnet 5.5", _caps(False, True, False, "deep", True), 200000),
        _model("claude-opus-5.5", "Claude Opus 5.5", _caps(False, False, True, "deep", True), 200000),
    ],
    "cline": [
        _model("default", "Cline Default", _caps(True, True, False, "deep", True), 200000),
        _model("deepseek/deepseek-v4.1-flash", "DeepSeek V4.1 Flash", _caps(False, True, True, "fast", True), 128000),
        _model("anthropic/claude-sonnet-5.5", "Claude Sonnet 5.5", _caps(True, True, False, "deep", True), 200000),
        _model("google/gemini-2.5-flash", "Gemini 2.5 Flash", _caps(True, True, False, "fast"), 1000000),
    ],
    "gemini": [
        _model("gemini-2.5-flash", "Gemini 2.5 Flash", _caps(True, True, False, "fast"), 1000000),
        _model("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite", _caps(True, True, False, "ultra"), 1000000),
        _model("gemini-2.5-pro", "Gemini 2.5 Pro", _caps(True, True, True, "deep"), 2000000),
    ],
    "groq": [
        _model("llama-3.3-70b-versatile", "Llama 3.3 70B Versatile", _caps(False, True, False, "ultra"), 131072),
        _model("openai/gpt-oss-120b", "GPT-OSS 120B", _caps(False, False, True, "fast"), 131072),
        _model("qwen/qwen3-32b", "Qwen3 32B", _caps(False, False, True, "fast"), 131072),
        _model("llama-3.1-8b-instant", "Llama 3.1 8B Instant", _caps(False, False, False, "ultra"), 131072),
    ],
    "mistral": [
        _model("mistral-large-latest", "Mistral Large", _caps(True, True, False, "deep"), 131072),
        _model("mistral-small-latest", "Mistral Small", _caps(True, True, False, "fast"), 131072),
        _model("codestral-latest", "Codestral", _caps(False, False, False, "fast", True), 262144),
    ],
    "zai": [
        _model("glm-4.7-flash", "GLM 4.7 Flash", _caps(False, True, False, "ultra"), 128000),
        _model("glm-4.5-flash", "GLM 4.5 Flash", _caps(False, False, False, "fast"), 128000),
        _model("glm-4.6v-flash", "GLM 4.6V Flash", _caps(True, False, False, "fast"), 128000),
    ],
    "cohere": [
        _model("command-a-03-2025", "Command A", _caps(False, True, True, "deep"), 256000),
        _model("command-r-plus", "Command R+", _caps(False, True, False, "fast"), 128000),
        _model("command-r", "Command R", _caps(False, False, False, "fast"), 128000),
    ],
    "nvidia": [
        _model("deepseek-ai/deepseek-v4.1-flash", "DeepSeek V4.1 Flash", _caps(False, False, True, "fast"), 128000),
        _model("deepseek-ai/deepseek-r1", "DeepSeek R1", _caps(False, False, True, "deep"), 128000),
        _model("meta/llama-3.3-70b-instruct", "Llama 3.3 70B Instruct", _caps(False, False, False, "fast"), 128000),
        _model("qwen/qwen2.5-72b-instruct", "Qwen2.5 72B Instruct", _caps(False, False, False, "fast", True), 131072),
    ],
    "sambanova": [
        _model("DeepSeek-V3.1", "DeepSeek V3.1", _caps(False, False, True, "fast"), 128000),
        _model("DeepSeek-V3.2", "DeepSeek V3.2", _caps(False, False, True, "deep"), 128000),
        _model("Meta-Llama-3.3-70B-Instruct", "Llama 3.3 70B Instruct", _caps(False, False, False, "ultra"), 128000),
        _model("gpt-oss-120b", "GPT-OSS 120B", _caps(False, False, True, "fast"), 128000),
    ],
    "cloudflare": [
        _model("@cf/deepseek-ai/deepseek-r1-distill-qwen-32b", "DeepSeek R1 Distill Qwen 32B", _caps(False, False, True, "deep"), 80000),
        _model("@cf/meta/llama-3.3-70b-instruct-fp8-fast", "Llama 3.3 70B FP8 Fast", _caps(False, False, False, "ultra"), 24000),
        _model("@cf/qwen/qwen2.5-coder-32b-instruct", "Qwen2.5 Coder 32B", _caps(False, False, False, "fast", True), 32768),
    ],
    "openrouter": [
        _model("openrouter/free", "OpenRouter Auto Free", _caps(True, True, False, "fast"), 128000),
    ],
    "custom": [
        _model("custom-model", "Custom Model", _caps(False, True, False, "fast"), 0),
    ],
}

DEFAULT_FALLBACK_ORDER = [
    "groq",
    "gemini",
    "nvidia",
    "sambanova",
    "mistral",
    "zai",
    "cohere",
    "cloudflare",
    "openrouter",
    "antigravity",
    "cline",
    "custom",
]


def default_config() -> Dict[str, Any]:
    return {
        "version": 1,
        "active_provider": "auto",
        "active_model": "smart-auto",
        "reasoning_effort": "medium",
        "auto_fallback": True,
        "fallback_order": list(DEFAULT_FALLBACK_ORDER),
        "keys": {p: "" for p in PROVIDER_IDS},
        "custom": {
            "base_url": "",
            "api_key": "",
            "model_id": "",
            "multimodal": False,
        },
        "cloudflare_account_id": "",
        "dynamic_models": {},
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
        elif key == "custom" and isinstance(val, dict):
            cfg["custom"].update({k: val.get(k, cfg["custom"].get(k)) for k in cfg["custom"]})
        elif key == "dynamic_models" and isinstance(val, dict):
            cfg["dynamic_models"] = val
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
    # Fallback ke .env runtime (tanpa menimpa environment nyata).
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
    base = str((PROVIDER_META.get(provider) or {}).get("base_url") or "")
    if provider == "cloudflare":
        account = str(cfg.get("cloudflare_account_id") or _env_lookup(["CLOUDFLARE_ACCOUNT_ID"])).strip()
        if account:
            return f"{base}/{account}/ai/v1"
    return base


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
    if provider == "cloudflare":
        return bool(cfg.get("cloudflare_account_id") or _env_lookup(["CLOUDFLARE_ACCOUNT_ID"]))
    return True


def provider_models(provider: str, config: Optional[Dict[str, Any]] = None) -> List[Dict[str, Any]]:
    provider = (provider or "").lower()
    cfg = config or load_config()
    models = [dict(m) for m in PROVIDER_MODELS.get(provider, [])]
    if provider == "custom":
        custom = cfg.get("custom") or {}
        mid = str(custom.get("model_id") or custom.get("model") or "").strip()
        if mid:
            models = [_model(mid, mid, _caps(bool(custom.get("multimodal")), True, False, "fast"))]
        else:
            models = []
    dynamic = (cfg.get("dynamic_models") or {}).get(provider)
    if isinstance(dynamic, list):
        known = {m.get("id") for m in models}
        for item in dynamic:
            if not isinstance(item, dict):
                continue
            mid = str(item.get("id") or "").strip()
            if not mid or mid in known:
                continue
            caps = item.get("capabilities") if isinstance(item.get("capabilities"), dict) else _caps(
                bool(item.get("multimodal")), False, False, "fast"
            )
            models.append(_model(mid, str(item.get("label") or mid), caps, item.get("context_window")))
    return models


def get_model(provider: str, model_id: Optional[str], config: Optional[Dict[str, Any]] = None) -> Optional[Dict[str, Any]]:
    models = provider_models(provider, config)
    if not model_id:
        return models[0] if models else None
    for m in models:
        if m.get("id") == model_id:
            return m
    for m in models:
        if m.get("id") == "openrouter/free" or m.get("id") == "default":
            return m
    return models[0] if models else None


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
            "needs_account_id": bool(meta.get("needs_account_id")),
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
    cfg["active_model"] = model or "smart-auto"
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
    """Simpan pengaturan hub dari UI; key bermasker diabaikan."""
    cfg = load_config()
    provider = str(payload.get("active_provider") or cfg.get("active_provider") or "auto").lower()
    if provider != "auto" and provider not in PROVIDER_IDS:
        provider = "auto"
    cfg["active_provider"] = provider
    cfg["active_model"] = str(payload.get("active_model") or cfg.get("active_model") or "smart-auto")
    effort = str(payload.get("reasoning_effort") or cfg.get("reasoning_effort") or "medium").lower()
    cfg["reasoning_effort"] = effort if effort in ("low", "medium", "high") else "medium"
    if "auto_fallback" in payload:
        cfg["auto_fallback"] = bool(payload.get("auto_fallback"))
    if isinstance(payload.get("fallback_order"), list) and payload["fallback_order"]:
        seen = []
        for p in payload["fallback_order"]:
            pid = str(p).lower()
            if pid in PROVIDER_IDS and pid not in seen:
                seen.append(pid)
        if seen:
            cfg["fallback_order"] = seen
    incoming_keys = payload.get("keys")
    if isinstance(incoming_keys, dict):
        keys = cfg.get("keys") if isinstance(cfg.get("keys"), dict) else {}
        for p, val in incoming_keys.items():
            pid = str(p).lower()
            if pid not in PROVIDER_IDS:
                continue
            text = str(val or "").strip()
            if not text or "*" in text:
                continue
            keys[pid] = text
        cfg["keys"] = keys
    custom = payload.get("custom")
    if isinstance(custom, dict):
        base = cfg.get("custom") if isinstance(cfg.get("custom"), dict) else {}
        for field in ("base_url", "model", "model_id", "label"):
            if field in custom:
                base[field] = str(custom.get(field) or "")
        if "model" in custom and "model_id" not in custom:
            base["model_id"] = str(custom.get("model") or "")
        if "api_key" in custom:
            key_text = str(custom.get("api_key") or "").strip()
            if key_text and "*" not in key_text:
                base["api_key"] = key_text
        if "multimodal" in custom:
            base["multimodal"] = bool(custom.get("multimodal"))
        cfg["custom"] = base
    if "cloudflare_account_id" in payload:
        account = str(payload.get("cloudflare_account_id") or "").strip()
        if "*" not in account:
            cfg["cloudflare_account_id"] = account
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
        env_name = (PROVIDER_META.get(p) or {}).get("env", "")
        env_value = os.environ.get(env_name, "") if env_name else ""
        masked[p] = mask_key(raw or env_value)
        configured[p] = bool(raw or env_value or (PROVIDER_META.get(p) or {}).get("kind") == "cli")
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
        "cloudflare_account_id": cfg.get("cloudflare_account_id") or "",
        "updated_at": cfg.get("updated_at") or "",
    }


def cli_main(argv: Optional[List[str]] = None) -> int:
    import argparse
    import sys

    parser = argparse.ArgumentParser(prog="provider_config.py", description="ContentStudio Provider Catalog V3.7")
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
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])

    if args.cmd == "set":
        ok = set_active(args.provider, args.model, args.effort, not args.no_fallback)
        print(json.dumps({"status": "ok" if ok else "error"}, ensure_ascii=False))
        return 0 if ok else 1
    if args.cmd == "show":
        cfg = load_config()
        for p in PROVIDER_IDS:
            cfg.setdefault("keys", {})[p] = ""
        print(json.dumps(cfg, indent=2, ensure_ascii=False))
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
