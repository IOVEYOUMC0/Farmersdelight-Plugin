#!/usr/bin/env python3
"""Reusable third-party addon checks for FarmersDelight.

This is the addon test kit: it checks the API boundary, the Paper plugin dependency, the API
feature gate, and (unless --skip-build) runs the addon's Gradle build. With --smoke it also copies
a server's jars/properties into an isolated work directory, starts that server on a free port,
asserts the addon enables, and stops it.

Usage:
    python tools/addon_testkit.py ../MyAddon
    python tools/addon_testkit.py ../MyAddon --skip-build
    python tools/addon_testkit.py ../MyAddon --skip-build --smoke \
        --server-dir ../test1 \
        --fd-jar ../FarmersDelight/build/libs/farmersdelight-1.0.4.jar

The source server is never started or modified; smoke copies jars/properties into the addon's
build/addon-testkit-smoke directory and starts its own fresh world on a random local port.
"""
from __future__ import annotations

import argparse
import json
import queue
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path

FD_API_PREFIX = "com.huidu.farmersdelight.api."
FD_PREFIX = "com.huidu.farmersdelight."
FQCN = re.compile(r"com\.huidu\.farmersdelight\.[A-Za-z0-9_.$]+")
DEPRECATED_API = (
    "com.huidu.farmersdelight.api.block.CraftEngineBlockAccess",
    "com.huidu.farmersdelight.api.registry.ContentRegistration",
    "com.huidu.farmersdelight.api.item.SlotPlaceholder",
    "com.huidu.farmersdelight.api.util.TooltipUtils",
    "com.huidu.farmersdelight.api.util.CeItemInterop",
    "com.huidu.farmersdelight.api.pack.AddonPackSections",
)


class Check:
    def __init__(self, name: str, ok: bool, detail: str = "") -> None:
        self.name = name
        self.ok = ok
        self.detail = detail


def java_sources(root: Path) -> list[Path]:
    source_root = root / "src" / "main" / "java"
    if not source_root.is_dir():
        return []
    return sorted(source_root.rglob("*.java"))


def check_api_boundary(root: Path) -> Check:
    violations: list[str] = []
    for path in java_sources(root):
        text = path.read_text(encoding="utf-8", errors="replace")
        for match in FQCN.finditer(text):
            fqcn = match.group(0)
            if not fqcn.startswith(FD_API_PREFIX):
                violations.append(f"{path.relative_to(root)}: {fqcn}")
    if violations:
        return Check("api-boundary", False, "imports outside api.**: " + "; ".join(violations[:5]))
    return Check("api-boundary", True, "all FarmersDelight references stay inside api.**")


def check_paper_dependency(root: Path) -> Check:
    manifests = list((root / "src" / "main" / "resources").glob("paper-plugin*.yml"))
    if not manifests:
        return Check("paper-dependency", False, "no paper-plugin.yml under src/main/resources")
    for manifest in manifests:
        text = manifest.read_text(encoding="utf-8", errors="replace")
        if "FarmersDelight:" not in text:
            continue
        block = text.split("FarmersDelight:", 1)[1].splitlines()[:8]
        joined = "\n".join(block)
        if "required: true" in joined and "join-classpath: true" in joined:
            return Check("paper-dependency", True, f"{manifest.name} declares FarmersDelight required + join-classpath")
        return Check("paper-dependency", False,
                     f"{manifest.name}: FarmersDelight dependency needs required: true and join-classpath: true")
    return Check("paper-dependency", False, "paper-plugin.yml does not declare the FarmersDelight dependency")


def check_feature_gate(root: Path) -> Check:
    text = "\n".join(path.read_text(encoding="utf-8", errors="replace") for path in java_sources(root))
    if "apiVersion()" in text or "hasFeature(" in text:
        return Check("feature-gate", True, "source uses apiVersion()/hasFeature()")
    return Check("feature-gate", False, "no apiVersion()/hasFeature() gate in addon Java sources")


def check_deprecated_api(root: Path) -> Check:
    violations: list[str] = []
    for path in java_sources(root):
        text = path.read_text(encoding="utf-8", errors="replace")
        for deprecated in DEPRECATED_API:
            if deprecated in text:
                violations.append(f"{path.relative_to(root)}: {deprecated}")
    if violations:
        return Check("deprecated-api", False,
                     "use the compat/neutral replacement: " + "; ".join(violations[:5]))
    return Check("deprecated-api", True, "no deprecated advanced API imports")


def run_gradle(root: Path) -> Check:
    if sys.platform.startswith("win"):
        wrapper = root / "gradlew.bat"
    else:
        wrapper = root / "gradlew"
    if not wrapper.is_file():
        return Check("build", False, f"no Gradle wrapper at {wrapper}")
    command = [str(wrapper), "build", "--no-daemon"]
    try:
        result = subprocess.run(command, cwd=root, text=True, capture_output=True, timeout=1800)
    except (OSError, subprocess.TimeoutExpired) as error:
        return Check("build", False, f"could not run {wrapper}: {error}")
    if result.returncode != 0:
        tail = (result.stderr or result.stdout or "").strip().splitlines()[-8:]
        return Check("build", False, "Gradle build failed: " + " | ".join(tail))
    return Check("build", True, "addon Gradle build passed")


