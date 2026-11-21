"""Faux WildFly pour tester deploy-app.sh sans serveur d'applications (étape 5).

Il imite les deux choses dont dépend le script :
- le scanner du dossier standalone/deployments : marqueurs .dodeploy -> .isdeploying ->
  .deployed ou .failed, redéploiement quand le WAR est plus récent que .deployed, retrait quand
  le WAR disparaît ;
- Undertow : hôtes virtuels choisis par l'en-tête Host (www/localhost -> frontoffice,
  admin -> backoffice), retour vers index.html, découverte OIDC servie seulement si iam.war est
  déployé.
Un WAR est « cassé » s'il n'a pas WEB-INF/web.xml ou s'il contient une entrée BROKEN.
"""
from __future__ import annotations

import json
import threading
import time
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

DISCOVERY = "/iam/api/oauth2/.well-known/openid-configuration"


def war_version(path: Path) -> str | None:
    """Version lue dans version.txt, ou None si le WAR ne démarrerait pas."""
    try:
        with zipfile.ZipFile(path) as z:
            names = set(z.namelist())
            if "WEB-INF/web.xml" not in names or "BROKEN" in names:
                return None
            return z.read("version.txt").decode().strip() if "version.txt" in names else "?"
    except (zipfile.BadZipFile, OSError):
        return None


class FakeWildFly:
    def __init__(self, deployments: Path, web_root: Path, delay: float = 0.2, poll: float = 0.05):
        self.dir = deployments
        self.web_root = web_root
        self.delay = delay
        self.poll = poll
        self.deployed: dict[str, str] = {}   # nom du WAR -> version en service
        self.history: list[tuple[str, str]] = []
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self._httpd = ThreadingHTTPServer(("127.0.0.1", 0), self._handler())
        self.url = f"http://127.0.0.1:{self._httpd.server_address[1]}"

    # ------------------------------------------------------------ scanner
    def _marker(self, name: str, suffix: str) -> Path:
        return self.dir / f"{name}.{suffix}"

    def _deploy(self, war: Path) -> None:
        name = war.name
        self._marker(name, "dodeploy").unlink(missing_ok=True)
        self._marker(name, "isdeploying").touch()
        time.sleep(self.delay)
        version = war_version(war)
        self._marker(name, "isdeploying").unlink(missing_ok=True)
        with self._lock:
            if version is None:
                self.deployed.pop(name, None)
                self._marker(name, "deployed").unlink(missing_ok=True)
                self._marker(name, "failed").write_text(
                    f'{{"WFLYCTL0080: Failed services" => {{"jboss.deployment.unit.\\"{name}\\""}}}}\n')
                self.history.append((name, "failed"))
            else:
                self.deployed[name] = version
                self._marker(name, "failed").unlink(missing_ok=True)
                self._marker(name, "deployed").write_text(name)
                self.history.append((name, version))

    def _scan_once(self) -> None:
        for war in sorted(self.dir.glob("*.war")):
            name = war.name
            dodeploy = self._marker(name, "dodeploy")
            deployed = self._marker(name, "deployed")
            failed = self._marker(name, "failed")
            newer = deployed.exists() and war.stat().st_mtime_ns > deployed.stat().st_mtime_ns
            if dodeploy.exists() or (not deployed.exists() and not failed.exists()) or newer:
                self._deploy(war)
        for deployed in self.dir.glob("*.war.deployed"):
            name = deployed.name[: -len(".deployed")]
            if not (self.dir / name).exists():
                deployed.unlink(missing_ok=True)
                self._marker(name, "undeployed").touch()
                with self._lock:
                    self.deployed.pop(name, None)
                    self.history.append((name, "undeployed"))

    def _scan_loop(self) -> None:
        while not self._stop.is_set():
            self._scan_once()
            time.sleep(self.poll)

    # ------------------------------------------------------------ Undertow
    def _handler(self):
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):  # silencieux
                pass

            def _send(self, code: int, body: bytes, ctype: str = "text/html") -> None:
                self.send_response(code)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                host = (self.headers.get("Host") or "").split(":")[0]
                path = self.path.split("?")[0]
                with fake._lock:
                    deployed = dict(fake.deployed)
                if path.startswith("/iam/"):
                    if "iam.war" in deployed and path == DISCOVERY:
                        issuer = f"https://{host}/iam/api/oauth2"
                        body = json.dumps({"issuer": issuer, "version": deployed["iam.war"]})
                        return self._send(200, body.encode(), "application/json")
                    return self._send(404, b"Not Found")
                if path.startswith("/api/"):
                    if "api.war" in deployed:
                        return self._send(200, json.dumps({"version": deployed["api.war"]}).encode(),
                                          "application/json")
                    return self._send(404, b"Not Found")
                if host.startswith("admin."):
                    root = fake.web_root / "backoffice"
                elif host.startswith("www.") or host in ("localhost", "127.0.0.1"):
                    root = fake.web_root / "frontoffice"
                else:
                    return self._send(404, b"unknown host")
                target = (root / path.lstrip("/")).resolve()
                if not (target.is_file() and root.resolve() in target.parents):
                    target = root / "index.html"   # retour vers index.html (PWA)
                if target.is_file():
                    return self._send(200, target.read_bytes())
                return self._send(404, b"Not Found")

        return Handler

    # ------------------------------------------------------------ cycle de vie
    def start(self) -> "FakeWildFly":
        self.dir.mkdir(parents=True, exist_ok=True)
        threading.Thread(target=self._scan_loop, daemon=True).start()
        threading.Thread(target=self._httpd.serve_forever, daemon=True).start()
        return self

    def stop(self) -> None:
        self._stop.set()
        self._httpd.shutdown()
        self._httpd.server_close()
