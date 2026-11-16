"""
Healthcheck Docker du service sbe-ml (étape 4).

infer.py --health-file FICHIER écrit l'heure toutes les 10 s tant qu'il est connecté au broker.
Ce script répond « sain » (code 0) si l'heure lue a moins de MAX secondes, « malade » (code 1)
sinon : script planté, bloqué, ou broker injoignable depuis trop longtemps.

Le contrôle ne dépend pas de l'arrivée de mesures : sans simulateur ni ESP32, il n'y a pas de
score à publier, et le service est pourtant en bonne santé.

Utilisation : python healthcheck.py /tmp/sbe-ml-health 60
"""

import sys
import time


def check(path, max_age, now=None):
    """Renvoie (sain, message)."""
    now = time.time() if now is None else now
    try:
        with open(path, encoding="utf-8") as handle:
            beat = float(handle.read().strip())
    except FileNotFoundError:
        return False, f"aucun battement dans {path} (infer.py pas encore connecté au broker ?)"
    except ValueError:
        return False, f"contenu illisible dans {path}"
    age = now - beat
    if age > max_age:
        return False, f"dernier battement il y a {age:.0f} s (maximum {max_age:.0f} s)"
    return True, f"connecté, dernier battement il y a {age:.0f} s"


def main(argv):
    if len(argv) not in (2, 3):
        print("utilisation : python healthcheck.py FICHIER [AGE_MAX_SECONDES]")
        return 2
    max_age = float(argv[2]) if len(argv) == 3 else 60.0
    healthy, message = check(argv[1], max_age)
    print(message)
    return 0 if healthy else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
