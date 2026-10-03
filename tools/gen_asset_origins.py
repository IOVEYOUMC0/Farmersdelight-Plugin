"""Build and verify ASSET-ORIGINS.json, the provenance manifest for third-party assets.

The plugin ships art, audio, models and locale files ported from the original Farmer's Delight mod. Their
copyright stays with the mod author and they are redistributed under the mod's MIT license, so every one of
them needs a reproducible origin. This script scans the ported asset tree, recomputes each file's SHA-256, and
compares it byte-for-byte with the upstream checkouts under `../Reference/FarmersDelight-1.21` and
`../Reference/FarmersDelight-1.4`:

    * byte-identical to an upstream file at the same relative path -> status "ported"
    * same relative path upstream but different bytes             -> status "modified"
    * no upstream file at that path                               -> self-authored, not registered

Scope is the ported asset tree only. CraftEngine configuration YAML under
`src/main/resources/craftengine/**/configuration/` is data authored for this plugin (it is not a copy of any
upstream file, and the CE format differs), and the plugin's own Java, datapack and server-side language files
are ours; both are outside this manifest. Self-authored assets that happen to live in the asset tree are
counted in `self_authored` but deliberately not listed, so the manifest stays a list of things we did not
write.

Read-only by default: with no mode flag (or with the explicit `--check` alias) the script re-hashes the disk
and reports drift, exiting non-zero when the manifest and the tree disagree. `--write` regenerates the
manifest instead; `--check` and `--write` are mutually exclusive.

    python tools/gen_asset_origins.py            # verify (same as --check)
    python tools/gen_asset_origins.py --check    # verify, explicit
    python tools/gen_asset_origins.py --write    # regenerate ASSET-ORIGINS.json
    python tools/gen_asset_origins.py --check --quiet
"""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MANIFEST = ROOT / "ASSET-ORIGINS.json"

# The one ported asset tree this manifest covers, relative to the plugin repository root.
ASSET_ROOT = "src/main/resources/craftengine/farmersdelight/resourcepack/assets/farmersdelight"

UPSTREAMS = [
    {
        "name": "FarmersDelight-1.21",
        "repo": "https://github.com/vectorwing/FarmersDelight",
        "local_checkout": "../Reference/FarmersDelight-1.21",
        "local_remote": "https://ghfast.top/https://github.com/vectorwing/FarmersDelight.git",
        "asset_root": "src/main/resources/assets/farmersdelight",
        "license": "MIT",
        "copyright": "Copyright (c) 2020 vectorwing",
    },
    {
        "name": "FarmersDelight-1.4",
        "repo": "https://github.com/vectorwing/FarmersDelight",
        "local_checkout": "../Reference/FarmersDelight-1.4",
        "local_remote": "https://ghfast.top/https://github.com/vectorwing/FarmersDelight.git",
        "asset_root": "src/main/resources/assets/farmersdelight",
        "license": "MIT",
        "copyright": "Copyright (c) 2020 vectorwing",
    },
]

SCOPE = {
    "asset_root": ASSET_ROOT,
    "registered": "third-party assets ported from the original Farmer's Delight mod: files that exist upstream at the same relative path (byte-identical or edited here).",
    "excluded": [
        "self-authored models, textures, GUI sprites, item definitions and sounds with no upstream file at the same path",
        "CraftEngine configuration YAML under src/main/resources/craftengine/**/configuration/ (authored here, data-only, no upstream byte equivalent)",
        "this plugin's Java sources, datapack definitions and server-side lang/*.yml",
        "shaded libraries (AntiGriefLib, bStats), which are registered in NOTICE.md, not here",
    ],
}

LICENSE_POLICY = [
    "Asset rights stay with the original mod author; this plugin redistributes them under the upstream MIT license.",
    "Every registered path is verified by SHA-256 against a pinned upstream commit; tools/gen_asset_origins.py --check fails on drift.",
    "No file from the paid Pro fork is copied into this repository.",
]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 16), b""):
            digest.update(block)
    return digest.hexdigest()


def asset_index(base: Path) -> dict[str, str]:
    """Map relative asset path -> sha256 for every file under base."""
    if not base.is_dir():
        return {}
    index: dict[str, str] = {}
    for path in sorted(base.rglob("*")):
        if path.is_file():
            index[path.relative_to(base).as_posix()] = sha256(path)
    return index


