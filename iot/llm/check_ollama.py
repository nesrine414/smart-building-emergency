"""
Vérifie que le serveur Ollama local est prêt pour l'agent IA de l'étape 3 (personne 2).

Contrôles, dans l'ordre :
  1. Ollama répond sur http://localhost:11434 (version) et le modèle est téléchargé.
  2. Le modèle déclare la capacité "tools" (sans elle, LangChain4j ne peut pas lui donner d'outils).
  3. Appel d'outil réel sur /api/chat avec les 3 outils du contrat 3 (getRecentReadings,
     getRoomEvacuationExit, getRiskScore) : la réponse doit contenir un tool_calls valide.
  4. Boucle complète comme le fera l'agent : on renvoie le résultat de l'outil, le modèle doit
     répondre en texte.
  5. Temps de réponse : premier appel (chargement du modèle) puis N appels "à chaud". Le rapport
     propose le délai d'attente à régler dans l'agent de la personne 2.

Bibliothèque standard seulement (pas de pip install). Rapport JSON dans iot/llm/reports/.

Utilisation :
    python iot/llm/check_ollama.py                       # qwen2.5:7b, 3 mesures
    python iot/llm/check_ollama.py --model llama3.2:3b --runs 5
"""

import argparse
import json
import os
import platform
import statistics
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_URL = os.environ.get("OLLAMA_URL", "http://localhost:11434")
DEFAULT_MODEL = os.environ.get("OLLAMA_MODEL", "qwen2.5:7b")

SYSTEM_PROMPT = ("Tu es l'assistant de sécurité d'un bâtiment intelligent. Pour répondre, appelle les "
                 "outils disponibles au lieu d'inventer des valeurs. Réponds en français, en 3 phrases maximum.")
QUESTION = "Quelles sont les dernières mesures de la salle room-101 ? Y a-t-il un danger ?"
EXPECTED_TOOL = "getRecentReadings"
EXPECTED_ROOM = "101"

# Les trois outils en lecture seule du contrat 3, au format "function calling" d'Ollama.
TOOLS = [
    {"type": "function", "function": {
        "name": "getRecentReadings",
        "description": "Dernière valeur de chaque capteur (gaz, fumée, température, humidité, présence) d'une salle.",
        "parameters": {"type": "object", "required": ["roomId"],
                       "properties": {"roomId": {"type": "string", "description": "identifiant de la salle, ex. room-101"}}}}},
    {"type": "function", "function": {
        "name": "getRoomEvacuationExit",
        "description": "Sortie de secours conseillée pour une salle.",
        "parameters": {"type": "object", "required": ["roomId"],
                       "properties": {"roomId": {"type": "string", "description": "identifiant de la salle, ex. room-101"}}}}},
    {"type": "function", "function": {
        "name": "getRiskScore",
        "description": "Score de risque d'incendie entre 0 et 1 calculé par le modèle ML pour une salle.",
        "parameters": {"type": "object", "required": ["roomId"],
                       "properties": {"roomId": {"type": "string", "description": "identifiant de la salle, ex. room-101"}}}}},
]

# Résultat simulé de l'outil, renvoyé au modèle pour la boucle complète.
TOOL_RESULT = ("Salle room-101 : GAS 1250 ppm (seuil 1000), SMOKE 20 ppm, TEMPERATURE 24.1 C, "
               "HUMIDITY 45 %, MOTION présence détectée il y a 12 s.")


class OllamaError(Exception):
    pass


def call(url, path, payload=None, timeout=180.0):
    """GET (payload None) ou POST JSON ; renvoie (réponse JSON, durée en secondes)."""
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(url.rstrip("/") + path, data=data,
                                     headers={"Content-Type": "application/json"},
                                     method="GET" if payload is None else "POST")
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")
        try:
            detail = json.loads(detail).get("error", detail)
        except ValueError:
            pass
        raise OllamaError(f"HTTP {error.code} sur {path} : {detail}") from None
    except (urllib.error.URLError, TimeoutError, OSError) as error:
        raise OllamaError(f"Ollama injoignable sur {url} ({getattr(error, 'reason', error)})") from None
    return json.loads(body), time.perf_counter() - started


