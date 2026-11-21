"""check_ports.py contre de vrais ports locaux : ouverts (un serveur écoute) et fermés."""
import socket

import check_ports as C


def listening_socket():
    server = socket.socket()
    server.bind(("127.0.0.1", 0))
    server.listen()
    return server


def free_port():
    probe = socket.socket()
    probe.bind(("127.0.0.1", 0))
    port = probe.getsockname()[1]
    probe.close()
    return port


def run(*argv):
    return C.run(C.parse_args(list(argv)))


def test_conforme_quand_les_bons_ports_sont_ouverts():
    a, b = listening_socket(), listening_socket()
    try:
        code = run("--ip", "127.0.0.1", "--timeout", "1",
                   "--expect-open", str(a.getsockname()[1]), str(b.getsockname()[1]),
                   "--expect-closed", str(free_port()))
    finally:
        a.close(), b.close()
    assert code == 0


def test_echec_si_un_port_interne_est_ouvert():
    leak = listening_socket()
    try:
        code = run("--ip", "127.0.0.1", "--timeout", "1", "--expect-open", str(leak.getsockname()[1]),
                   "--expect-closed", str(leak.getsockname()[1]))
    finally:
        leak.close()
    assert code == 1


def test_echec_si_un_port_public_ne_repond_pas():
    assert run("--ip", "127.0.0.1", "--timeout", "1", "--expect-open", str(free_port()),
               "--expect-closed", str(free_port())) == 1


def test_domaine_introuvable():
    assert run("domaine-qui-n-existe-pas.invalid", "--timeout", "1") == 1


def test_valeurs_par_defaut_du_plan():
    args = C.parse_args(["exemple.me"])
    assert args.expect_open == [80, 443, 8883]
    assert 27017 in args.expect_closed and 1883 in args.expect_closed and 9990 in args.expect_closed
