"""deploy-app.sh de bout en bout : vrai Git, faux WildFly (scanner + Undertow), faux docker.

Scénarios du plan de l'étape 5 : déploiement normal, WAR cassé -> retour automatique, contrôle HTTP
en échec -> retour (dépôt et sbe-ml compris), sbe-ml unhealthy, --rollback, fichiers modifiés à la
main sur le serveur, nettoyage des anciennes versions.
Ces tests ont besoin de bash, git, curl et flock : ils tournent sous Linux (pipeline GitHub,
serveur) et sont ignorés sous Windows.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

import pytest

from fake_wildfly import FakeWildFly

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "deploy" / "server" / "deploy-app.sh"
DOMAIN = "sbe.test"

pytestmark = pytest.mark.skipif(
    sys.platform == "win32" or not all(shutil.which(t) for t in ("bash", "git", "curl", "flock")),
    reason="deploy-app.sh se teste sous Linux (bash, git, curl, flock)",
)

FAKE_DOCKER = """#!/bin/sh
echo "$*" >> "$DOCKER_LOG"
case "$*" in
  *"ps -q sbe-ml"*) echo cid-sbe-ml ;;
  inspect*) echo "${FAKE_ML_HEALTH:-healthy}" ;;
esac
exit 0
"""


def git(cwd: Path, *args: str) -> str:
    env = {**os.environ, "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t", "GIT_COMMITTER_NAME": "t",
           "GIT_COMMITTER_EMAIL": "t@t"}
    return subprocess.run(["git", *args], cwd=cwd, env=env, check=True, capture_output=True,
                          text=True).stdout.strip()


def make_war(path: Path, version: str, broken: bool = False) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as z:
        if not broken:
            z.writestr("WEB-INF/web.xml", "<web-app/>")
        z.writestr("version.txt", version)


def make_artifacts(base: Path, version: str, sha: str, broken_api: bool = False,
                   front_html: str | None = None) -> Path:
    out = base / f"art-{version}-{len(list(base.glob('art-*')))}"
    make_war(out / "wars" / "iam.war", version)
    make_war(out / "wars" / "api.war", version, broken=broken_api)
    for front in ("frontoffice", "backoffice"):
        (out / front / "assets").mkdir(parents=True)
        html = front_html if front_html is not None else f"<!doctype html><html><body>{front} {version}</body></html>"
        (out / front / "index.html").write_text(html)
        (out / front / "assets" / "app.js").write_text(f"// {version}")
    (out / "BUILD_INFO").write_text(f"sha={sha}\nref=main\nrun=1\n")
    return out


class Env:
    """Un serveur miniature dans tmp_path."""

    def __init__(self, tmp: Path):
        self.tmp = tmp
        self.sbe = tmp / "opt-sbe"
        self.repo = self.sbe / "smart-building-emergency"
        self.web = tmp / "var-opt-wildfly"
        self.deployments = tmp / "wildfly" / "standalone" / "deployments"
        self.bin = tmp / "bin"
        self.docker_log = tmp / "docker.log"
        self.summary = tmp / "summary.md"
        for d in (self.sbe, self.web / "frontoffice", self.web / "backoffice", self.bin):
            d.mkdir(parents=True, exist_ok=True)
        (self.bin / "docker").write_text(FAKE_DOCKER)
        (self.bin / "docker").chmod(0o755)
        (self.sbe / ".env").write_text(f"PUBLIC_DOMAIN={DOMAIN}\nMONGO_ROOT_PASSWORD=ne-pas-lire\n")
        # Dépôt « GitHub » (nu) + copie de travail d'une développeuse + clone du serveur
        self.origin = tmp / "origin.git"
        git(tmp, "init", "-q", "--bare", "-b", "main", str(self.origin))
        self.dev = tmp / "dev"
        git(tmp, "clone", "-q", str(self.origin), str(self.dev))
        git(self.dev, "checkout", "-q", "-b", "main")
        (self.dev / "deploy").mkdir()
        shutil.copy(ROOT / "deploy" / "compose.sh", self.dev / "deploy" / "compose.sh")
        (self.dev / "docker-compose.yml").write_text("services: {}\n")
        (self.dev / "iot" / "mlops").mkdir(parents=True)
        (self.dev / "iot" / "mlops" / "infer.py").write_text("V = 'A'\n")
        (self.dev / "README.md").write_text("A\n")
        self.sha = {"A": self.commit("A")}
        git(tmp, "clone", "-q", str(self.origin), str(self.repo))
        self.wf = FakeWildFly(self.deployments, self.web).start()

    def commit(self, label: str, path: str = "README.md", content: str | None = None) -> str:
        (self.dev / path).write_text(content if content is not None else f"{label}\n")
        git(self.dev, "add", "-A")
        git(self.dev, "commit", "-q", "-m", f"test: {label}")
        git(self.dev, "push", "-q", "origin", "main")
        sha = git(self.dev, "rev-parse", "HEAD")
        self.sha = {**getattr(self, "sha", {}), label: sha}
        return sha

    def run(self, *args: str, **extra: str) -> subprocess.CompletedProcess:
        env = {
            **os.environ,
            "PATH": f"{self.bin}{os.pathsep}{os.environ['PATH']}",
            "NO_PROXY": "127.0.0.1,localhost", "no_proxy": "127.0.0.1,localhost",
            "SBE_ROOT": str(self.sbe), "WEB_ROOT": str(self.web), "DEPLOYMENTS_DIR": str(self.deployments),
            "WILDFLY_HTTP": self.wf.url, "POLL": "0.1", "DEPLOY_TIMEOUT": "15", "SMOKE_TIMEOUT": "3",
            "SBE_ML_TIMEOUT": "3", "DOCKER_LOG": str(self.docker_log),
            "GITHUB_STEP_SUMMARY": str(self.summary),
            **extra,
        }
        env.pop("GITHUB_ACTIONS", None)
        return subprocess.run(["bash", str(SCRIPT), *args], env=env, capture_output=True, text=True,
                              timeout=120)

    def deploy(self, label: str, **kw) -> subprocess.CompletedProcess:
        extra = kw.pop("env", {})
        art = make_artifacts(self.tmp, label, self.sha[label], **kw)
        return self.run("--sha", self.sha[label], str(art), **extra)

    # ------------------------------------------------------------ constats
    def head(self) -> str:
        return git(self.repo, "rev-parse", "HEAD")

    def front(self, name: str = "frontoffice") -> str:
        return (self.web / name / "index.html").read_text()

    def releases(self) -> list[Path]:
        return sorted(p for p in (self.sbe / "releases").iterdir() if p.is_dir())

    def current(self) -> str:
        return (self.sbe / "releases" / "CURRENT").read_text().strip()

    def status(self, rel: Path) -> str:
        return (rel / "status").read_text().strip()

    def compose_builds(self) -> int:
        if not self.docker_log.exists():
            return 0
        return sum("up -d --build" in line for line in self.docker_log.read_text().splitlines())


@pytest.fixture
def env(tmp_path):
    e = Env(tmp_path)
    yield e
    e.wf.stop()


def ok(proc: subprocess.CompletedProcess) -> None:
    assert proc.returncode == 0, proc.stdout + proc.stderr


def test_premier_deploiement_puis_mise_a_jour(env):
    ok(env.deploy("A"))
    assert env.wf.deployed == {"iam.war": "A", "api.war": "A"}
    assert "frontoffice A" in env.front() and "backoffice A" in env.front("backoffice")
    assert env.head() == env.sha["A"]
    assert env.compose_builds() == 0          # rien de sbe-ml n'a changé
    first = env.releases()[0]
    assert env.status(first) == "ok" and env.current() == first.name

    env.commit("B", "iot/mlops/infer.py", "V = 'B'\n")
    proc = env.deploy("B")
    ok(proc)
    assert env.wf.deployed == {"iam.war": "B", "api.war": "B"}
    assert "frontoffice B" in env.front()
    assert env.head() == env.sha["B"]
    assert env.compose_builds() == 1          # iot/mlops a changé : sbe-ml reconstruit
    assert env.current() == env.releases()[-1].name
    # L'IAM est déployé avant l'API, et rien ne traîne dans le dossier du scanner
    assert [n for n, _ in env.wf.history[-2:]] == ["iam.war", "api.war"]
    assert not list(env.deployments.glob("*.uploading")) and not list(env.web.glob("*.new"))
    # Le serveur ne lit que PUBLIC_DOMAIN dans .env
    assert "ne-pas-lire" not in proc.stdout
    assert "réussi" in env.summary.read_text()


def test_war_casse_retour_automatique(env):
    ok(env.deploy("A"))
    good = env.current()
    env.commit("C")
    proc = env.deploy("C", broken_api=True)
    assert proc.returncode == 1
    assert "WildFly refuse api.war" in proc.stdout and "Retour terminé" in proc.stdout
    # L'API et l'IAM sont revenus à A, comme les PWA et le dépôt
    assert env.wf.deployed == {"iam.war": "A", "api.war": "A"}
    assert "frontoffice A" in env.front()
    assert env.head() == env.sha["A"]
    assert env.current() == good
    assert env.status(env.releases()[-1]) == "echec-retour-fait"
    assert "échec" in env.summary.read_text()


def test_controle_http_en_echec_retour_complet(env):
    ok(env.deploy("A"))
    env.commit("B", "iot/mlops/infer.py", "V = 'B'\n")
    proc = env.deploy("B", front_html="page blanche sans balise")
    assert proc.returncode == 1
    assert "ÉCHEC" in proc.stdout and f"www.{DOMAIN}" in proc.stdout
    assert env.head() == env.sha["A"]
    assert env.compose_builds() == 2          # reconstruit pour B, puis de nouveau pour A
    assert "frontoffice A" in env.front()
    assert env.wf.deployed == {"iam.war": "A", "api.war": "A"}


def test_sbe_ml_unhealthy_retour(env):
    ok(env.deploy("A"))
    env.commit("B")
    proc = env.deploy("B", env={"FAKE_ML_HEALTH": "unhealthy"})
    assert proc.returncode == 1 and "sbe-ml unhealthy" in proc.stdout
    assert env.head() == env.sha["A"]


def test_commande_rollback(env):
    ok(env.deploy("A"))
    env.commit("B")
    ok(env.deploy("B"))
    proc = env.run("--rollback")
    ok(proc)
    assert env.head() == env.sha["A"]
    assert env.wf.deployed == {"iam.war": "A", "api.war": "A"} and "frontoffice A" in env.front()
    latest = env.releases()[-1]
    assert env.current() == latest.name and (latest / "rollback-of").exists()
    # Un second retour ne revient pas vers B : il n'y a rien de plus ancien que A
    proc = env.run("--rollback")
    assert proc.returncode == 1 and "pas de version réussie plus ancienne" in proc.stdout
    listing = env.run("--list").stdout
    assert "<- en ligne" in listing and listing.count(" ok") == 3


def test_fichier_modifie_a_la_main_sur_le_serveur(env):
    ok(env.deploy("A"))
    (env.repo / "README.md").write_text("modifié sur le serveur\n")
    env.commit("B")
    proc = env.deploy("B")
    assert proc.returncode == 1 and "modifiés à la main" in proc.stdout
    assert (env.repo / "README.md").read_text() == "modifié sur le serveur\n"   # rien d'écrasé
    assert env.head() == env.sha["A"] and "frontoffice A" in env.front()


def test_refuse_sans_domaine_avant_toute_modification(env):
    (env.sbe / ".env").write_text("PUBLIC_DOMAIN=\n")
    proc = env.deploy("A")
    assert proc.returncode == 1 and "PUBLIC_DOMAIN vide" in proc.stdout
    assert not (env.sbe / "releases").exists()
    assert env.wf.deployed == {}


def test_garde_les_dernieres_versions(env):
    for label in ("A", "B", "C"):
        if label != "A":
            env.commit(label)
        ok(env.deploy(label, env={"KEEP_RELEASES": "2"}))
    names = [p.name for p in env.releases()]
    assert len(names) == 2 and env.current() == names[-1]