def chat(args, messages):
    payload = {"model": args.model, "messages": messages, "tools": TOOLS, "stream": False,
               "keep_alive": args.keep_alive, "options": {"temperature": 0.2}}
    return call(args.url, "/api/chat", payload, timeout=args.timeout)


def tool_calls_of(response):
    return (response.get("message") or {}).get("tool_calls") or []


def check_tool_call(response):
    """Renvoie (ok, détail) : un appel à getRecentReadings pour la salle 101 est attendu."""
    calls = tool_calls_of(response)
    if not calls:
        content = (response.get("message") or {}).get("content", "")
        return False, f"aucun tool_calls (réponse texte : {content[:120]!r})"
    names = [c.get("function", {}).get("name") for c in calls]
    unknown = [n for n in names if n not in {t["function"]["name"] for t in TOOLS}]
    if unknown:
        return False, f"outil inventé par le modèle : {unknown}"
    for c in calls:
        function = c.get("function", {})
        arguments = function.get("arguments") or {}
        if isinstance(arguments, str):                    # certains modèles renvoient du JSON en texte
            try:
                arguments = json.loads(arguments)
            except ValueError:
                arguments = {}
        if function.get("name") == EXPECTED_TOOL and EXPECTED_ROOM in str(arguments.get("roomId", "")):
            return True, f"{EXPECTED_TOOL}({arguments}) ; outils appelés : {names}"
    return False, f"outils appelés {names}, mais pas {EXPECTED_TOOL} avec roomId room-101"


def full_loop(args):
    """Question -> tool_calls -> résultat de l'outil -> réponse finale, comme le fera l'agent."""
    messages = [{"role": "system", "content": SYSTEM_PROMPT}, {"role": "user", "content": QUESTION}]
    first, first_seconds = chat(args, messages)
    tool_ok, tool_detail = check_tool_call(first)
    result = {"tool_ok": tool_ok, "tool_detail": tool_detail, "answer": None, "ok": False,
              "seconds": first_seconds, "first": first, "final": {}}
    if not tool_ok:
        return result
    messages.append(first["message"])
    for c in tool_calls_of(first):
        messages.append({"role": "tool", "tool_name": c["function"]["name"], "content": TOOL_RESULT})
    final, final_seconds = chat(args, messages)
    answer = (final.get("message") or {}).get("content", "").strip()
    result.update({"answer": answer, "ok": bool(answer), "seconds": first_seconds + final_seconds, "final": final})
    return result


def tokens_per_second(response):
    count, duration = response.get("eval_count"), response.get("eval_duration")
    if count and duration:
        return round(count / (duration / 1e9), 1)
    return None


