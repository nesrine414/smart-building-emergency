"""
Audit de sécurité TLS du serveur en ligne (étape 6, personne 1), à lancer depuis un PC de l'équipe.

    python deploy/audit_securite.py sbe-team.me
    python deploy/audit_securite.py sbe-team.me --ip 13.62.97.65     # avant que le DNS soit propagé

Il vérifie, en une commande, les deux points que l'encadrant contrôle dans un audit SSL,
plus ce qui va avec :

1. DNS : un enregistrement CAA autorise letsencrypt.org à émettre les certificats du domaine.
2. Pour chaque nom web (www, admin, api, iam), port 443 :
   - certificat valide, au bon nom, encore valable au moins 14 jours, TLS 1.2 ou 1.3 ;
   - TLS-CA : le serveur envoie la chaîne complète (son certificat ET la CA intermédiaire de
     Let's Encrypt, c'est-à-dire fullchain.pem). Le contrôle se fait avec les seules CA racines
     (paquet certifi) : sans la CA intermédiaire, la vérification échoue ;
   - HSTS : en-tête Strict-Transport-Security, une seule fois, avec max-age d'au moins 6 mois (seuil de la
     note A+ de SSL Labs) ;
   - le port 80 redirige vers https:// sur le même nom.
3. Broker MQTT (mqtt.<domaine>:8883) : certificat valide pour la CA du projet (ca.pem), au bon nom,
   chaîne complète, TLS 1.2 ou 1.3 ; le port 1883 (MQTT en clair) est fermé.

Un rapport Markdown est écrit dans deploy/reports/ (à joindre au rapport ou à montrer au jury).
Bibliothèque standard ; « pip install certifi » rend le contrôle de chaîne exact sous Windows.
Code de sortie 0 si tout est conforme, 1 sinon.
"""

from __future__ import annotations

import argparse
import datetime as dt
import http.client
import json
import socket
import ssl
import sys
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path

WEB_NAMES = ("www", "admin", "api", "iam")
HSTS_MIN = 15_768_000          # 6 mois (182,5 jours) : minimum de SSL Labs pour la note A+
MIN_DAYS = 14                  # Let's Encrypt renouvelle 30 jours avant l'échéance
DOH_URL = "https://dns.google/resolve"
ROOT = Path(__file__).resolve().parents[1]
GOOD_VERSIONS = ("TLSv1.2", "TLSv1.3")


@dataclass
class Result:
    section: str
    target: str
    check: str
    status: str        # OK, ÉCHEC, ATTENTION, INFO
    detail: str = ""


# ---------------------------------------------------------------------------- TLS
@dataclass
class Probe:
    ok: bool
    error: str = ""
    version: str = ""
    cert: dict | None = None
    chain_len: int | None = None   # certificats envoyés par le serveur (Python 3.13 et plus)


def tls_probe(host: str, port: int, context: ssl.SSLContext, connect_ip: str | None, timeout: float) -> Probe:
    """Poignée de main TLS vers connect_ip (ou host), en présentant host (SNI et vérification du nom)."""
    try:
        with socket.create_connection((connect_ip or host, port), timeout=timeout) as raw:
            with context.wrap_socket(raw, server_hostname=host) as tls:
                chain = None
                if hasattr(tls, "get_unverified_chain"):
                    try:
                        chain = len(tls.get_unverified_chain() or [])
                    except (ssl.SSLError, ValueError):
                        chain = None
                return Probe(True, version=tls.version() or "", cert=tls.getpeercert(), chain_len=chain)
    except ssl.SSLCertVerificationError as exc:
        return Probe(False, error=f"certificat refusé : {exc.verify_message or exc}")
    except (ssl.SSLError, OSError) as exc:
        return Probe(False, error=f"{type(exc).__name__} : {exc}")


def days_left(cert: dict) -> int:
    end = dt.datetime.fromtimestamp(ssl.cert_time_to_seconds(cert["notAfter"]), dt.timezone.utc)
    return (end - dt.datetime.now(dt.timezone.utc)).days


def issuer_name(cert: dict) -> str:
    parts = dict(item[0] for item in cert.get("issuer", ()))
    return " / ".join(v for k, v in parts.items() if k in ("organizationName", "commonName"))


