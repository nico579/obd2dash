"""Compile current production Kotlin and run offline ViewModel regression cases.

Only existing Gradle-cache JARs are used; this runner never invokes Gradle or ADB.
All generated files belong to a fresh, isolated directory under captures/.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SOURCE_DIR = ROOT / "app/src/main/java/com/nico/obd2dash"
OUTPUT_ROOT = ROOT / "captures/audit_2026-09-13/recording_reconnect"
SOURCE_NAMES = (
    "ObdViewModel", "Elm327Client", "PidCatalog", "DtcDictionary", "VinDecoder",
    "CanHeaderReassembly", "HeaderlessObdResponse", "DtcPayloadDecoder", "VinPayloadDecoder",
    "PollingHealth", "StandardObdAvailability",
    "DiagnosticReadSequence", "BleSerialTransport",
    "DashboardGaugeOrder",
)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def relative(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


def java_binary() -> Path:
    executable = "java.exe" if os.name == "nt" else "java"
    if os.environ.get("JAVA_HOME"):
        candidate = Path(os.environ["JAVA_HOME"]) / "bin" / executable
    else:
        candidate = Path.home() / ".jdks/jbr-21.0.11/bin" / executable
    if not candidate.is_file():
        raise RuntimeError(f"JDK unavailable at {candidate}; set JAVA_HOME to an existing JDK")
    return candidate


def cached_jar(group: str, name: str, version: str) -> Path:
    gradle_home = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle")))
    cache = gradle_home / "caches/modules-2/files-2.1"
    matches = sorted(cache.glob(f"{group}/{name}/{version}/*/{name}-{version}.jar"))
    if len(matches) != 1:
        raise RuntimeError(f"Expected one cached {name}:{version} JAR in {cache}, found {len(matches)}; no download attempted")
    return matches[0]


def kotlin_string(value: str) -> str:
    # JSON and Kotlin share these string escapes; Kotlin also interpolates '$'.
    return json.dumps(value, ensure_ascii=False).replace("$", "\\$")


def android_text(element: ET.Element) -> str:
    value = "".join(element.itertext())
    escapes = {"n": "\n", "t": "\t", "r": "\r", "'": "'", '"': '"', "\\": "\\", "@": "@", "?": "?"}
    value = re.sub(r"\\u([0-9a-fA-F]{4})", lambda match: chr(int(match[1], 16)), value)
    return re.sub(r"\\(.)", lambda match: escapes.get(match[1], match[0]), value)


def generate_resources(sources: list[Path], destination: Path) -> list[Path]:
    """Generate only referenced IDs, verifying each exists in current base XML.

    This is a test double, not Android's aapt or locale/resource resolution.
    The base strings are French; quantity one covers 0 and 1, as in French.
    """
    names: dict[str, set[str]] = {"string": set(), "plurals": set()}
    for source in sources:
        for kind, name in re.findall(r"\bR\.(string|plurals)\.([A-Za-z_]\w*)", source.read_text(encoding="utf-8")):
            names[kind].add(name)
    values: dict[tuple[str, str], str | dict[str, str]] = {}
    xml_files = sorted((ROOT / "app/src/main/res/values").glob("*.xml"))
    for xml_file in xml_files:
        for element in ET.parse(xml_file).getroot():
            kind, name = element.tag, element.get("name", "")
            if kind in names and name in names[kind]:
                values[kind, name] = (
                    {item.attrib["quantity"]: android_text(item) for item in element}
                    if kind == "plurals" else android_text(element)
                )
    references = [(kind, name) for kind in names for name in sorted(names[kind])]
    missing = [f"R.{kind}.{name}" for kind, name in references if (kind, name) not in values]
    if missing:
        raise RuntimeError("Missing base resources: " + ", ".join(missing))
    identifiers = {reference: index for index, reference in enumerate(references, 1)}
    lines = ["// Generated from current source references and base resources. Do not edit.",
             "package com.nico.obd2dash", "", "object R {"]
    for kind in names:
        lines.append(f"    object {kind} {{")
        for name in sorted(names[kind]):
            lines.append(f"        const val {name}: Int = {identifiers[kind, name]}")
        lines.append("    }")
    lines += ["}", "", "internal object AuditResourceValues {",
              "    private val strings = mapOf<Int, String>("]
    for name in sorted(names["string"]):
        lines.append(f"        R.string.{name} to {kotlin_string(str(values['string', name]))},")
    lines += ["    )", "    private val plurals = mapOf<Int, Map<String, String>>("]
    for name in sorted(names["plurals"]):
        items = values["plurals", name]
        assert isinstance(items, dict)
        entries = ", ".join(f"{kotlin_string(key)} to {kotlin_string(value)}" for key, value in items.items())
        lines.append(f"        R.plurals.{name} to mapOf({entries}),")
    lines += [
        "    )",
        "    fun string(id: Int, vararg args: Any): String =",
        "        format(strings.getValue(id), args)",
        "    fun plural(id: Int, quantity: Int, vararg args: Any): String {",
        "        val variants = plurals.getValue(id)",
        '        val key = if (quantity == 0 || quantity == 1) "one" else "other"',
        '        return format(variants[key] ?: variants.getValue("other"), args)',
        "    }",
        "    private fun format(value: String, args: Array<out Any>): String =",
        "        if (args.isEmpty()) value else String.format(java.util.Locale.ROOT, value, *args)",
        "}",
    ]
    destination.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return xml_files


def run_process(command: list[str], log: Path, timeout: float) -> int:
    try:
        result = subprocess.run(command, cwd=ROOT, capture_output=True, encoding="utf-8", errors="replace", timeout=timeout)
    except subprocess.TimeoutExpired as error:
        def decode(value: str | bytes | None) -> str:
            return value.decode("utf-8", errors="replace") if isinstance(value, bytes) else (value or "")
        output = decode(error.stdout) + decode(error.stderr) + f"\nTIMEOUT after {timeout:g} seconds\n"
        log.write_text(output, encoding="utf-8")
        print(output, end="", flush=True)
        return 124
    output = result.stdout + result.stderr
    log.write_text(output, encoding="utf-8")
    print(output, end="", flush=True)
    return result.returncode


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case", help="Run one named scenario instead of the full suite")
    parser.add_argument("--compile-only", action="store_true", help="Compile without running the Kotlin cases")
    parser.add_argument("--timeout", type=float, default=240, help="Kotlin execution timeout in seconds (default: 240)")
    parser.add_argument("--compile-timeout", type=float, default=120, help="Kotlin compilation timeout in seconds (default: 120)")
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    if args.compile_timeout <= 0:
        parser.error("--compile-timeout must be positive")
    sources = [SOURCE_DIR / f"{name}.kt" for name in SOURCE_NAMES]
    harness = [*sorted(HERE.glob("stubs_*.kt")), HERE / "fake_elm.kt", HERE / "recording_cases.kt"]
    for source in [*sources, *harness]:
        if not source.is_file():
            parser.error(f"Required source does not exist: {source}")
    java = java_binary()
    stdlib = cached_jar("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.24")
    coroutines = cached_jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.1")
    annotations = cached_jar("org.jetbrains", "annotations", "13.0")
    compiler_jars = [
        cached_jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "1.9.24"), stdlib,
        cached_jar("org.jetbrains.kotlin", "kotlin-script-runtime", "1.9.24"),
        cached_jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
        cached_jar("org.jetbrains.intellij.deps", "trove4j", "1.0.20200330"), annotations,
    ]
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    run_dir = Path(tempfile.mkdtemp(prefix=datetime.now().strftime("run_%Y%m%d_%H%M%S_"), dir=OUTPUT_ROOT))
    print(f"Artifacts: {run_dir}", flush=True)
    generated = run_dir / "generated_resources.kt"
    resource_files = generate_resources(sources, generated)
    inputs = [*sources, *harness, *resource_files, Path(__file__).resolve()]
    original_hashes = {relative(path): sha256(path) for path in inputs}
    classes = run_dir / "classes"
    classes.mkdir()
    dependency_cp = os.pathsep.join(map(str, [stdlib, coroutines, annotations]))
    compile_command = [
        str(java), "-Xmx768m", "-Dfile.encoding=UTF-8", "-cp", os.pathsep.join(map(str, compiler_jars)),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
        "-cp", dependency_cp, "-d", str(classes), *map(str, [*sources, *harness, generated]),
    ]
    run_command = [
        str(java), "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8",
        "-cp", os.pathsep.join(map(str, [classes, stdlib, coroutines])),
        "com.nico.obd2dash.Recording_casesKt", str(run_dir / "cases"),
    ]
    if args.case:
        run_command.append(args.case)
    provenance = {
        "started_at_utc": datetime.now(timezone.utc).isoformat(),
        "method": "Unmodified production Kotlin sources; Android/history/log/service doubles; single UI dispatcher; real Elm327Client sockets and coroutines; fake ELM on loopback",
        "case": args.case,
        "input_sha256": original_hashes,
        "generated_resources_sha256": sha256(generated),
        "java": str(java),
        "dependency_sha256": {str(path): sha256(path) for path in dict.fromkeys([*compiler_jars, coroutines])},
        "compile_command": compile_command,
        "run_command": run_command,
        "compile_exit": None,
        "run_exit": None,
    }
    provenance_path = run_dir / "provenance.json"

    def save_provenance() -> None:
        provenance_path.write_text(json.dumps(provenance, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    save_provenance()
    try:
        provenance["compile_exit"] = run_process(compile_command, run_dir / "compile.txt", args.compile_timeout)
        code = int(provenance["compile_exit"])
        if code == 0 and not args.compile_only:
            provenance["run_exit"] = run_process(run_command, run_dir / "results.txt", args.timeout)
            code = int(provenance["run_exit"])
        changed = [relative(path) for path in inputs if not path.is_file() or sha256(path) != original_hashes[relative(path)]]
        provenance["inputs_unchanged"] = not changed
        provenance["changed_inputs"] = changed
        if changed:
            print("INVALIDATED: inputs changed during compilation/execution: " + ", ".join(changed), flush=True)
            code = code or 2
        return code
    finally:
        provenance["finished_at_utc"] = datetime.now(timezone.utc).isoformat()
        save_provenance()


if __name__ == "__main__":
    raise SystemExit(main())
