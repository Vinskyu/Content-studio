#!/usr/bin/env python3
"""
ContentStudio Hybrid Asset Router (engine/assets/router.py)

Membaca kontrak per scene + `reference_dna.json`, lalu memilih worker dengan
MATRIKS FALLBACK KETAT:

  clip     -> query klip alternatif -> still/thumbnail sumber -> graphic
              (DILARANG jatuh ke stok generik)
  generate -> prompt lebih ringkas -> ganti provider generator
              (DILARANG memakai stok milik orang lain)
  stock    -> query alternatif -> generate
              (DILARANG mengambil stok acak tidak relevan)
  graphic  -> render lokal
  local    -> error jelas bila file tidak ada (DILARANG cari ke internet)

Setiap output WAJIB lolos `qa_gate.verify_asset(...)`.

Checkpoint & resume: scene yang asetnya sudah ada dan valid dilewati
(kecuali `force=True`), sehingga proyek berpuluh scene bisa dilanjutkan.
"""

import sys
import os

_script_dir = os.path.dirname(os.path.abspath(__file__))
if sys.path and sys.path[0] == _script_dir:
    sys.path.pop(0)
_engine_dir = os.path.dirname(_script_dir)
_root_dir = os.path.dirname(_engine_dir)
for _p in (_engine_dir, _root_dir):
    if _p not in sys.path:
        sys.path.append(_p)

import glob
import json
from typing import Any, Callable, Dict, List, Optional

from engine.runtime import get_workspaces_dir
from engine.reference_dna import (
    SOURCE_TYPES,
    load_reference_dna,
    normalize_aspect_ratio,
    scene_contract,
)
from engine.assets import qa_gate
from engine.assets import clip_worker, generate_worker, graphic_worker, local_worker, stock_worker

REPORT_FILENAME = "resolve_report.json"
MEDIA_EXTS = (".jpg", ".jpeg", ".png", ".webp", ".bmp", ".mp4", ".mov", ".webm", ".mkv", ".m4v")


def _emit_step(message: str) -> None:
    sys.stderr.write("[STEP] %s\n" % message)
    sys.stderr.flush()


def _emit_think(message: str) -> None:
    sys.stderr.write("[THINK] %s\n" % message)
    sys.stderr.flush()


def project_dir(project: str) -> str:
    return os.path.join(get_workspaces_dir(), os.path.basename(str(project or "").strip()))


def assets_dir(project: str) -> str:
    return os.path.join(project_dir(project), "assets")


def scene_output_base(project: str, index: int) -> str:
    return os.path.join(assets_dir(project), "scene_%02d" % (int(index) + 1))


def existing_checkpoint(project: str, index: int) -> Optional[str]:
    """Aset scene yang sudah ada dan valid (checkpoint resume)."""
    pattern = scene_output_base(project, index) + ".*"
    for candidate in sorted(glob.glob(pattern)):
        if candidate.lower().endswith(MEDIA_EXTS) and os.path.isfile(candidate):
            ok, _reason = qa_gate.verify_asset(candidate, "auto")
            if ok:
                return candidate
    return None


def _infer_source_type(contract: Dict[str, Any], dna: Dict[str, Any]) -> str:
    """
    Tentukan source_type paling masuk akal bila kontrak scene belum mengisinya.

    Berbasis sifat visual (kata kunci & template), bukan niche konten.
    """
    explicit = contract.get("source_type")
    if explicit in SOURCE_TYPES:
        return explicit
    if contract.get("visual") and local_worker.find_local_asset("", contract):
        return "local"
    goal = (str(contract.get("visual_goal") or "") + " " + " ".join(contract.get("keywords") or [])).lower()
    if contract.get("graphic_template") or any(
        token in goal for token in ("statistic", "statistik", "chart", "grafik", "quote", "kutipan",
                                    "chapter", "bab", "tier", "comparison", "perbandingan", "score", "skor")
    ):
        return "graphic"
    if contract.get("keywords"):
        return "stock"
    priority = dna.get("sources_priority") or list(SOURCE_TYPES)
    for candidate in priority:
        if candidate in SOURCE_TYPES:
            return candidate
    return "generate"


def _graphic_payload(contract: Dict[str, Any]) -> Dict[str, Any]:
    payload = dict(contract.get("graphic_data") or {})
    payload.setdefault("title", contract.get("title") or contract.get("visual_goal") or "")
    if contract.get("narration") and "subtitle" not in payload:
        payload.setdefault("subtitle", contract.get("narration")[:160])
    if contract.get("chapter") not in (None, ""):
        payload.setdefault("chapter", contract.get("chapter"))
        payload.setdefault("eyebrow", "BAB")
    if contract.get("title") and "eyebrow" not in payload:
        payload.setdefault("eyebrow", "CATATAN")
    return payload