def roots_only_context(web_cafile: str | None) -> tuple[ssl.SSLContext, str]:
    """Contexte qui ne connaît que des CA racines : une CA intermédiaire non envoyée y est fatale."""
    if web_cafile:
        return ssl.create_default_context(cafile=web_cafile), f"CA fournie ({web_cafile})"
    try:
        import certifi  # type: ignore
        return ssl.create_default_context(cafile=certifi.where()), "racines certifi"
    except ImportError:
        return ssl.create_default_context(), "magasin du système (pip install certifi pour un contrôle exact)"


# ---------------------------------------------------------------------------- HTTP
class _HTTPSConnection(http.client.HTTPSConnection):
    """HTTPS vers une IP choisie, avec le nom du site pour le SNI et l'en-tête Host."""

    def __init__(self, host, port, context, timeout, connect_ip=None):
        super().__init__(host, port, context=context, timeout=timeout)
        self._connect_ip = connect_ip

    def connect(self):
        sock = socket.create_connection((self._connect_ip or self.host, self.port), self.timeout)
        self.sock = self._context.wrap_socket(sock, server_hostname=self.host)


def http_get(host: str, port: int, *, tls: bool, context: ssl.SSLContext | None, connect_ip: str | None,
             timeout: float) -> tuple[int, dict[str, str]]:
    if tls:
        conn = _HTTPSConnection(host, port, context, timeout, connect_ip)
    else:
        conn = http.client.HTTPConnection(connect_ip or host, port, timeout=timeout)
    try:
        headers = {"Host": host if port in (80, 443) else f"{host}:{port}", "User-Agent": "sbe-audit/1"}
        conn.request("GET", "/", headers=headers)
        resp = conn.getresponse()
        resp.read(65536)
        merged: dict[str, str] = {}
        for name, value in resp.getheaders():       # un en-tête répété est gardé, séparé par un saut de ligne
            name = name.lower()
            merged[name] = f"{merged[name]}\n{value}" if name in merged else value
        return resp.status, merged
    finally:
        conn.close()


def parse_hsts(value: str) -> tuple[int | None, bool]:
    max_age, subdomains = None, False
    for part in value.split(";"):
        part = part.strip()
        if part.lower().startswith("max-age="):
            try:
                max_age = int(part.split("=", 1)[1].strip().strip('"'))
            except ValueError:
                max_age = None
        elif part.lower() == "includesubdomains":
            subdomains = True
    return max_age, subdomains


