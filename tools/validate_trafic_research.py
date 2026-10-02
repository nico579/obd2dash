"""Vérifie le registre documentaire Trafic, exclusivement sur les fichiers locaux."""

import hashlib
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REGISTER = ROOT / "recherches" / "trafic"


def main():
    sources = json.loads((REGISTER / "sources.json").read_text(encoding="utf-8"))
    errors = []
    seen_ids = set()
    verified = 0
    missing = []
    for source in sources["sources"]:
        if source["id"] in seen_ids:
            errors.append(f"Identifiant de source dupliqué : {source['id']}")
        seen_ids.add(source["id"])
        path = ROOT / source["artifact"]
        if not path.is_file():
            missing.append(source["artifact"])
            continue
        content = path.read_bytes()
        if len(content) != source["size"]:
            errors.append(f"Taille différente : {source['artifact']}")
        elif hashlib.sha256(content).hexdigest() != source["sha256"]:
            errors.append(f"SHA-256 différent : {source['artifact']}")
        else:
            verified += 1

    actions = [json.loads(line) for line in
               (REGISTER / "actions.jsonl").read_text(encoding="utf-8").splitlines()
               if line.strip()]
    seen_ids = set()
    seen_targets = {}
    for action in actions:
        action_id = action["id"]
        if action_id in seen_ids:
            errors.append(f"Identifiant d'action dupliqué : {action_id}")
        seen_ids.add(action_id)
        if action["vehicle_commands_sent"] != 0:
            errors.append(f"Action non documentaire : {action_id}")
        key = (action["kind"], " ".join(action["target"].split()).casefold())
        if key in seen_targets and not action.get("recheck_reason"):
            errors.append(f"Reprise sans justification : {action_id} / {seen_targets[key]}")
        seen_targets[key] = action_id
        for evidence in action["evidence"]:
            if not (REGISTER / evidence).is_file() and evidence not in {
                source["id"] for source in sources["sources"]
            }:
                errors.append(f"Preuve inconnue pour {action_id} : {evidence}")

    print(json.dumps({
        "sources": len(sources["sources"]),
        "local_hashes_verified": verified,
        "artifacts_missing": missing,
        "actions": len(actions),
        "errors": errors,
        "vehicle_commands_sent": 0,
    }, ensure_ascii=False, indent=2))
    # Les captures ne sont pas versionnées : leur absence doit être visible,
    # sans empêcher la lecture du registre depuis un autre clone.
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