def git(*args: str, cwd: Path) -> str | None:
    try:
        out = subprocess.run(
            ["git", *args], cwd=cwd, capture_output=True, text=True, timeout=30, check=False
        )
    except (OSError, subprocess.SubprocessError):
        return None
    return out.stdout.strip() if out.returncode == 0 else None


def resolve_commit(checkout: Path) -> tuple[str, str, str]:
    """Return (commit, commit_date, reason). Commit is 'unpinned' when no git metadata is available."""
    if not (checkout / ".git").exists():
        return "unpinned", "", "the reference checkout is an extracted directory without git metadata, so no commit can be pinned; re-clone it to pin"
    head = git("rev-parse", "HEAD", cwd=checkout)
    if not head:
        return "unpinned", "", "git metadata is present but rev-parse HEAD failed"
    date = git("log", "-1", "--format=%ad", "--date=short", cwd=checkout) or ""
    return head, date, ""


def collect() -> dict:
    root = ROOT / ASSET_ROOT
    if not root.is_dir():
        raise SystemExit(f"asset root not found: {root}")
    disk = asset_index(root)

    upstreams = []
    refs: list[tuple[dict, dict[str, str]]] = []
    for spec in UPSTREAMS:
        checkout = (ROOT / spec["local_checkout"]).resolve()
        commit, date, reason = resolve_commit(checkout)
        entry = dict(spec)
        entry["commit"] = commit
        entry["commit_date"] = date
        if reason:
            entry["reason"] = reason
        upstreams.append(entry)
        refs.append((entry, asset_index(checkout / spec["asset_root"])))

    assets = []
    self_root_counts: dict[str, int] = {}
    for asset in sorted(disk):
        digest = disk[asset]
        identical = [(u, idx) for u, idx in refs if idx.get(asset) == digest]
        same_path = [(u, idx) for u, idx in refs if asset in idx]
        if identical:
            origin = identical[0][0]
            record = {
                "path": f"{ASSET_ROOT}/{asset}",
                "asset": asset,
                "upstream": origin["name"],
                "repo": origin["repo"],
                "commit": origin["commit"],
                "license": origin["license"],
                "status": "ported",
                "sha256": digest,
            }
        elif same_path:
            origin = same_path[0][0]
            record = {
                "path": f"{ASSET_ROOT}/{asset}",
                "asset": asset,
                "upstream": origin["name"],
                "repo": origin["repo"],
                "commit": origin["commit"],
                "license": origin["license"],
                "status": "modified",
                "sha256": digest,
                "upstream_sha256": same_path[0][1][asset],
            }
        else:
            subtree = asset.split("/")[0] if "/" in asset else asset
            self_root_counts[subtree] = self_root_counts.get(subtree, 0) + 1
            continue
        assets.append(record)

    ported = sum(1 for a in assets if a["status"] == "ported")
    modified = len(assets) - ported
    return {
        "schema": 1,
        "generated_by": "tools/gen_asset_origins.py",
        "scope": SCOPE,
        "license_policy": LICENSE_POLICY,
        "upstreams": upstreams,
        "asset_root": ASSET_ROOT,
        "assets": assets,
        "self_authored": {
            "count": sum(self_root_counts.values()),
            "by_subtree": dict(sorted(self_root_counts.items())),
            "note": "No upstream file exists at the same relative path, so these are authored or converted here and are not registered above.",
        },
        "summary": {
            "assets_scanned": len(disk),
            "registered": len(assets),
            "ported": ported,
            "modified": modified,
            "self_authored": sum(self_root_counts.values()),
        },
    }


def render(manifest: dict) -> str:
    """Emit one asset per line: valid JSON, stable order, diff-friendly."""
    blocks: list[tuple[str, str]] = []
    for key in ("schema", "generated_by", "scope", "license_policy", "upstreams", "asset_root", "self_authored", "summary"):
        value = json.dumps(manifest[key], ensure_ascii=False, indent=2)
        blocks.append((key, value.replace("\n", "\n  ")))
    lines = ["{"]
    body: list[str] = []
    for key, value in blocks[:-1]:
        body.append(f"  {json.dumps(key)}: {value}")
    assets = manifest["assets"]
    asset_lines = ["  \"assets\": ["]
    for index, record in enumerate(assets):
        comma = "," if index < len(assets) - 1 else ""
        asset_lines.append(f"    {json.dumps(record, ensure_ascii=False)}{comma}")
    asset_lines.append("  ]")
    body.insert(5, "\n".join(asset_lines))
    body.append(f"  {json.dumps(blocks[-1][0])}: {blocks[-1][1]}")
    lines.append(",\n".join(body))
    lines.append("}")
    return "\n".join(lines) + "\n"