def _find_java(explicit: str | None) -> str:
    if explicit:
        return explicit
    java_home = Path(__import__("os").environ.get("JAVA_HOME", ""))
    if java_home.is_dir():
        candidate = java_home / "bin" / ("java.exe" if sys.platform.startswith("win") else "java")
        if candidate.is_file():
            return str(candidate)
    found = shutil.which("java")
    if not found:
        raise RuntimeError("java not found; pass --java or set JAVA_HOME")
    return found


def _find_addon_jar(root: Path, explicit: str | None) -> Path | None:
    if explicit:
        jar = Path(explicit)
        return jar if jar.is_file() else None
    libs = root / "build" / "libs"
    if not libs.is_dir():
        return None
    jars = [jar for jar in libs.glob("*.jar")
            if not any(token in jar.name for token in ("sources", "javadoc", "-api"))]
    return max(jars, key=lambda jar: jar.stat().st_mtime) if jars else None


def _find_server_jar(server_dir: Path, explicit: str | None) -> Path | None:
    if explicit:
        jar = Path(explicit)
        return jar if jar.is_file() else None
    for pattern in ("purpur-*.jar", "paper-*.jar", "shiroha.jar"):
        matches = sorted(server_dir.glob(pattern), key=lambda jar: jar.stat().st_mtime, reverse=True)
        if matches:
            return matches[0]
    return None


def _copy_required_plugins(server_dir: Path, plugins_dst: Path, fd_jar: Path | None) -> list[str]:
    source = server_dir / "plugins"
    copied: list[str] = []
    patterns = {
        "craft-engine": "craft-engine-*.jar",
        "bettermodel": "bettermodel-*.jar",
        "advancement-api": "UltimateAdvancementAPI-*.jar",
    }
    for label, pattern in patterns.items():
        matches = sorted(source.glob(pattern), key=lambda jar: jar.stat().st_mtime, reverse=True)
        if not matches:
            raise RuntimeError(f"missing required plugin {label} ({pattern}) in {source}")
        shutil.copy2(matches[0], plugins_dst / matches[0].name)
        copied.append(matches[0].name)
    if fd_jar is None:
        matches = sorted(source.glob("farmersdelight-*.jar"),
                         key=lambda jar: jar.stat().st_mtime, reverse=True)
        if not matches:
            raise RuntimeError(f"missing FarmersDelight plugin jar in {source}")
        fd_jar = matches[0]
    shutil.copy2(fd_jar, plugins_dst / "farmersdelight.jar")
    copied.append(fd_jar.name)
    return copied


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def _write_server_properties(source: Path, target: Path) -> None:
    lines = source.read_text(encoding="utf-8", errors="replace").splitlines() if source.is_file() else []
    overrides = {
        "server-port": str(_free_port()),
        "online-mode": "false",
        "max-players": "1",
        "view-distance": "2",
        "simulation-distance": "2",
        "spawn-protection": "0",
        "allow-nether": "false",
        "generate-structures": "false",
        "level-name": "smoke-world",
        "enable-rcon": "false",
        "enable-query": "false",
        "motd": "fd-addon-testkit",
    }
    seen: set[str] = set()
    output: list[str] = []
    for line in lines:
        key = line.split("=", 1)[0].strip() if "=" in line else ""
        if key in overrides:
            output.append(f"{key}={overrides[key]}")
            seen.add(key)
        elif not line.lstrip().startswith("#"):
            output.append(line)
    for key, value in overrides.items():
        if key not in seen:
            output.append(f"{key}={value}")
    target.write_text("\n".join(output) + "\n", encoding="utf-8")


