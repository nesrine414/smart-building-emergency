import infer
import healthcheck


def test_battement_ecrit_puis_lu(tmp_path):
    path = tmp_path / "health"
    infer.write_heartbeat(path, 1000.0)
    assert healthcheck.check(path, 60, now=1030.0) == (True, "connecté, dernier battement il y a 30 s")
    assert not (tmp_path / "health.tmp").exists()      # écriture atomique : pas de reste


def test_battement_trop_vieux(tmp_path):
    path = tmp_path / "health"
    infer.write_heartbeat(path, 1000.0)
    healthy, message = healthcheck.check(path, 60, now=1061.0)
    assert not healthy and "61 s" in message


def test_fichier_absent_ou_illisible(tmp_path):
    assert healthcheck.check(tmp_path / "absent", 60)[0] is False
    (tmp_path / "bad").write_text("pas un nombre", encoding="utf-8")
    assert healthcheck.check(tmp_path / "bad", 60)[0] is False


def test_codes_de_sortie(tmp_path):
    path = tmp_path / "health"
    infer.write_heartbeat(path, __import__("time").time())
    assert healthcheck.main(["healthcheck.py", str(path), "60"]) == 0
    assert healthcheck.main(["healthcheck.py", str(tmp_path / "absent")]) == 1
    assert healthcheck.main(["healthcheck.py"]) == 2


def test_option_health_file():
    args = infer.parse_args(["--health-file", "/tmp/x", "--tls"])
    assert args.health_file == "/tmp/x" and args.port == 8883


def test_arret_si_deconnecte_trop_longtemps():
    assert not infer.disconnected_too_long(1100, 1000, None)       # désactivé par défaut
    assert not infer.disconnected_too_long(1100, 1000, 120)
    assert infer.disconnected_too_long(1121, 1000, 120)