# ---------------------------------------------------------------------------- DNS (CAA)
def caa_lookup_doh(domain: str, timeout: float, doh_url: str = DOH_URL) -> list[str]:
    """Enregistrements CAA du domaine, par DNS sur HTTPS (aucune bibliothèque DNS à installer)."""
    url = f"{doh_url}?{urllib.parse.urlencode({'name': domain, 'type': 'CAA'})}"
    req = urllib.request.Request(url, headers={"Accept": "application/dns-json"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = json.load(resp)
    return [a.get("data", "") for a in data.get("Answer", []) if a.get("type") == 257]


# ---------------------------------------------------------------------------- audit
def audit(domain: str, *, connect_ip: str | None = None, cafile: str | None = None, web_cafile: str | None = None,
          https_port: int = 443, http_port: int = 80, mqtt_port: int = 8883, mqtt_plain_port: int = 1883,
          timeout: float = 8.0, caa_lookup=caa_lookup_doh, web_names=WEB_NAMES) -> list[Result]:
    results: list[Result] = []
    add = lambda *a: results.append(Result(*a))  # noqa: E731

    # 1. CAA
    try:
        records = caa_lookup(domain, timeout)
        issuers = [r for r in records if " issue " in f" {r} " or " issuewild " in f" {r} "]
        if any("letsencrypt.org" in r for r in issuers):
            add("DNS", domain, "CAA autorise Let's Encrypt", "OK", " ; ".join(records))
        elif records:
            add("DNS", domain, "CAA autorise Let's Encrypt", "ÉCHEC", "letsencrypt.org absent : " + " ; ".join(records))
        else:
            add("DNS", domain, "CAA autorise Let's Encrypt", "ÉCHEC",
                'aucun enregistrement CAA (Namecheap : CAA Record, host @, 0 issue "letsencrypt.org")')
    except Exception as exc:  # réseau, DNS sur HTTPS bloqué...
        add("DNS", domain, "CAA autorise Let's Encrypt", "ATTENTION", f"non vérifié ({exc})")

    # 2. Web
    strict_ctx, strict_label = roots_only_context(web_cafile)
    for name in web_names:
        host = f"{name}.{domain}"
        probe = tls_probe(host, https_port, strict_ctx, connect_ip, timeout)
        if not probe.ok:
            hint = ""
            if "local issuer" in probe.error:
                hint = " → chaîne incomplète : Nginx doit utiliser fullchain.pem (pas cert.pem)"
            add("Web", host, "Certificat et chaîne complète (TLS-CA)", "ÉCHEC", probe.error + hint)
        else:
            chain = f", {probe.chain_len} certificats envoyés" if probe.chain_len else ""
            if probe.chain_len == 1 and not web_cafile:
                add("Web", host, "Certificat et chaîne complète (TLS-CA)", "ÉCHEC",
                    "le serveur n'envoie que son certificat : utiliser fullchain.pem")
            else:
                add("Web", host, "Certificat et chaîne complète (TLS-CA)", "OK",
                    f"émis par {issuer_name(probe.cert)}{chain} ; vérifié avec : {strict_label}")
            left = days_left(probe.cert)
            add("Web", host, f"Validité (au moins {MIN_DAYS} jours)", "OK" if left >= MIN_DAYS else "ÉCHEC",
                f"{left} jours restants")
            add("Web", host, "Version TLS", "OK" if probe.version in GOOD_VERSIONS else "ÉCHEC", probe.version)

        # HSTS : on lit l'en-tête même si la chaîne est incomplète, pour tout signaler d'un coup
        lax = ssl.create_default_context()
        lax.check_hostname = False
        lax.verify_mode = ssl.CERT_NONE
        try:
            status, headers = http_get(host, https_port, tls=True, context=lax, connect_ip=connect_ip, timeout=timeout)
            value = headers.get("strict-transport-security")
            if not value:
                add("Web", host, "HSTS", "ÉCHEC",
                    f"en-tête absent (réponse {status}) : add_header Strict-Transport-Security ... always;")
            else:
                values = value.split("\n")
                max_age, subs = parse_hsts(values[0])
                ok = max_age is not None and max_age >= HSTS_MIN
                if len(values) > 1:                     # plan : « une seule fois » (Nginx seulement)
                    add("Web", host, "HSTS", "ÉCHEC",
                        f"en-tête envoyé {len(values)} fois (Nginx et WildFly ?) : " + " | ".join(values))
                else:
                    add("Web", host, "HSTS", "OK" if ok else "ÉCHEC",
                        f"{value}" + ("" if ok else f" (max-age d'au moins {HSTS_MIN} attendu)")
                        + ("" if subs else " ; sans includeSubDomains"))
        except Exception as exc:
            add("Web", host, "HSTS", "ÉCHEC", f"pas de réponse HTTPS ({exc})")

        try:
            status, headers = http_get(host, http_port, tls=False, context=None, connect_ip=connect_ip, timeout=timeout)
            location = headers.get("location", "")
            ok = status in (301, 302, 307, 308) and location.startswith(f"https://{host}")
            add("Web", host, "Port 80 redirige vers HTTPS", "OK" if ok else "ÉCHEC",
                f"{status} {location}".strip())
        except Exception as exc:
            add("Web", host, "Port 80 redirige vers HTTPS", "ÉCHEC", f"pas de réponse HTTP ({exc})")

    # 3. MQTT
    mqtt_host = f"mqtt.{domain}"
    if not cafile:
        add("MQTT", mqtt_host, "Certificat et chaîne (CA du projet)", "ATTENTION",
            "ca.pem introuvable : lancer deploy/fetch-ca.ps1 (ou --cafile)")
    else:
        probe = tls_probe(mqtt_host, mqtt_port, ssl.create_default_context(cafile=cafile), connect_ip, timeout)
        if not probe.ok:
            add("MQTT", f"{mqtt_host}:{mqtt_port}", "Certificat et chaîne (CA du projet)", "ÉCHEC", probe.error)
        else:
            chain = f", {probe.chain_len} certificats envoyés" if probe.chain_len else ""
            add("MQTT", f"{mqtt_host}:{mqtt_port}", "Certificat et chaîne (CA du projet)", "OK",
                f"émis par {issuer_name(probe.cert)}{chain}")
            left = days_left(probe.cert)
            add("MQTT", f"{mqtt_host}:{mqtt_port}", f"Validité (au moins {MIN_DAYS} jours)",
                "OK" if left >= MIN_DAYS else "ÉCHEC", f"{left} jours restants")
            add("MQTT", f"{mqtt_host}:{mqtt_port}", "Version TLS",
                "OK" if probe.version in GOOD_VERSIONS else "ÉCHEC", probe.version)
    try:
        with socket.create_connection((connect_ip or mqtt_host, mqtt_plain_port), timeout=min(timeout, 3)):
            add("MQTT", f"{mqtt_host}:{mqtt_plain_port}", "MQTT en clair fermé", "ÉCHEC", "le port répond")
    except OSError:
        add("MQTT", f"{mqtt_host}:{mqtt_plain_port}", "MQTT en clair fermé", "OK", "refusé ou filtré")
    return results


# ---------------------------------------------------------------------------- sortie
def print_results(results: list[Result]) -> None:
    section = None
    for r in results:
        if r.section != section:
            section = r.section
            print(f"\n{section}")
        print(f"  [{r.status:<9}] {r.target} : {r.check}" + (f"\n               {r.detail}" if r.detail else ""))


def write_report(results: list[Result], domain: str, connect_ip: str | None, out_dir: Path) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    now = dt.datetime.now(dt.timezone.utc)
    path = out_dir / f"audit-securite-{now:%Y%m%d-%H%M}.md"
    fails = sum(r.status == "ÉCHEC" for r in results)
    lines = [
        f"# Audit de sécurité TLS : {domain}",
        "",
        f"- Date : {now:%Y-%m-%d %H:%M} UTC",
        f"- Cible : {connect_ip + ' (IP imposée)' if connect_ip else 'résolution DNS normale'}",
        f"- Résultat : {'conforme' if fails == 0 else f'{fails} point(s) en échec'}",
        "",
        "| Partie | Cible | Contrôle | Résultat | Détail |",
        "| --- | --- | --- | --- | --- |",
    ]
    for r in results:
        detail = r.detail.replace("|", "\\|")
        lines.append(f"| {r.section} | {r.target} | {r.check} | {r.status} | {detail} |")
    lines += ["", "Généré par deploy/audit_securite.py (étape 6)."]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return path


def default_cafile() -> str | None:
    for candidate in (ROOT / "infra/mqtt/certs-serveur/ca.pem", ROOT / "infra/mqtt/certs/ca.pem"):
        if candidate.is_file():
            return str(candidate)
    return None


def parse_args(argv=None):
    p = argparse.ArgumentParser(description="Audit TLS du serveur en ligne : HSTS, chaîne TLS-CA, CAA, MQTT")
    p.add_argument("domain", help="domaine de l'équipe, sans www (ex. sbe-team.me)")
    p.add_argument("--ip", default=None, help="se connecter à cette IP (noms gardés pour le certificat et l'en-tête Host)")
    p.add_argument("--cafile", default=None, help="CA du broker MQTT (défaut : infra/mqtt/certs-serveur/ca.pem)")
    p.add_argument("--web-cafile", default=None, help=argparse.SUPPRESS)  # essais : CA racine de test
    p.add_argument("--timeout", type=float, default=8.0, help="secondes d'attente par connexion (défaut : 8)")
    p.add_argument("--report-dir", default=str(ROOT / "deploy" / "reports"), help="dossier du rapport Markdown")
    p.add_argument("--no-report", action="store_true", help="ne pas écrire de rapport")
    return p.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    domain = args.domain.strip().lower().removeprefix("www.")
    cafile = args.cafile or default_cafile()
    print(f"Audit TLS de {domain}" + (f" via {args.ip}" if args.ip else ""))
    results = audit(domain, connect_ip=args.ip, cafile=cafile, web_cafile=args.web_cafile, timeout=args.timeout)
    print_results(results)
    fails = sum(r.status == "ÉCHEC" for r in results)
    warns = sum(r.status == "ATTENTION" for r in results)
    print()
    if not args.no_report:
        print(f"Rapport : {write_report(results, domain, args.ip, Path(args.report_dir))}")
    print("Tout est conforme." if fails == 0 else f"{fails} point(s) en échec, {warns} à regarder.")
    return 0 if fails == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
