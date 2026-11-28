#!/usr/bin/env python3
"""
ContentStudio Live Quota & Official Limits Tracker (V3.7)

Menyimpan ledger pemakaian di `config/usage_ledger.json`, menghitung batas
harian/bulanan, membaca header rate limit nyata (`x-ratelimit-*`, `retry-after`),
dan menyediakan status `ok` / `warning` / `exhausted` siap tampil di UI.
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
import os
import re
import sys
import time
from datetime import datetime, timezone
from typing import Any, Dict, List, Optional, Tuple


from engine.runtime import get_root_dir

LEDGER_REL_PATH = os.path.join("config", "usage_ledger.json")

# Baseline resmi (konservatif) — bisa diperbarui otomatis dari header / endpoint /models.
BASELINE_LIMITS: Dict[str, Dict[str, Any]] = {
    "antigravity": {"period": "dynamic", "rpm": 0, "requests_per_day": 0, "requests_per_month": 0,
                    "tokens_per_day": 0, "unit": "CLI lokal", "note": "Mengikuti kuota CLI bawaan"},
    "cline": {"period": "dynamic", "rpm": 0, "requests_per_day": 0, "requests_per_month": 0,
              "tokens_per_day": 0, "unit": "CLI lokal", "note": "Headless agent lokal"},
    "gemini": {"period": "dynamic", "rpm": 10, "requests_per_day": 250, "requests_per_month": 0,
               "tokens_per_day": 0, "unit": "req/hari", "note": "AI Studio free tier"},
    "groq": {"period": "dynamic", "rpm": 30, "requests_per_day": 1000, "requests_per_month": 0,
             "tokens_per_day": 0, "unit": "req/hari", "note": "Free tier per model"},
    "mistral": {"period": "monthly", "rpm": 60, "requests_per_day": 0, "requests_per_month": 1000,
                "tokens_per_day": 0, "unit": "req/bulan", "note": "Free mode eksperimental"},
    "zai": {"period": "dynamic", "rpm": 20, "requests_per_day": 500, "requests_per_month": 0,
            "tokens_per_day": 0, "unit": "req/hari", "note": "GLM Flash free"},
    "cohere": {"period": "monthly", "rpm": 20, "requests_per_day": 0, "requests_per_month": 1000,
               "tokens_per_day": 0, "unit": "req/bulan", "note": "Trial key"},
    "nvidia": {"period": "dynamic", "rpm": 40, "requests_per_day": 1000, "requests_per_month": 0,
               "tokens_per_day": 0, "unit": "req/hari", "note": "NIM free credits"},
    "sambanova": {"period": "dynamic", "rpm": 20, "requests_per_day": 500, "requests_per_month": 0,
                  "tokens_per_day": 0, "unit": "req/hari", "note": "Cloud free tier"},
    "cloudflare": {"period": "daily", "rpm": 0, "requests_per_day": 0, "requests_per_month": 0,
                   "tokens_per_day": 10000, "unit": "Neurons/hari", "note": "Workers AI free neurons"},
    "openrouter": {"period": "dynamic", "rpm": 20, "requests_per_day": 50, "requests_per_month": 0,
                   "tokens_per_day": 0, "unit": "req/hari", "note": "Free model :free"},
    "custom": {"period": "dynamic", "rpm": 0, "requests_per_day": 0, "requests_per_month": 0,
               "tokens_per_day": 0, "unit": "custom", "note": "Mengikuti penyedia kustom"},
}


def get_ledger_path() -> str:
    return os.path.join(get_root_dir(), LEDGER_REL_PATH)


def _today() -> str:
    return datetime.now().strftime("%Y-%m-%d")


def _month() -> str:
    return datetime.now().strftime("%Y-%m")


def default_ledger() -> Dict[str, Any]:
    return {
        "version": 1,
        "day": _today(),
        "month": _month(),
        "providers": {},
        "last_sync": "",
    }


def _empty_provider() -> Dict[str, Any]:
    return {
        "requests_today": 0,
        "tokens_today": 0,
        "requests_month": 0,
        "tokens_month": 0,
        "last_used_at": "",
        "models": {},
        "limits": {},
        "live_limits": {},
        "retry_after_until": 0,
        "synced_at": "",
        "errors": 0,
    }


def load_ledger() -> Dict[str, Any]:
    path = get_ledger_path()
    data: Any = {}
    if os.path.exists(path):
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except Exception:
            data = {}
    ledger = default_ledger()
    if not isinstance(data, dict):
        return ledger
    ledger["providers"] = data.get("providers") if isinstance(data.get("providers"), dict) else {}
    ledger["last_sync"] = str(data.get("last_sync") or "")
    for pid, val in list(ledger["providers"].items()):
        base = _empty_provider()
        if isinstance(val, dict):
            base.update(val)
        ledger["providers"][pid] = base
    if data.get("day") != _today():
        for p in ledger["providers"].values():
            p["requests_today"] = 0
            p["tokens_today"] = 0
        ledger["day"] = _today()
    if data.get("month") != _month():
        for p in ledger["providers"].values():
            p["requests_month"] = 0
            p["tokens_month"] = 0
        ledger["month"] = _month()
    return ledger


def save_ledger(ledger: Dict[str, Any]) -> bool:
    path = get_ledger_path()
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(ledger, f, indent=2, ensure_ascii=False)
        os.replace(tmp, path)
        return True
    except Exception:
        return False


def _provider_entry(ledger: Dict[str, Any], provider: str) -> Dict[str, Any]:
    pid = (provider or "").lower()
    entry = ledger["providers"].get(pid)
    if not isinstance(entry, dict):
        entry = _empty_provider()
        ledger["providers"][pid] = entry
    for key, val in _empty_provider().items():
        entry.setdefault(key, val)
    return entry


def record_usage(provider: str, model: str = "", tokens: int = 0, requests: int = 1,
                 error: bool = False) -> Dict[str, Any]:
    ledger = load_ledger()
    entry = _provider_entry(ledger, provider)
    entry["requests_today"] = int(entry.get("requests_today") or 0) + max(0, requests)
    entry["requests_month"] = int(entry.get("requests_month") or 0) + max(0, requests)
    entry["tokens_today"] = int(entry.get("tokens_today") or 0) + max(0, tokens)
    entry["tokens_month"] = int(entry.get("tokens_month") or 0) + max(0, tokens)
    entry["last_used_at"] = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    if error:
        entry["errors"] = int(entry.get("errors") or 0) + 1
    if model:
        models = entry.get("models")
        if not isinstance(models, dict):
            models = {}
        m = models.get(model) if isinstance(models.get(model), dict) else {}
        m["requests"] = int(m.get("requests") or 0) + max(0, requests)
        m["tokens"] = int(m.get("tokens") or 0) + max(0, tokens)
        m["last_used_at"] = entry["last_used_at"]
        models[model] = m
        entry["models"] = models
    save_ledger(ledger)
    return entry


def _num(value: Any) -> Optional[float]:
    if value is None:
        return None
    try:
        return float(str(value).strip())
    except Exception:
        return None


def _epoch_from_header(value: str) -> int:
    val = str(value or "").strip()
    if not val:
        return 0
    if re.fullmatch(r"\d+(\.\d+)?", val):
        num = float(val)
        if num > 1e12:
            return int(num / 1000)
        if num > 1e9:
            return int(num)
    try:
        from email.utils import parsedate_to_datetime
        return int(parsedate_to_datetime(val).timestamp())
    except Exception:
        pass
    for fmt in ("%Y-%m-%dT%H:%M:%S%z", "%Y-%m-%dT%H:%M:%SZ"):
        try:
            return int(datetime.strptime(val, fmt).timestamp())
        except Exception:
            continue
    return 0


def update_from_headers(provider: str, headers: Any, model: str = "") -> Dict[str, Any]:
    """Baca header rate limit nyata dan simpan batas aktual + retry-after."""
    h = {str(k).lower(): str(v) for k, v in (headers or {}).items()} if hasattr(headers, "items") else {}
    ledger = load_ledger()
    entry = _provider_entry(ledger, provider)
    live = entry.get("live_limits") if isinstance(entry.get("live_limits"), dict) else {}
    if model:
        live.setdefault("models", {})
        live["models"].setdefault(model, {})
        target = live["models"][model]
    else:
        target = live

    mapping = {
        "requests_limit": ["x-ratelimit-limit-requests", "x-ratelimit-limit"],
        "requests_remaining": ["x-ratelimit-remaining-requests", "x-ratelimit-remaining"],
        "tokens_limit": ["x-ratelimit-limit-tokens", "x-ratelimit-token-limit"],
        "tokens_remaining": ["x-ratelimit-remaining-tokens", "x-ratelimit-token-remaining"],
        "reset_at": ["x-ratelimit-reset-requests", "x-ratelimit-reset", "x-ratelimit-reset-tokens"],
        "retry_after": ["retry-after"],
    }
    for out_key, candidates in mapping.items():
        for cand in candidates:
            if cand in h:
                raw = h[cand]
                if out_key in ("reset_at", "retry_after"):
                    num = _num(raw)
                    if num is None:
                        target[out_key] = _epoch_from_header(raw)
                    elif out_key == "retry_after":
                        target[out_key] = int(num)
                    else:
                        target[out_key] = int(num) if num > 1e9 else int(time.time() + num)
                else:
                    n = _num(raw)
                    if n is not None:
                        target[out_key] = int(n)
                break

    # Ringkas batas terakhir ke level provider agar quota_status membacanya.
    for key in ("requests_limit", "requests_remaining", "tokens_limit", "tokens_remaining",
                "reset_at", "retry_after"):
        if target.get(key) is not None:
            live[key] = target[key]

    now = int(time.time())
    if target.get("retry_after"):
        entry["retry_after_until"] = now + int(target["retry_after"])
    elif int(target.get("requests_remaining") if target.get("requests_remaining") is not None else 1) <= 0:
        reset_at = int(target.get("reset_at") or 0)
        entry["retry_after_until"] = reset_at if reset_at > now else now + 60
    entry["live_limits"] = live
    entry["synced_at"] = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    save_ledger(ledger)
    return entry


def _limits_for(provider: str, entry: Dict[str, Any]) -> Dict[str, Any]:
    limits = dict(BASELINE_LIMITS.get((provider or "").lower(), BASELINE_LIMITS["custom"]))
    live = entry.get("live_limits") if isinstance(entry.get("live_limits"), dict) else {}
    models_live = live.get("models") if isinstance(live.get("models"), dict) else {}
    if models_live:
        latest = list(models_live.values())[-1]
        if isinstance(latest, dict):
            live = {**live, **latest}
    if live.get("requests_limit"):
        limits["requests_per_day"] = int(live["requests_limit"])
        limits["period"] = "dynamic"
        limits["unit"] = "req/hari"
    if live.get("tokens_limit"):
        limits["tokens_per_day"] = int(live["tokens_limit"])
        limits["unit"] = "token/hari"
    return limits


def quota_status(provider: str, model: str = "") -> Dict[str, Any]:
    ledger = load_ledger()
    entry = _provider_entry(ledger, provider)
    limits = _limits_for(provider, entry)
    period = limits.get("period") or "dynamic"

    used = 0
    limit = 0
    unit = limits.get("unit") or "req/hari"
    if period == "monthly" and limits.get("requests_per_month"):
        used, limit = int(entry.get("requests_month") or 0), int(limits["requests_per_month"])
    elif limits.get("tokens_per_day"):
        used, limit = int(entry.get("tokens_today") or 0), int(limits["tokens_per_day"])
    elif limits.get("requests_per_day"):
        used, limit = int(entry.get("requests_today") or 0), int(limits["requests_per_day"])
    elif limits.get("requests_per_month"):
        used, limit = int(entry.get("requests_month") or 0), int(limits["requests_per_month"])

    percent = round((used / limit) * 100.0, 1) if limit > 0 else 0.0
    retry_until = int(entry.get("retry_after_until") or 0)
    now = int(time.time())
    exhausted = bool(retry_until and retry_until > now)
    if limit > 0 and used >= limit:
        exhausted = True
    if exhausted:
        status = "exhausted"
    elif percent >= 80.0:
        status = "warning"
    else:
        status = "ok"

    if limit > 0:
        label = f"{used} / {limit} {unit}"
    else:
        label = f"{used} {unit} (dinamis)"
    if exhausted and retry_until > now:
        mins = max(1, int((retry_until - now) / 60))
        label += f" | tunggu {mins}m"
    if status == "warning":
        label += " | mendekati batas"

    return {
        "provider": (provider or "").lower(),
        "model": model,
        "status": status,
        "percent": percent,
        "used": used,
        "limit": limit,
        "unit": unit,
        "period": period,
        "label": label,
        "requests_today": int(entry.get("requests_today") or 0),
        "tokens_today": int(entry.get("tokens_today") or 0),
        "requests_month": int(entry.get("requests_month") or 0),
        "tokens_month": int(entry.get("tokens_month") or 0),
        "last_used_at": entry.get("last_used_at") or "",
        "synced_at": entry.get("synced_at") or "",
        "retry_after_until": retry_until,
        "limits": limits,
        "live_limits": entry.get("live_limits") or {},
    }


def summary(provider_ids: Optional[List[str]] = None) -> Dict[str, Any]:
    from engine.provider_config import PROVIDER_IDS
    ids = provider_ids or list(PROVIDER_IDS)
    rows = [quota_status(p) for p in ids]
    return {
        "day": _today(),
        "month": _month(),
        "providers": {r["provider"]: r for r in rows},
        "last_sync": load_ledger().get("last_sync", ""),
    }


def is_available(provider: str) -> bool:
    return quota_status(provider).get("status") != "exhausted"


def mark_retry_after(provider: str, seconds: int = 60) -> None:
    ledger = load_ledger()
    entry = _provider_entry(ledger, provider)
    entry["retry_after_until"] = int(time.time()) + max(1, int(seconds))
    save_ledger(ledger)


LIVE_MODEL_ENDPOINTS: Dict[str, str] = {
    "gemini": "https://generativelanguage.googleapis.com/v1beta/models",
    "groq": "https://api.groq.com/openai/v1/models",
    "mistral": "https://api.mistral.ai/v1/models",
    "nvidia": "https://integrate.api.nvidia.com/v1/models",
    "sambanova": "https://api.sambanova.ai/v1/models",
    "openrouter": "https://openrouter.ai/api/v1/models",
}


def _http_get_json(url: str, headers: Dict[str, str], timeout: float = 8.0) -> Any:
    import urllib.request
    req = urllib.request.Request(url, headers=headers, method="GET")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8", errors="replace"))


def refresh_live_catalog_and_limits() -> Dict[str, Any]:
    """Tarik model gratis terbaru + kuota key dari endpoint resmi (best effort)."""
    from engine.provider_config import load_config, resolve_key, save_config

    cfg = load_config()
    updates: Dict[str, Any] = {"synced": [], "failed": [], "models_added": {}}
    ledger = load_ledger()

    for provider, url in LIVE_MODEL_ENDPOINTS.items():
        key = resolve_key(provider, cfg)
        if not key:
            continue
        headers = {"Authorization": f"Bearer {key}", "Accept": "application/json"}
        if provider == "gemini":
            url_with_key = f"{url}?key={key}"
            headers = {"Accept": "application/json"}
        else:
            url_with_key = url
        try:
            data = _http_get_json(url_with_key, headers)
            models: List[Dict[str, Any]] = []
            if provider == "gemini":
                for item in (data.get("models") or []):
                    methods = item.get("supportedGenerationMethods") or []
                    if "generateContent" not in methods:
                        continue
                    mid = str(item.get("name") or "").replace("models/", "")
                    if not mid:
                        continue
                    models.append({
                        "id": mid,
                        "label": item.get("displayName") or mid,
                        "multimodal": "generateContent" in methods,
                        "capabilities": {"multimodal": True, "tools": True, "reasoning": "pro" in mid,
                                         "speed": "fast", "coding": False},
                    })
            elif provider == "openrouter":
                for item in (data.get("data") or []):
                    pricing = item.get("pricing") or {}
                    if str(pricing.get("prompt")) not in ("0", "0.0", "0.00"):
                        continue
                    mid = str(item.get("id") or "")
                    if not mid:
                        continue
                    modality = str((item.get("architecture") or {}).get("modality") or "")
                    models.append({
                        "id": mid,
                        "label": item.get("name") or mid,
                        "multimodal": "image" in modality,
                        "capabilities": {"multimodal": "image" in modality, "tools": True,
                                         "reasoning": False, "speed": "fast", "coding": False},
                        "context_window": item.get("context_length"),
                    })
            else:
                for item in (data.get("data") or []):
                    mid = str(item.get("id") or "")
                    if mid:
                        models.append({"id": mid, "label": mid, "multimodal": False,
                                       "capabilities": {"multimodal": False, "tools": False,
                                                        "reasoning": False, "speed": "fast", "coding": False}})
            if models:
                cfg.setdefault("dynamic_models", {})[provider] = models[:200]
                updates["models_added"][provider] = len(models)
            entry = _provider_entry(ledger, provider)
            entry["synced_at"] = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
            updates["synced"].append(provider)
        except Exception as exc:
            updates["failed"].append({"provider": provider, "error": str(exc)[:160]})

    # Kuota key OpenRouter (opsional, hanya bila key tersedia).
    or_key = resolve_key("openrouter", cfg)
    if or_key:
        try:
            data = _http_get_json("https://openrouter.ai/api/v1/auth/key",
                                  {"Authorization": f"Bearer {or_key}"})
            d = data.get("data") or {}
            entry = _provider_entry(ledger, "openrouter")
            entry["live_limits"] = {
                "requests_limit": d.get("rate_limit", {}).get("requests"),
                "requests_remaining": None,
                "usage": d.get("usage"),
                "limit": d.get("limit"),
                "is_free_tier": d.get("is_free_tier"),
            }
            entry["synced_at"] = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
        except Exception:
            pass

    cfg["updated_at"] = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    save_config(cfg)
    ledger["last_sync"] = cfg["updated_at"]
    save_ledger(ledger)
    updates["last_sync"] = ledger["last_sync"]
    return updates


def cli_main(argv: Optional[List[str]] = None) -> int:
    import argparse

    parser = argparse.ArgumentParser(prog="usage_tracker.py", description="ContentStudio Quota Tracker V3.7")
    sub = parser.add_subparsers(dest="cmd")
    p_status = sub.add_parser("status", help="Ringkasan kuota semua provider (JSON)")
    p_status.add_argument("--provider", default=None)
    p_headers = sub.add_parser("headers", help="Simulasi/pembaruan batas dari header (JSON string)")
    p_headers.add_argument("--provider", required=True)
    p_headers.add_argument("--headers", required=True)
    p_headers.add_argument("--model", default="")
    p_sync = sub.add_parser("refresh", help="Sinkronisasi live katalog & limit")
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])

    if args.cmd == "refresh":
        print(json.dumps(refresh_live_catalog_and_limits(), indent=2, ensure_ascii=False))
        return 0
    if args.cmd == "headers":
        try:
            headers = json.loads(args.headers)
        except Exception:
            print(json.dumps({"status": "error", "message": "headers bukan JSON valid"}))
            return 1
        update_from_headers(args.provider, headers, args.model)
        print(json.dumps(quota_status(args.provider, args.model), indent=2, ensure_ascii=False))
        return 0
    if args.cmd == "status" and args.provider:
        print(json.dumps(quota_status(args.provider), indent=2, ensure_ascii=False))
        return 0
    print(json.dumps(summary(), indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(cli_main())