def run(args):
    report = {"date": datetime.now(timezone.utc).isoformat(timespec="seconds"), "url": args.url,
              "model": args.model, "machine": {"os": platform.platform(), "cpu": platform.processor()},
              "checks": {}}
    failures = 0

    def check(name, ok, detail):
        nonlocal failures
        report["checks"][name] = {"ok": ok, "detail": detail}
        print(f"  [{'OK  ' if ok else 'ÉCHEC'}] {name} : {detail}")
        if not ok:
            failures += 1
        return ok

    print(f"Ollama {args.url}, modèle {args.model}\n")
    try:
        version, _ = call(args.url, "/api/version", timeout=10)
        check("serveur", True, f"Ollama {version.get('version', '?')}")
        tags, _ = call(args.url, "/api/tags", timeout=10)
    except OllamaError as error:
        check("serveur", False, f"{error}. Lance l'application Ollama (ou 'ollama serve').")
        return finish(report, failures, args)

    installed = [m.get("name") for m in tags.get("models", [])]
    wanted = args.model if ":" in args.model else args.model + ":latest"
    if not check("modèle téléchargé", wanted in installed,
                 "présent" if wanted in installed else f"absent ; lance : ollama pull {args.model} "
                 f"(installés : {', '.join(installed) or 'aucun'})"):
        return finish(report, failures, args)

    show, _ = call(args.url, "/api/show", {"model": args.model}, timeout=30)
    capabilities = show.get("capabilities", [])
    details = show.get("details", {})
    report["model_details"] = {"capabilities": capabilities, "parameter_size": details.get("parameter_size"),
                               "quantization": details.get("quantization_level"), "family": details.get("family")}
    if not check("capacité tools", "tools" in capabilities,
                 f"capacités {capabilities}, {details.get('parameter_size', '?')} paramètres"
                 + ("" if "tools" in capabilities else " ; ce modèle ne sait pas appeler d'outils (ex. phi3) : "
                                                       "prends qwen2.5:7b ou llama3.2:3b")):
        return finish(report, failures, args)

    print("\n  Premier appel (chargement du modèle en mémoire, peut prendre une minute)...")
    try:
        cold = full_loop(args)
    except OllamaError as error:
        check("appel d'outil", False, str(error))
        return finish(report, failures, args)
    check("appel d'outil (tool_calls)", cold["tool_ok"], cold["tool_detail"])
    if cold["tool_ok"]:
        check("réponse finale après l'outil", bool(cold["answer"]), (cold["answer"] or "réponse vide")[:160])
    report["cold_start_seconds"] = round(cold["seconds"], 2)
    report["example_tool_call"] = tool_calls_of(cold.get("first", {}))
    report["example_answer"] = cold.get("answer")

    warm, successes, speeds = [], 0, []
    for i in range(args.runs):
        try:
            result = full_loop(args)
        except OllamaError as error:
            print(f"  essai {i + 1}/{args.runs} : erreur {error}")
            continue
        warm.append(result["seconds"])
        successes += 1 if result["ok"] else 0
        speed = tokens_per_second(result.get("final", {}))
        if speed:
            speeds.append(speed)
        print(f"  essai {i + 1}/{args.runs} : {result['seconds']:.1f} s "
              f"({'outil + réponse OK' if result['ok'] else result['tool_detail'] if not result['tool_ok'] else 'réponse vide'})")

    if warm:
        p95 = sorted(warm)[min(len(warm) - 1, int(round(0.95 * (len(warm) - 1))))]
        recommended = max(30, int(3 * p95 + 0.999))
        report["latency_seconds"] = {"runs": len(warm), "min": round(min(warm), 2),
                                     "median": round(statistics.median(warm), 2), "max": round(max(warm), 2),
                                     "p95": round(p95, 2)}
        report["tokens_per_second"] = round(statistics.median(speeds), 1) if speeds else None
        report["tool_call_success_rate"] = f"{successes}/{len(warm)}"
        report["recommended_agent_timeout_seconds"] = recommended
        check("fiabilité de la boucle outil + réponse", successes == len(warm), f"{successes}/{len(warm)} essais réussis")
        print(f"\n  Latence d'une question complète (2 appels au modèle) : médiane {statistics.median(warm):.1f} s, "
              f"max {max(warm):.1f} s ; premier appel {cold['seconds']:.1f} s")
        print(f"  Délai d'attente conseillé pour l'agent (personne 2) : {recommended} s, "
              "et charger le modèle avant la démo (premier appel lent).")
    return finish(report, failures, args)


def finish(report, failures, args):
    report["ok"] = failures == 0
    out_dir = Path(args.report_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    name = f"ollama-{args.model.replace(':', '-').replace('/', '-')}-{datetime.now():%Y%m%d-%H%M%S}.json"
    path = out_dir / name
    path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"\nRapport : {path}")
    print("Tout est prêt pour l'agent IA." if report["ok"] else f"{failures} contrôle(s) en échec.")
    return 0 if report["ok"] else 1


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description="Vérifie le serveur Ollama pour l'agent IA (étape 3)")
    parser.add_argument("--url", default=DEFAULT_URL, help=f"adresse d'Ollama (défaut : {DEFAULT_URL})")
    parser.add_argument("--model", default=DEFAULT_MODEL, help=f"modèle à tester (défaut : {DEFAULT_MODEL})")
    parser.add_argument("--runs", type=int, default=3, help="nombre de mesures à chaud (défaut : 3)")
    parser.add_argument("--timeout", type=float, default=300.0, help="délai maximum d'un appel en secondes")
    parser.add_argument("--keep-alive", default="30m", help="durée pendant laquelle Ollama garde le modèle chargé")
    parser.add_argument("--report-dir", default=str(HERE / "reports"), help="dossier du rapport JSON")
    return parser.parse_args(argv)


if __name__ == "__main__":
    sys.exit(run(parse_args()))
