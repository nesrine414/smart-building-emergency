"""Le haché doit être identique à celui de l'outil officiel de l'extension HiveMQ File RBAC."""
import xml.etree.ElementTree as ET

import pytest

import gen_credentials as G

# Exemple officiel du README de l'extension (mot de passe "pass1"), reproduit aussi avec
# java -jar hivemq-file-rbac-extension-4.6.19.jar -p pass1 -s XSPQPtRLc3N1kHRGPP4hn92JW7emp8n9 -i 100
OFFICIAL_SALT = "XSPQPtRLc3N1kHRGPP4hn92JW7emp8n9"
OFFICIAL_HASH = ("WFNQUVB0UkxjM04xa0hSR1BQNGhuOTJKVzdlbXA4bjk=:100:FY12nwpUEbBK9EKQ/Aw/rQKSoA7jXsC0HKELwU2mLCVU"
                 "39bJVK0zf4NemuFeDOHPO4BW1nOjxi6NporkC6rUog==")

PASSWORDS = {"sbe-backend": "backend-secret-1", "sbe-device": "device-secret-22", "sbe-ml": "ml-secret-333"}


def test_identique_a_l_outil_officiel():
    assert G.hash_password("pass1", iterations=100, salt=OFFICIAL_SALT) == OFFICIAL_HASH


def test_verification_comme_hivemq():
    assert G.verify_password("pass1", OFFICIAL_HASH)
    assert not G.verify_password("pass2", OFFICIAL_HASH)
    assert not G.verify_password("pass1", "pas:un:hache")


def test_sel_aleatoire_a_chaque_hachage():
    assert G.hash_password("x" * 12, iterations=1000) != G.hash_password("x" * 12, iterations=1000)


def test_credentials_conformes_au_contrat_5():
    root = ET.fromstring(G.build_credentials_xml(PASSWORDS, iterations=1000).encode("utf-8"))
    users = {u.findtext("name"): u for u in root.iter("user")}
    assert set(users) == {"sbe-backend", "sbe-device", "sbe-ml"}
    for name, user in users.items():
        assert G.verify_password(PASSWORDS[name], user.findtext("password"))
        assert PASSWORDS[name] not in G.build_credentials_xml(PASSWORDS, iterations=1000)

    roles = {}
    for role in root.iter("role"):
        roles[role.findtext("id")] = {(p.findtext("activity"), p.findtext("topic")) for p in role.iter("permission")}
    assert roles["backend"] == {("PUBLISH", "smartbuilding/+/+/cmd"), ("SUBSCRIBE", "smartbuilding/#")}
    assert ("PUBLISH", "smartbuilding/+/+/actuator") in roles["device"]
    assert ("SUBSCRIBE", "smartbuilding/+/+/cmd") in roles["device"]
    assert ("PUBLISH", "smartbuilding/+/+/cmd") not in roles["device"]
    assert roles["ml"] >= {("PUBLISH", "smartbuilding/+/+/RISK"), ("SUBSCRIBE", "smartbuilding/+/+/GAS")}
    assert not any(activity == "PUBLISH" and not topic.endswith("RISK") for activity, topic in roles["ml"])
    # Aucune publication "retained" autorisée
    for permission in root.iter("permission"):
        if permission.findtext("activity") == "PUBLISH":
            assert permission.findtext("retain") == "NOT_RETAINED"


def test_mots_de_passe_refuses_si_vides_courts_ou_identiques(monkeypatch):
    for variable in ("MQTT_BACKEND_PASSWORD", "MQTT_DEVICE_PASSWORD", "MQTT_ML_PASSWORD"):
        monkeypatch.delenv(variable, raising=False)
    _, problems = G.collect_passwords({"MQTT_BACKEND_PASSWORD": "court", "MQTT_DEVICE_PASSWORD": "",
                                       "MQTT_ML_PASSWORD": "court"})
    text = " ".join(problems)
    assert "MQTT_DEVICE_PASSWORD est vide" in text
    assert "moins de 12" in text
    assert "même mot de passe" in text


def test_init_env_ne_remplace_pas_les_valeurs_existantes(tmp_path):
    (tmp_path / ".env.example").write_text("# commentaire\nMQTT_DEVICE_PASSWORD=\nMQTT_ML_PASSWORD=\nAUTRE=\n",
                                           encoding="utf-8")
    env = tmp_path / ".env"
    G.init_env(env, tmp_path / ".env.example")
    first = G.read_env_file(env)
    assert len(first["MQTT_DEVICE_PASSWORD"]) == 24 and first["AUTRE"] == ""
    G.init_env(env, tmp_path / ".env.example")
    assert G.read_env_file(env) == first
    assert env.read_text(encoding="utf-8").startswith("# commentaire")


def test_lecture_env_avec_guillemets(tmp_path):
    env = tmp_path / ".env"
    env.write_text('A="valeur 1"\nB=\'deux\'\n# C=non\nD = quatre\n', encoding="utf-8")
    assert G.read_env_file(env) == {"A": "valeur 1", "B": "deux", "D": "quatre"}


def test_iterations_trop_faibles_refusees(tmp_path):
    with pytest.raises(SystemExit):
        G.main(["--iterations", "100", "--env-file", str(tmp_path / ".env"), "--output", str(tmp_path / "c.xml")])
