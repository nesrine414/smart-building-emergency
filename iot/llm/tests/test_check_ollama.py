"""Teste check_ollama.py contre un faux serveur Ollama (mêmes routes et mêmes formats JSON)."""
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

import check_ollama as C

MODELS = {
    "qwen2.5:7b": {"capabilities": ["completion", "tools"], "behaviour": "good"},
    "texte:1b": {"capabilities": ["completion", "tools"], "behaviour": "text"},
    "phi3:latest": {"capabilities": ["completion"], "behaviour": "good"},
    "chaine:3b": {"capabilities": ["completion", "tools"], "behaviour": "string-args"},
}


class FakeOllama(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, body):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/api/version":
            return self.reply(200, {"version": "0.13.0"})
        if self.path == "/api/tags":
            return self.reply(200, {"models": [{"name": n} for n in MODELS]})
        self.reply(404, {"error": "not found"})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        model = MODELS.get(body.get("model")) or MODELS.get(body.get("model", "") + ":latest")
        if self.path == "/api/show":
            return self.reply(200, {"capabilities": model["capabilities"],
                                    "details": {"parameter_size": "7.6B", "quantization_level": "Q4_K_M"}})
        if self.path == "/api/chat":
            if "tools" not in model["capabilities"]:
                return self.reply(400, {"error": f"{body['model']} does not support tools"})
            if body["messages"][-1]["role"] == "tool":
                return self.reply(200, {"message": {"role": "assistant", "content": "Fuite de gaz : quittez la salle."},
                                        "eval_count": 40, "eval_duration": 2_000_000_000})
            if model["behaviour"] == "text":
                return self.reply(200, {"message": {"role": "assistant", "content": "Je pense que tout va bien."}})
            arguments = {"roomId": "room-101"}
            if model["behaviour"] == "string-args":
                arguments = json.dumps(arguments)
            return self.reply(200, {"message": {"role": "assistant", "content": "", "tool_calls": [
                {"function": {"name": "getRecentReadings", "arguments": arguments}}]}})
        self.reply(404, {"error": "not found"})


@pytest.fixture(scope="module")
def server():
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), FakeOllama)
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    yield f"http://127.0.0.1:{httpd.server_address[1]}"
    httpd.shutdown()


def run(url, model, tmp_path, runs=2):
    return C.run(C.parse_args(["--url", url, "--model", model, "--runs", str(runs), "--report-dir", str(tmp_path)]))


def report(tmp_path):
    return json.loads(next(tmp_path.glob("ollama-*.json")).read_text(encoding="utf-8"))


def test_modele_compatible(server, tmp_path):
    assert run(server, "qwen2.5:7b", tmp_path) == 0
    r = report(tmp_path)
    assert r["ok"] and r["tool_call_success_rate"] == "2/2"
    assert r["recommended_agent_timeout_seconds"] >= 30
    assert r["example_tool_call"][0]["function"]["name"] == "getRecentReadings"
    assert r["tokens_per_second"] == 20.0


def test_modele_sans_outils_refuse(server, tmp_path):
    assert run(server, "phi3", tmp_path) == 1
    assert report(tmp_path)["checks"]["capacité tools"]["ok"] is False


def test_modele_qui_repond_sans_appeler_d_outil(server, tmp_path):
    assert run(server, "texte:1b", tmp_path) == 1
    assert "aucun tool_calls" in report(tmp_path)["checks"]["appel d'outil (tool_calls)"]["detail"]


def test_arguments_en_texte_acceptes(server, tmp_path):
    assert run(server, "chaine:3b", tmp_path) == 0


def test_modele_non_telecharge(server, tmp_path):
    assert run(server, "llama3.2:3b", tmp_path) == 1
    assert "ollama pull llama3.2:3b" in report(tmp_path)["checks"]["modèle téléchargé"]["detail"]


def test_ollama_arrete(tmp_path):
    assert run("http://127.0.0.1:9", "qwen2.5:7b", tmp_path) == 1
    assert report(tmp_path)["checks"]["serveur"]["ok"] is False
