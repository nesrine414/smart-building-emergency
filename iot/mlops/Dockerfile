# Image du service sbe-ml : score de risque ML publié en continu (étape 4, personne 1).
#
#   docker compose -f docker-compose.yml -f docker-compose.secure.yml -f docker-compose.prod.yml build sbe-ml
#
# Les modèles (models/), le dataset (data/) et le certificat de la CA sont montés par Compose :
# rien de secret ni de volumineux n'est copié dans l'image.
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

COPY requirements-docker.txt .
RUN pip install -r requirements-docker.txt

COPY features.py train.py infer.py healthcheck.py ./

# Utilisateur sans privilèges. Sur le serveur, Compose lance le conteneur avec l'UID de sbe
# (SBE_UID) : les modèles écrits par train.py dans models/ appartiennent à sbe.
RUN useradd --uid 1000 --create-home sbe && mkdir -p /app/models /app/data && chown -R sbe /app
USER sbe

CMD ["python", "infer.py", "--help"]
