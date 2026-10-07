"""Exercise the unmodified updater HTTP/parser/hash code against GitHub.

Explicit network test; downloads the current stable APK to captures, without
installing it, invoking ADB or using an OBD radio/address. Run after the Gradle
unit tests have populated the dependency cache. Android APK inspection, space
allocation and installer UI are outside this JVM test.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("offline_base", ROOT / "tools/offline_viewmodel/run.py")
BASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASE)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--use-gh-auth", action="store_true", help="Use local gh authentication in memory for this private repository; never log/embed the token")
    args = parser.parse_args()
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or
               str(Path.home() / "AppData/Local/Android/Sdk"))
    android = sdk / "platforms/android-34/android.jar"
    if not android.is_file():
        parser.error("Android SDK 34 not found; set ANDROID_HOME")
    output_root = ROOT / "captures/updates_20261007"
    output_root.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix=datetime.now().strftime("live_%Y%m%d_%H%M%S_"), dir=output_root))
    print(f"Artifacts: {directory}", flush=True)
    source_root = ROOT / "app/src/main/java/com/nico/obd2dash/updates"
    sources = [source_root / name for name in ("UpdatePolicy.kt", "UpdateDownloader.kt", "AppUpdateRepository.kt", "UpdateCredentials.kt")]
    inputs = [*sources, HERE / "live_check.kt", Path(__file__).resolve()]
    hashes = {BASE.relative(path): BASE.sha256(path) for path in inputs}
    java = BASE.java_binary()
    stdlib = BASE.cached_jar("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.24")
    coroutines = BASE.cached_jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.1")
    json_jar = BASE.cached_jar("org.json", "json", "20240303")
    annotations = BASE.cached_jar("org.jetbrains", "annotations", "13.0")
    compiler = [BASE.cached_jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "1.9.24"), stdlib,
                BASE.cached_jar("org.jetbrains.kotlin", "kotlin-script-runtime", "1.9.24"),
                BASE.cached_jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
                BASE.cached_jar("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"), annotations]
    classes = directory / "classes"
    classes.mkdir()
    compile_command = [str(java), "-Xmx768m", "-Dfile.encoding=UTF-8", "-cp", os.pathsep.join(map(str, compiler)),
                       "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
                       "-cp", os.pathsep.join(map(str, [stdlib, coroutines, annotations, json_jar, android])),
                       "-d", str(classes), *map(str, [*sources, HERE / "live_check.kt"])]
    run_command = [str(java), "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-cp",
                   os.pathsep.join(map(str, [classes, stdlib, json_jar])),
                   "com.nico.obd2dash.updates.Live_checkKt", str(directory)]
    proof = {"utc": datetime.now(timezone.utc).isoformat(), "source_sha256": hashes,
             "scope": "GitHub network, no installation or OBD", "runs": []}
    environment = os.environ.copy()
    if args.use_gh_auth:
        auth = subprocess.run(["gh", "auth", "token"], capture_output=True, encoding="utf-8", timeout=20)
        if auth.returncode or not auth.stdout.strip():
            parser.error("Local gh authentication unavailable")
        environment["OBD_UPDATE_TOKEN"] = auth.stdout.strip()
    success = True
    for label, command in [("compile", compile_command), ("download", run_command)]:
        try:
            result = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    encoding="utf-8", errors="replace", timeout=args.timeout, env=environment)
            output, code = result.stdout, result.returncode
        except subprocess.TimeoutExpired as error:
            output = (error.stdout or b"").decode("utf-8", errors="replace") if isinstance(error.stdout, bytes) else error.stdout or ""
            code = -1
        (directory / f"{label}.log").write_text(output, encoding="utf-8")
        proof["runs"].append({"step": label, "exit_code": code})
        print(f"{label}: {code}", flush=True)
        if code:
            print(output[-4000:], flush=True)
            success = False
            break
        if label == "download":
            proof["download"] = json.loads(output.strip().splitlines()[-1])
            print(json.dumps(proof["download"], ensure_ascii=False), flush=True)
    proof["inputs_unchanged"] = all(BASE.sha256(path) == hashes[BASE.relative(path)] for path in inputs)
    success &= proof["inputs_unchanged"]
    proof["success"] = success
    (directory / "validation.json").write_text(json.dumps(proof, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0 if success else 1


if __name__ == "__main__":
    raise SystemExit(main())