def _graphic_template(contract: Dict[str, Any]) -> str:
    template = contract.get("graphic_template")
    if template in graphic_worker.GRAPHIC_TEMPLATES:
        return template
    if contract.get("chapter") not in (None, ""):
        return "chapter"
    payload = contract.get("graphic_data") or {}
    if payload.get("tiers"):
        return "tier"
    if payload.get("left_value") or payload.get("right_value"):
        return "comparison"
    if payload.get("value") or payload.get("number"):
        return "stat"
    return "quote"


def _run_graphic(project: str, contract: Dict[str, Any], index: int, dna: Dict[str, Any],
                 template: Optional[str] = None) -> Dict[str, Any]:
    out_path = scene_output_base(project, index) + ".png"
    res = graphic_worker.render_graphic(
        out_path,
        template or _graphic_template(contract),
        _graphic_payload(contract),
        dna,
    )
    if res.get("status") == "ok":
        res["kind"] = "image"
    return res


def _resolve_clip(project: str, scene: Dict[str, Any], contract: Dict[str, Any], index: int,
                  dna: Dict[str, Any], errors: List[str]) -> Dict[str, Any]:
    queries = clip_worker.build_queries(contract)
    out_path = scene_output_base(project, index) + ".mp4"

    primary = clip_worker.fetch_clip(scene, out_path, dna, project, index)
    if primary.get("status") == "ok":
        return primary
    errors.append("clip: " + str(primary.get("message") or "")[:120])

    if len(queries) > 1:
        _emit_think("Query klip pertama belum menghasilkan. Mencoba kata kunci alternatif.")
        alt = clip_worker.fetch_clip(scene, out_path, dna, project, index, query_override=queries[1])
        if alt.get("status") == "ok":
            return alt
        errors.append("clip-alt: " + str(alt.get("message") or "")[:120])

    source_url = str((scene or {}).get("source_url") or (scene or {}).get("url") or "").strip()
    if source_url:
        _emit_think("Klip utuh tidak bisa dipotong. Mengambil still sumber untuk pan/zoom.")
        still = clip_worker.fetch_still_from_url(source_url, scene_output_base(project, index), dna.get("aspect_ratio"))
        if still.get("status") == "ok":
            still["motion"] = "pan_zoom_suggested"
            return still
        errors.append("clip-still: " + str(still.get("message") or "")[:120])

    # Jalur terakhir clip adalah graphic. Stok generik dilarang.
    _emit_think("Footage tidak tersedia. Menutup scene dengan kartu grafis yang relevan.")
    graphic = _run_graphic(project, contract, index, dna)
    if graphic.get("status") == "ok":
        return graphic
    errors.append("clip-graphic: " + str(graphic.get("message") or "")[:120])
    return {"status": "error", "message": "Rantai clip habis tanpa aset valid"}


def _resolve_generate(project: str, contract: Dict[str, Any], index: int,
                      dna: Dict[str, Any], errors: List[str]) -> Dict[str, Any]:
    out_path = scene_output_base(project, index) + ".png"
    prompt = contract.get("visual_goal") or contract.get("title") or "visual pendukung adegan"
    negative = str((contract.get("graphic_data") or {}).get("negative_prompt") or "")

    primary = generate_worker.generate_image(
        out_path, prompt, dna=dna, project=project, scene_index=index,
        negative_prompt=negative, seed=contract.get("seed"),
    )
    if primary.get("status") == "ok":
        return primary
    errors.append("generate: " + str(primary.get("message") or "")[:120])

    _emit_think("Prompt awal terlalu berat atau provider sibuk. Mencoba prompt lebih ringkas.")
    short = generate_worker.generate_image(
        out_path, prompt, dna=dna, project=project, scene_index=index,
        negative_prompt=negative, shorten=True, seed=contract.get("seed"),
    )
    if short.get("status") == "ok":
        return short
    errors.append("generate-short: " + str(short.get("message") or "")[:120])

    _emit_think("Mengganti provider generator sebagai jalur terakhir, tanpa memakai stok orang lain.")
    switch = generate_worker.generate_image(
        out_path, prompt, dna=dna, project=project, scene_index=index,
        negative_prompt=negative, seed=contract.get("seed"), force_provider="pollinations",
    )
    if switch.get("status") == "ok":
        return switch
    errors.append("generate-switch: " + str(switch.get("message") or "")[:120])
    return {"status": "error", "message": "Rantai generate habis tanpa aset valid"}