def check_server_smoke(root: Path, server_dir: Path, java: str | None, server_jar: str | None,
                       fd_jar: str | None, addon_jar: str | None, timeout: int) -> Check:
    server = _find_server_jar(server_dir, server_jar)
    if server is None:
        return Check("server-smoke", False, f"no server jar found in {server_dir}")
    addon = _find_addon_jar(root, addon_jar)
    if addon is None:
        return Check("server-smoke", False, f"no addon jar found under {root / 'build' / 'libs'}")
    try:
        java_cmd = _find_java(java)
    except RuntimeError as error:
        return Check("server-smoke", False, str(error))

    workdir = root / "build" / "addon-testkit-smoke"
    if workdir.exists():
        shutil.rmtree(workdir)
    plugins = workdir / "plugins"
    plugins.mkdir(parents=True)
    try:
        shutil.copy2(server, workdir / server.name)
        _write_server_properties(server_dir / "server.properties", workdir / "server.properties")
        (workdir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
        copied = _copy_required_plugins(server_dir, plugins, Path(fd_jar) if fd_jar else None)
        shutil.copy2(addon, plugins / addon.name)
        copied.append(addon.name)
    except (OSError, RuntimeError) as error:
        return Check("server-smoke", False, f"prepare failed: {error}")

    manifest = root / "src" / "main" / "resources" / "paper-plugin.yml"
    addon_name = "unknown"
    if manifest.is_file():
        for line in manifest.read_text(encoding="utf-8", errors="replace").splitlines():
            if line.startswith("name:"):
                addon_name = line.split(":", 1)[1].strip().strip("'\"")
                break

    command = [java_cmd, "-Xms512M", "-Xmx2048M", "-jar", server.name, "nogui"]
    log_path = workdir / "console.log"
    lines: list[str] = []
    process: subprocess.Popen[str] | None = None
    try:
        with log_path.open("w", encoding="utf-8", errors="replace") as log:
            process = subprocess.Popen(command, cwd=workdir, stdin=subprocess.PIPE,
                                       stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       text=True, encoding="utf-8", errors="replace", bufsize=1)
            assert process.stdout is not None
            output: "queue.Queue[str | None]" = queue.Queue()

            def reader() -> None:
                try:
                    for output_line in process.stdout:
                        output.put(output_line)
                finally:
                    output.put(None)

            threading.Thread(target=reader, daemon=True).start()
            deadline = time.monotonic() + timeout
            done = False
            while time.monotonic() < deadline:
                try:
                    line = output.get(timeout=1.0)
                except queue.Empty:
                    if process.poll() is not None:
                        break
                    continue
                if line is None:
                    break
                lines.append(line)
                log.write(line)
                log.flush()
                if "Done (" in line:
                    done = True
                    break
            if done:
                # Give the addon's onEnable a few seconds to log its own status.
                end = time.monotonic() + 5
                while time.monotonic() < end:
                    try:
                        line = output.get(timeout=1.0)
                    except queue.Empty:
                        continue
                    if line is None:
                        break
                    lines.append(line)
                    log.write(line)
                    log.flush()
            try:
                if process.stdin:
                    process.stdin.write("stop\n")
                    process.stdin.flush()
            except OSError:
                pass
            try:
                process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
    except (OSError, subprocess.SubprocessError) as error:
        return Check("server-smoke", False, f"server failed to run: {error}")
    finally:
        if process is not None and process.poll() is None:
            process.kill()

    joined = "".join(lines)
    if not done:
        return Check("server-smoke", False,
                     "server did not reach Done; see " + str(log_path))
    if f"[{addon_name}] Enabling" not in joined and f"[{addon_name}]" not in joined:
        return Check("server-smoke", False,
                     f"{addon_name} was not seen in the server log; see {log_path}")
    unexpected = [line.strip() for line in lines
                  if ("[ERROR]" in line or "SEVERE" in line)
                  and "build.zip" not in line
                  and "UnsupportedClassVersionError" not in line
                  and "class file version" not in line]
    if unexpected:
        return Check("server-smoke", False,
                     "server/addon errors: " + " | ".join(unexpected[:3]) + f"; see {log_path}")
    return Check("server-smoke", True,
                 f"{addon_name} loaded on {server.name}; plugins={','.join(copied)}; log={log_path}")

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="FarmersDelight third-party addon checks")
    parser.add_argument("addon", type=Path, help="addon project directory")
    parser.add_argument("--skip-build", action="store_true", help="only run source/manifest checks")
    parser.add_argument("--json", action="store_true", help="print a machine-readable report")
    parser.add_argument("--smoke", action="store_true", help="start an isolated copied server with the addon")
    parser.add_argument("--server-dir", type=Path, help="existing server directory to copy jars/properties from")
    parser.add_argument("--server-jar", help="server jar path (default: detect in --server-dir)")
    parser.add_argument("--fd-jar", help="FarmersDelight plugin jar (default: newest in server plugins)")
    parser.add_argument("--addon-jar", help="addon jar (default: newest build/libs jar)")
    parser.add_argument("--java", help="java executable or JAVA_HOME/bin/java")
    parser.add_argument("--timeout", type=int, default=300, help="server startup timeout in seconds")
    args = parser.parse_args(argv)

    root = args.addon.resolve()
    if not root.is_dir():
        print(f"not an addon directory: {root}", file=sys.stderr)
        return 2

    checks = [
        check_api_boundary(root),
        check_deprecated_api(root),
        check_paper_dependency(root),
        check_feature_gate(root),
    ]
    if not args.skip_build:
        checks.append(run_gradle(root))
    if args.smoke:
        if args.server_dir is None:
            checks.append(Check("server-smoke", False, "--smoke requires --server-dir"))
        else:
            checks.append(check_server_smoke(root, args.server_dir.resolve(), args.java, args.server_jar,
                                             args.fd_jar, args.addon_jar, args.timeout))

    if args.json:
        print(json.dumps([{"name": check.name, "ok": check.ok, "detail": check.detail}
                          for check in checks], ensure_ascii=False, indent=2))
    else:
        for check in checks:
            print(f"[{'PASS' if check.ok else 'FAIL'}] {check.name}: {check.detail}")
    return 0 if all(check.ok for check in checks) else 1


if __name__ == "__main__":
    raise SystemExit(main())