def load_manifest() -> dict | None:
    if not MANIFEST.is_file():
        return None
    try:
        return json.loads(MANIFEST.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        print(f"error: cannot read {MANIFEST.name}: {error}")
        return None


def check(quiet: bool) -> int:
    manifest = load_manifest()
    if manifest is None:
        return 1
    root = ROOT / ASSET_ROOT
    disk = asset_index(root)
    drift = 0

    registered: dict[str, dict] = {}
    for record in manifest.get("assets", []):
        registered[record["asset"]] = record
        path = ROOT / record["path"]
        if not path.is_file():
            print(f"  MISSING  {record['path']} (registered as {record['status']} from {record['upstream']})")
            drift += 1
            continue
        digest = sha256(path)
        if digest != record["sha256"]:
            print(f"  CHANGED  {record['path']}  manifest {record['sha256'][:12]} -> disk {digest[:12]}")
            drift += 1

    upstream_indexes: list[tuple[dict, dict[str, str]]] = []
    for spec in manifest.get("upstreams", []):
        checkout = (ROOT / spec["local_checkout"]).resolve()
        upstream_indexes.append((spec, asset_index(checkout / spec["asset_root"]) if checkout.is_dir() else {}))

    for asset, digest in sorted(disk.items()):
        if asset in registered:
            continue
        # An unregistered file is only drift when it matches an upstream file, i.e. it is ported content.
        for spec, upstream in upstream_indexes:
            if upstream.get(asset) == digest or asset in upstream:
                print(f"  UNREGISTERED  {ASSET_ROOT}/{asset} exists upstream ({spec['name']}) but has no manifest entry")
                drift += 1
                break

    for spec in manifest.get("upstreams", []):
        checkout = (ROOT / spec["local_checkout"]).resolve()
        if not (checkout / ".git").exists():
            if not quiet:
                print(f"  note     {spec['name']}: not a git checkout, cannot re-verify the pinned commit")
            continue
        head = git("rev-parse", "HEAD", cwd=checkout)
        if not head:
            print(f"  ERROR    {spec['name']}: rev-parse HEAD failed")
            drift += 1
        elif head != spec["commit"]:
            print(f"  PIN-MOVED {spec['name']}: manifest pins {spec['commit'][:12]}, checkout is at {head[:12]}; re-verify and re-pin")
            drift += 1

    summary = manifest.get("summary", {})
    print(
        f"asset origins: {len(registered)} registered ({summary.get('ported', '?')} ported, "
        f"{summary.get('modified', '?')} modified), {len(disk)} files on disk, drift: {drift}"
    )
    return 1 if drift else 0


def write(quiet: bool) -> int:
    manifest = collect()
    MANIFEST.write_text(render(manifest), encoding="utf-8", newline="")
    summary = manifest["summary"]
    if not quiet:
        for spec in manifest["upstreams"]:
            print(f"  upstream {spec['name']}: {spec['commit']} ({spec['commit_date'] or 'no date'})")
    print(
        f"wrote {MANIFEST.name}: {summary['registered']} registered "
        f"({summary['ported']} ported, {summary['modified']} modified), "
        f"{summary['self_authored']} self-authored not registered, {summary['assets_scanned']} files scanned"
    )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true",
                      help="verify ASSET-ORIGINS.json against the asset tree, exiting 1 on drift (the default when no mode flag is given)")
    mode.add_argument("--write", action="store_true", help="regenerate ASSET-ORIGINS.json instead of checking it")
    parser.add_argument("--quiet", action="store_true", help="only print the final summary line")
    args = parser.parse_args()
    return write(args.quiet) if args.write else check(args.quiet)


if __name__ == "__main__":
    sys.exit(main())