def _resolve_stock(project: str, contract: Dict[str, Any], index: int,
                   dna: Dict[str, Any], errors: List[str]) -> Dict[str, Any]:
    out_path = scene_output_base(project, index) + ".jpg"
    first = stock_worker.fetch_stock(
        out_path, contract.get("keywords") or [contract.get("visual_goal") or ""],
        dna, project=project, scene_index=index,
    )
    if first.get("status") == "ok":
        return first
    errors.append("stock: " + str(first.get("message") or "")[:120])

    alt_queries = []
    title = str(contract.get("title") or "").strip()
    goal = str(contract.get("visual_goal") or "").strip()
    if title:
        alt_queries.append(title)
    if goal:
        alt_queries.append(" ".join(goal.split()[:6]))
    if alt_queries:
        _emit_think("Stok pertama tidak relevan. Mencoba kata kunci alternatif.")
        alt = stock_worker.fetch_stock(out_path, alt_queries, dna, project=project, scene_index=index)
        if alt.get("status") == "ok":
            return alt
        errors.append("stock-alt: " + str(alt.get("message") or "")[:120])

    _emit_think("Stok relevan tidak ditemukan. Beralih ke generator agar tetap relevan.")
    generated = generate_worker.generate_image(
        scene_output_base(project, index) + ".png",
        contract.get("visual_goal") or contract.get("title") or "b-roll relevan",
        dna=dna, project=project, scene_index=index,
    )
    if generated.get("status") == "ok":
        return generated
    errors.append("stock-generate: " + str(generated.get("message") or "")[:120])
    return {"status": "error", "message": "Rantai stock habis tanpa aset valid"}


def _resolve_local(project: str, contract: Dict[str, Any], index: int,
                   errors: List[str]) -> Dict[str, Any]:
    # Nama file mengikuti aset asli: pakai base tanpa ekstensi, worker menambah ekstensi.
    result = local_worker.fetch_local(contract, scene_output_base(project, index), project)
    if result.get("status") != "ok":
        errors.append("local: " + str(result.get("message") or "")[:120])
    return result


def resolve_scene(
    project: str,
    scene: Dict[str, Any],
    index: int,
    dna: Dict[str, Any],
    force: bool = False,
) -> Dict[str, Any]:
    """Resolusi aset satu scene dengan checkpoint + fallback ketat + QA gate."""
    contract = scene_contract(scene, dna)
    contract["index"] = index
    source_type = _infer_source_type(contract, dna)

    if not force:
        existing = existing_checkpoint(project, index)
        if existing:
            _emit_step("Scene %d sudah punya aset valid, checkpoint dilewati." % (index + 1))
            return {
                "index": index,
                "status": "skipped",
                "source_type": source_type,
                "file": existing,
                "reason": "checkpoint valid",
            }

    _emit_think("Menimbang sumber visual paling tepat untuk scene %d (%s)." % (index + 1, source_type))
    errors: List[str] = []
    result: Dict[str, Any] = {"status": "error", "message": "Tidak ada worker yang berjalan"}

    chain = contract.get("fallback_chain") or []
    if chain:
        _emit_think("Scene memakai rantai fallback eksplisit dari kontrak.")
        for step in chain:
            if step == "graphic":
                result = _run_graphic(project, contract, index, dna)
            elif step == "local":
                result = _resolve_local(project, contract, index, errors)
            elif step == "stock":
                result = _resolve_stock(project, contract, index, dna, errors)
            elif step == "generate":
                result = _resolve_generate(project, contract, index, dna, errors)
            else:
                result = _resolve_clip(project, scene, contract, index, dna, errors)
            if result.get("status") == "ok":
                break
    else:
        if source_type == "clip":
            result = _resolve_clip(project, scene, contract, index, dna, errors)
        elif source_type == "generate":
            result = _resolve_generate(project, contract, index, dna, errors)
        elif source_type == "stock":
            result = _resolve_stock(project, contract, index, dna, errors)
        elif source_type == "local":
            result = _resolve_local(project, contract, index, errors)
        else:
            result = _run_graphic(project, contract, index, dna)

    if result.get("status") != "ok":
        return {
            "index": index,
            "status": "failed",
            "source_type": source_type,
            "error": result.get("message") or "Gagal",
            "attempts": errors,
        }

    file_path = str(result.get("file") or "")
    expected_kind = str(result.get("kind") or "auto")
    ok, reason = qa_gate.verify_asset(file_path, expected_kind)
    if not ok:
        return {
            "index": index,
            "status": "failed",
            "source_type": source_type,
            "file": file_path,
            "error": "QA gate menolak aset: " + reason,
            "attempts": errors,
        }

    return {
        "index": index,
        "status": "resolved",
        "source_type": result.get("kind") or source_type,
        "worker": source_type,
        "file": file_path,
        "qa": reason,
        "motion": result.get("motion"),
        "attempts": errors,
    }


