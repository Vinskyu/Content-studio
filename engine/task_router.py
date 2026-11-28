#!/usr/bin/env python3
"""
ContentStudio Smart Task-Based Auto Router (V3.7)

Klasifikasi deterministik tanpa panggilan LLM tambahan. Hasil klasifikasi
memuat batasan keras (hard locks) yang wajib dipatuhi pemilih rute:

- multimodal_visual : HANYA model dengan capabilities.multimodal == true
- pipeline_execution: WAJIB capabilities.tools == true atau CLI agent
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


import os
import re
import sys
from typing import Any, Dict, List, Optional


VISUAL_EXTENSIONS = (".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic",
                     ".mp4", ".mov", ".mkv", ".webm", ".avi")

TASK_LABELS = {
    "multimodal_visual": "Visual Multimodal",
    "deep_creative": "Riset Kreatif Mendalam",
    "pipeline_execution": "Eksekusi Produksi",
    "complex_agent": "Agen Kompleks",
    "fast_chat": "Chat Cepat",
}

# Prioritas per profil (provider, model-preference). Model kosong = pilih model
# pertama yang memenuhi kapabilitas.
TASK_PRIORITIES: Dict[str, List[Dict[str, Any]]] = {
    "multimodal_visual": [
        {"provider": "gemini", "models": []},
        {"provider": "antigravity", "models": ["default"]},
        {"provider": "openrouter", "models": []},
        {"provider": "custom", "models": []},
    ],
    "deep_creative": [
        {"provider": "gemini", "models": []},
        {"provider": "groq", "models": []},
        {"provider": "openrouter", "models": []},
        {"provider": "antigravity", "models": ["default"]},
        {"provider": "custom", "models": []},
    ],
    "pipeline_execution": [
        {"provider": "antigravity", "models": ["default"]},
        {"provider": "gemini", "models": []},
        {"provider": "groq", "models": []},
        {"provider": "openrouter", "models": []},
        {"provider": "custom", "models": []},
    ],
    "complex_agent": [
        {"provider": "antigravity", "models": ["default"]},
        {"provider": "gemini", "models": []},
        {"provider": "groq", "models": []},
        {"provider": "openrouter", "models": []},
        {"provider": "custom", "models": []},
    ],
    "fast_chat": [
        {"provider": "groq", "models": []},
        {"provider": "gemini", "models": []},
        {"provider": "openrouter", "models": []},
        {"provider": "antigravity", "models": ["default"]},
        {"provider": "custom", "models": []},
    ],
}

VISUAL_HINTS = (
    "screenshot", "tangkapan layar", "bedah visual", "analisis visual", "apa isi gambar",
    "deskripsikan gambar", "gambar ini", "foto ini", "video ini", "footage", "contact sheet",
    "analisis gambar", "bedah gambar", "bedah foto", "warna di gambar",
)
PIPELINE_HINTS = (
    "buat proyek", "bikin proyek", "buat video", "susun plan", "plan.json", "resolve-assets",
    "render", "generate-media", "reference dna", "reference_dna", "save-reference-dna",
    "split-scenes", "voice generate", "voiceover", "set-bgm", "publish", "studio_cli",
    "5 worker", "lima worker", "assets", "asset", "produksi video", "render final",
)
DEEP_HINTS = (
    "riset", "research", "ide viral", "kurasi ide", "naskah mendalam", "struktur bab",
    "long-form", "video panjang", "hook", "angle", "anti-monoton", "brainstorm",
    "konsep kreatif", "analisis kompetitor", "pacing", "storyline", "narasi",
)
COMPLEX_HINTS = (
    "debug", "traceback", "error", "exception", "refactor", "ubah banyak file", "orkestrasi",
    "perbaiki bug", "compile", "build gagal", "sistem", "arsitektur", "migrasi",
    "audit kode", "pull request", "optimasi", "root cause",
)
FAST_HINTS = (
    "halo", "hai", "hi", "terima kasih", "makasih", "apa kabar", "singkat", "cepat",
    "ringkas", "variasi keyword", "sinonim", "berapa", "ya", "tidak", "oke",
)


def _has_visual_attachment(attachments: Optional[List[str]]) -> bool:
    for path in attachments or []:
        if str(path).lower().endswith(VISUAL_EXTENSIONS):
            return True
    return False


def _count_hits(text: str, hints: tuple) -> int:
    hits = 0
    for hint in hints:
        if hint in text:
            hits += 1
    return hits


def classify_task(prompt: str, attachments: Optional[List[str]] = None,
                  project_context: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
    """Klasifikasi zero-latency menjadi salah satu dari 5 profil tugas."""
    text = (prompt or "").lower()
    attachments = attachments or []
    project_context = project_context or {}
    signals: List[str] = []

    visual_attachment = _has_visual_attachment(attachments)
    visual_hits = _count_hits(text, VISUAL_HINTS)
    pipeline_hits = _count_hits(text, PIPELINE_HINTS)
    deep_hits = _count_hits(text, DEEP_HINTS)
    complex_hits = _count_hits(text, COMPLEX_HINTS)
    fast_hits = _count_hits(text, FAST_HINTS)

    if visual_attachment:
        signals.append("attachment_visual")
    if visual_hits:
        signals.append(f"visual_hints={visual_hits}")
    if pipeline_hits:
        signals.append(f"pipeline_hints={pipeline_hits}")
    if deep_hits:
        signals.append(f"deep_hints={deep_hits}")
    if complex_hits:
        signals.append(f"complex_hints={complex_hits}")
    if fast_hits:
        signals.append(f"fast_hints={fast_hits}")

    task_type = "fast_chat"
    if visual_attachment or visual_hits >= 1:
        task_type = "multimodal_visual"
    elif complex_hits >= 1:
        task_type = "complex_agent"
    elif pipeline_hits >= 1 and pipeline_hits >= deep_hits:
        task_type = "pipeline_execution"
    elif deep_hits >= 1:
        task_type = "deep_creative"
    elif len(text) > 420:
        task_type = "deep_creative"
    elif fast_hits >= 1 or len(text) <= 80:
        task_type = "fast_chat"
    else:
        task_type = "pipeline_execution" if project_context.get("active_project") else "fast_chat"

    requires_multimodal = task_type == "multimodal_visual"
    requires_tools = task_type in ("pipeline_execution", "complex_agent")
    requires_reasoning = task_type in ("deep_creative",)
    preferred_speed = {
        "fast_chat": "ultra",
        "multimodal_visual": "fast",
        "pipeline_execution": "fast",
        "deep_creative": "deep",
        "complex_agent": "deep",
    }[task_type]

    return {
        "task_type": task_type,
        "label": TASK_LABELS[task_type],
        "requires_multimodal": requires_multimodal,
        "requires_tools": requires_tools,
        "requires_reasoning": requires_reasoning,
        "preferred_speed": preferred_speed,
        "confidence": "high" if visual_attachment or complex_hits or pipeline_hits else "medium",
        "signals": signals,
    }


def _provider_lookup(catalog: Dict[str, Any]) -> Dict[str, Dict[str, Any]]:
    providers = catalog.get("providers") or []
    if isinstance(providers, dict):
        return {str(k).lower(): v for k, v in providers.items() if isinstance(v, dict)}
    return {str(p.get("id")).lower(): p for p in providers if isinstance(p, dict)}


def _quota_ok(provider: str, usage_tracker: Any) -> bool:
    try:
        if usage_tracker is None:
            return True
        if hasattr(usage_tracker, "is_available"):
            return bool(usage_tracker.is_available(provider))
        if isinstance(usage_tracker, dict):
            row = (usage_tracker.get("providers") or {}).get(provider) or usage_tracker.get(provider)
            if isinstance(row, dict):
                return str(row.get("status") or "ok") != "exhausted"
        return True
    except Exception:
        return True


def _model_matches(model: Dict[str, Any], profile: Dict[str, Any]) -> bool:
    caps = model.get("capabilities") or {}
    if profile.get("requires_multimodal") and not caps.get("multimodal"):
        return False
    return True


def _route_for(provider: Dict[str, Any], preferred_models: List[str],
               profile: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    if not provider.get("configured"):
        return None
    is_cli = str(provider.get("kind") or "") == "cli"
    if profile.get("requires_multimodal") and is_cli:
        # CLI tetap boleh, tetapi hanya model multimodal yang dipilih.
        pass
    models = provider.get("models") or []
    ordered: List[Dict[str, Any]] = []
    for mid in preferred_models:
        for m in models:
            if m.get("id") == mid and _model_matches(m, profile):
                ordered.append(m)
    for m in models:
        if m not in ordered and _model_matches(m, profile):
            ordered.append(m)
    if not ordered:
        return None
    if profile.get("requires_tools") and not is_cli:
        with_tools = [m for m in ordered if (m.get("capabilities") or {}).get("tools")]
        if not with_tools:
            return None
        ordered = with_tools
    chosen = ordered[0]
    return {
        "provider": provider.get("id"),
        "provider_label": provider.get("label"),
        "kind": provider.get("kind"),
        "model": chosen.get("id"),
        "model_label": chosen.get("label"),
        "capabilities": chosen.get("capabilities") or {},
    }


def select_best_route(task_profile: Dict[str, Any], catalog: Dict[str, Any],
                      usage_tracker: Any = None) -> Dict[str, Any]:
    """Pilih rute terbaik + fallback_chain yang memenuhi kunci kapabilitas & kuota."""
    task_type = task_profile.get("task_type") or "fast_chat"
    providers = _provider_lookup(catalog)
    priorities = TASK_PRIORITIES.get(task_type, TASK_PRIORITIES["fast_chat"])

    candidates: List[Dict[str, Any]] = []
    seen = set()
    for pref in priorities:
        pid = pref.get("provider")
        provider = providers.get(pid) or {}
        route = _route_for(provider, pref.get("models") or [], task_profile)
        if route and route["provider"] not in seen and _quota_ok(pid, usage_tracker):
            candidates.append(route)
            seen.add(pid)

    # Cadangan universal: provider terkonfigurasi yang memenuhi kapabilitas.
    fallback_order = catalog.get("fallback_order") or list(providers.keys())
    for pid in fallback_order:
        pid = str(pid).lower()
        if pid in seen:
            continue
        provider = providers.get(pid) or {}
        route = _route_for(provider, [], task_profile)
        if route and _quota_ok(pid, usage_tracker):
            candidates.append(route)
            seen.add(pid)

    if not candidates:
        for pid, provider in providers.items():
            route = _route_for(provider, [], task_profile)
            if route:
                candidates.append(route)
                break

    if not candidates:
        antigravity_provider = providers.get("antigravity") or {"id": "antigravity", "label": "Antigravity (Bawaan)", "kind": "cli", "configured": True, "models": [{"id": "default", "label": "Antigravity (Bawaan)", "capabilities": {"multimodal": True, "tools": True, "reasoning": False, "speed": "fast"}}]}
        route = _route_for(antigravity_provider, ["default"], task_profile) or {
            "provider": "antigravity",
            "provider_label": "Antigravity (Bawaan)",
            "kind": "cli",
            "model": "default",
            "model_label": "Antigravity (Bawaan)",
            "capabilities": {"multimodal": True, "tools": True, "reasoning": False, "speed": "fast"},
        }
        candidates.append(route)

    best = candidates[0]
    return {
        "provider": best["provider"],
        "provider_label": best.get("provider_label"),
        "kind": best.get("kind"),
        "model": best["model"],
        "model_label": best.get("model_label"),
        "capabilities": best.get("capabilities") or {},
        "fallback_chain": candidates[1:],
        "task_type": task_type,
        "label": task_profile.get("label") or TASK_LABELS.get(task_type),
        "profile": task_profile,
    }


def cli_main(argv: Optional[List[str]] = None) -> int:
    import argparse
    import json

    parser = argparse.ArgumentParser(prog="task_router.py", description="ContentStudio Task Router V3.7")
    parser.add_argument("prompt", nargs="?", default="")
    parser.add_argument("--attachment", action="append", default=[])
    parser.add_argument("--select", action="store_true", help="Pilih rute berdasarkan katalog aktif")
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])

    profile = classify_task(args.prompt, args.attachment)
    if args.select:
        try:
            from engine.provider_config import catalog
            from engine.usage_tracker import summary
            route = select_best_route(profile, catalog(), summary())
            route.pop("profile", None)
            profile["route"] = route
        except Exception as exc:
            profile["route_error"] = str(exc)
    print(json.dumps(profile, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(cli_main())
