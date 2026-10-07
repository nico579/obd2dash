"""Assert the v0.16 audit fixes against unmodified production sources, on loopback.

Each selected probe must exit zero; acceptance failures yield a nonzero status.
The original v0.15 observations and failing contracts remain in archived captures
and the audit report. No Gradle, ADB, downloads, radio or vehicle address is used.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
BASE_PATH = ROOT / 'tools/offline_viewmodel/run.py'
sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location('offline_viewmodel_runner', BASE_PATH)
assert SPEC is not None and SPEC.loader is not None
BASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASE)
CASES = {
    'parser': 'Diagnostic_parser_probeKt',
    'transport': 'TransportAuditProbe',
    'vm': 'VmIdentityFreshnessProbe',
    'smoke': 'Smoke_recording_ownershipKt',
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument('--case', choices=CASES)
    selection.add_argument('--include-smoke', action='store_true',
                           help='Run all four probes with one shared compilation')
    parser.add_argument('--compile-timeout', type=float, default=120)
    parser.add_argument('--timeout', type=float, default=90)
    args = parser.parse_args()
    if args.compile_timeout <= 0 or args.timeout <= 0:
        parser.error('Timeouts must be positive')
    sources = [BASE.SOURCE_DIR / f'{name}.kt' for name in BASE.SOURCE_NAMES]
    sources.append(BASE.SOURCE_DIR / 'profiles/ManufacturerProfiles.kt')
    harness = [*sorted(BASE.HERE.glob('stubs_*.kt')), BASE.HERE / 'fake_elm.kt',
               *sorted(HERE.glob('*_probe.kt')), HERE / 'smoke_recording_ownership.kt']
    for source in [*sources, *harness]:
        if not source.is_file():
            parser.error(f'Required source missing: {source}')
    output_root = ROOT / 'captures/audit_code_20261007/runs'
    output_root.mkdir(parents=True, exist_ok=True)
    run_dir = Path(tempfile.mkdtemp(prefix=datetime.now().strftime('run_%Y%m%d_%H%M%S_'), dir=output_root))
    print(f'Artifacts: {run_dir}', flush=True)
    generated = run_dir / 'generated_resources.kt'
    resource_files = BASE.generate_resources(sources, generated)
    inputs = [*sources, *harness, *resource_files, BASE_PATH, Path(__file__).resolve()]
    original_hashes = {BASE.relative(path): BASE.sha256(path) for path in inputs}
    classes = run_dir / 'classes'
    classes.mkdir()
    java = BASE.java_binary()
    stdlib = BASE.cached_jar('org.jetbrains.kotlin', 'kotlin-stdlib', '1.9.24')
    coroutines = BASE.cached_jar('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.1')
    annotations = BASE.cached_jar('org.jetbrains', 'annotations', '13.0')
    compiler_jars = [
        BASE.cached_jar('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '1.9.24'), stdlib,
        BASE.cached_jar('org.jetbrains.kotlin', 'kotlin-script-runtime', '1.9.24'),
        BASE.cached_jar('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
        BASE.cached_jar('org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330'), annotations,
    ]
    compile_command = [str(java), '-Xmx768m', '-Dfile.encoding=UTF-8', '-cp',
                       os.pathsep.join(map(str, compiler_jars)),
                       'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                       '-jvm-target', '17', '-cp', os.pathsep.join(map(str, [stdlib, coroutines, annotations])),
                       '-d', str(classes), *map(str, [*sources, *harness, generated])]
    selected = [args.case] if args.case else ['parser', 'transport', 'vm']
    if args.include_smoke:
        selected.append('smoke')
    commands = {
        case: [str(java), '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
               '-cp', os.pathsep.join(map(str, [classes, stdlib, coroutines])),
               'com.nico.obd2dash.' + CASES[case], str(run_dir / case)]
        for case in selected
    }
    provenance = {
        'started_at_utc': datetime.now(timezone.utc).isoformat(),
        'method': 'Unmodified production Kotlin; Android/history/log/service doubles; real client and coroutines; loopback TCP only; no radio or vehicle',
        'input_sha256': original_hashes,
        'generated_resources_sha256': BASE.sha256(generated),
        'dependency_sha256': {str(path): BASE.sha256(path) for path in dict.fromkeys([*compiler_jars, coroutines])},
        'compile_command': compile_command, 'run_commands': commands,
        'compile_exit': None, 'run_exits': {},
    }

    def save() -> None:
        (run_dir / 'provenance.json').write_text(json.dumps(provenance, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    save()
    try:
        provenance['compile_exit'] = BASE.run_process(compile_command, run_dir / 'compile.txt', args.compile_timeout)
        code = int(provenance['compile_exit'])
        if code == 0:
            for case, command in commands.items():
                print(f'Probe: {case}', flush=True)
                result = BASE.run_process(command, run_dir / f'{case}.txt', args.timeout)
                provenance['run_exits'][case] = result
                code = code or result
                save()
        changed = [BASE.relative(path) for path in inputs
                   if not path.is_file() or BASE.sha256(path) != original_hashes[BASE.relative(path)]]
        provenance['inputs_unchanged'] = not changed
        provenance['changed_inputs'] = changed
        if changed:
            print('INVALIDATED: inputs changed: ' + ', '.join(changed), flush=True)
            code = code or 2
        return code
    finally:
        provenance['finished_at_utc'] = datetime.now(timezone.utc).isoformat()
        save()


if __name__ == '__main__':
    raise SystemExit(main())