def _load_scenes(project: str) -> tuple:
    """Ambil scene dari plan.json bila ada, jika tidak dari project.json."""
    p_dir = project_dir(project)
    plan_path = os.path.join(p_dir, "plan.json")
    if os.path.exists(plan_path):
        try:
            with open(plan_path, "r", encoding="utf-8") as f:
                plan = json.load(f)
            segments = plan.get("segments") if isinstance(plan, dict) else None
            if isinstance(segments, list) and segments:
                return segments, "plan.json"
        except Exception:
            pass
    project_path = os.path.join(p_dir, "project.json")
    try:
        with open(project_path, "r", encoding="utf-8") as f:
            pj = json.load(f)
        scenes = pj.get("scenes") if isinstance(pj, dict) else []
        return (scenes if isinstance(scenes, list) else []), "project.json"
    except Exception:
        return [], "none"


def resolve_assets(
    project: str,
    force: bool = False,
    only: Optional[int] = None,
    write_back: bool = True,
) -> Dict[str, Any]:
    """Resolusi aset seluruh scene proyek dengan checkpoint resume."""
    clean_project = os.path.basename(str(project or "").strip())
    scenes, scene_source = _load_scenes(clean_project)
    if not scenes:
        return {"status": "error", "message": "Tidak ada scene di plan.json maupun project.json"}
    dna = load_reference_dna(clean_project)
    os.makedirs(assets_dir(clean_project), exist_ok=True)

    _emit_step("Resolusi aset %d scene (format %s, rasio %s)" % (
        len(scenes), dna.get("format_scale"), dna.get("aspect_ratio"),
    ))

    results: List[Dict[str, Any]] = []
    for idx, scene in enumerate(scenes):
        if only is not None and int(only) != idx:
            continue
        scene_dict = scene if isinstance(scene, dict) else {}
        scene_dict.setdefault("index", idx)
        results.append(resolve_scene(clean_project, scene_dict, idx, dna, force=force))

    resolved = [r for r in results if r.get("status") == "resolved"]
    skipped = [r for r in results if r.get("status") == "skipped"]
    failed = [r for r in results if r.get("status") == "failed"]

    if write_back and scene_source == "project.json":
        _write_back_visuals(clean_project, results)

    report = {
        "status": "ok" if not failed else "partial",
        "project": clean_project,
        "format_scale": dna.get("format_scale"),
        "aspect_ratio": dna.get("aspect_ratio"),
        "scene_source": scene_source,
        "total": len(results),
        "resolved": len(resolved),
        "skipped": len(skipped),
        "failed": len(failed),
        "results": results,
    }
    try:
        with open(os.path.join(assets_dir(clean_project), REPORT_FILENAME), "w", encoding="utf-8") as f:
            json.dump(report, f, indent=2, ensure_ascii=False)
    except Exception:
        pass
    return report


def _write_back_visuals(project: str, results: List[Dict[str, Any]]) -> None:
    """Update field `visual` di project.json agar render langsung memakai aset hasil router."""
    project_path = os.path.join(project_dir(project), "project.json")
    try:
        with open(project_path, "r", encoding="utf-8") as f:
            pj = json.load(f)
    except Exception:
        return
    scenes = pj.get("scenes")
    if not isinstance(scenes, list):
        return
    by_index = {r.get("index"): r for r in results if r.get("file")}
    changed = False
    for idx, scene in enumerate(scenes):
        item = by_index.get(idx)
        if not item or not isinstance(scene, dict):
            continue
        rel = os.path.relpath(str(item["file"]), project_dir(project))
        scene["visual"] = rel
        if item.get("motion"):
            scene["motion"] = item["motion"]
        changed = True
    if changed:
        pj["stage"] = pj.get("stage") or "visual"
        try:
            with open(project_path, "w", encoding="utf-8") as f:
                json.dump(pj, f, indent=2, ensure_ascii=False)
        except Exception:
            pass


def cli_main(argv=None) -> int:
    import argparse

    parser = argparse.ArgumentParser(prog="router.py", description="ContentStudio Hybrid Asset Router")
    parser.add_argument("--project", required=True)
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--scene", type=int, default=None, help="0-based index scene tunggal")
    parser.add_argument("--no-write-back", action="store_true")
    args = parser.parse_args(argv if argv is not None else sys.argv[1:])

    res = resolve_assets(args.project, force=args.force, only=args.scene, write_back=not args.no_write_back)
    print(json.dumps(res, indent=2, ensure_ascii=False))
    return 0 if res.get("status") == "ok" else 1


if __name__ == "__main__":
    raise SystemExit(cli_main())
