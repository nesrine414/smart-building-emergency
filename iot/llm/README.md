# Serveur LLM local : Ollama (étape 3, personne 1)

L'agent IA de la personne 2 (LangChain4j) appelle un modèle de langage qui tourne sur le PC, servi par
Ollama sur `http://localhost:11434`. Rien ne part sur Internet. Le rôle de la personne 1 : installer
Ollama, choisir un modèle **capable d'appeler des outils**, le vérifier et mesurer son temps de réponse.

## 1. Installer Ollama

Windows : télécharger l'installateur sur https://ollama.com/download et le lancer. Ollama démarre
tout seul (icône près de l'horloge) et écoute sur le port 11434. Vérifier :

```powershell
ollama --version
curl.exe http://localhost:11434/api/version
```

## 2. Choisir et télécharger le modèle

| RAM du PC de démo | Modèle | Taille | Remarque |
| --- | --- | --- | --- |
| 16 Go ou plus | `qwen2.5:7b` | ~4,7 Go | Bon français, appels d'outils fiables : **recommandé** |
| 8 Go | `llama3.2:3b` | ~2 Go | Plus rapide, un peu moins précis |
| à éviter | `phi3`, `mistral` (anciennes versions), modèles en `1b` | | `phi3` ne sait pas appeler d'outils ; les 1b sont trop faibles pour un agent |

```powershell
ollama pull qwen2.5:7b
ollama show qwen2.5:7b
```

Dans la sortie de `ollama show`, la section **Capabilities** doit contenir `tools`. Sinon, l'agent
ne pourra pas utiliser ses outils et LangChain4j recevra l'erreur « does not support tools ».

## 3. Vérifier l'appel d'outil et mesurer le temps de réponse

```powershell
python iot\llm\check_ollama.py                          # qwen2.5:7b, 3 mesures
python iot\llm\check_ollama.py --model llama3.2:3b --runs 5
```

Aucune installation Python n'est nécessaire (bibliothèque standard). Le script :

1. vérifie qu'Ollama répond et que le modèle est téléchargé ;
2. lit ses capacités (`/api/show`) : `tools` doit y être ;
3. envoie sur `/api/chat` la question « Quelles sont les dernières mesures de la salle room-101 ? »
   avec les **trois outils du contrat 3** (`getRecentReadings`, `getRoomEvacuationExit`,
   `getRiskScore`) : la réponse doit contenir un `tool_calls` vers `getRecentReadings` pour `room-101` ;
4. renvoie un résultat d'outil simulé (gaz à 1250 ppm) et vérifie que le modèle répond en texte,
   comme le fera l'agent ;
5. mesure le premier appel (chargement du modèle en mémoire, le plus lent) puis N appels « à chaud ».

Le rapport JSON est écrit dans `iot/llm/reports/` (pas sur Git) : latences (min, médiane, max),
jetons par seconde, taux de réussite de l'appel d'outil, et **délai d'attente conseillé** pour l'agent
(3 fois la latence la plus haute, 30 s minimum).

Le même test à la main (PowerShell) :

```powershell
$body = @'
{"model": "qwen2.5:7b", "stream": false,
 "messages": [{"role": "user", "content": "Quelles sont les dernières mesures de la salle room-101 ?"}],
 "tools": [{"type": "function", "function": {"name": "getRecentReadings",
   "description": "Dernières mesures d'une salle",
   "parameters": {"type": "object", "required": ["roomId"],
                  "properties": {"roomId": {"type": "string"}}}}}]}
'@
Invoke-RestMethod -Uri http://localhost:11434/api/chat -Method Post -ContentType "application/json" -Body $body |
  Select-Object -ExpandProperty message | ConvertTo-Json -Depth 6
```

La réponse doit contenir `tool_calls` avec `"name": "getRecentReadings"` et `"roomId": "room-101"`.

## 4. À transmettre à la personne 2

- le nom exact du modèle (`qwen2.5:7b` ou `llama3.2:3b`) ;
- l'URL : `http://localhost:11434` ;
- le **délai d'attente conseillé** et la latence médiane, lus dans le rapport ;
- le fait que le premier appel est lent (chargement) : voir ci-dessous.

## 5. Le jour de la démo

- Lancer Ollama et charger le modèle **avant** de commencer : `python iot\llm\check_ollama.py --runs 1`
  (le script demande à Ollama de garder le modèle en mémoire 30 minutes).
- Fermer les applications lourdes : un modèle 7b utilise environ 5 à 6 Go de RAM.
- Bonus du scénario (étape 9) : quitter Ollama depuis l'icône, poser une question dans la PWA ; le
  message d'erreur de l'agent et l'entrée `error` du journal LLMOps doivent apparaître. Relancer
  Ollama ensuite.

## Vérifié avant livraison

`check_ollama.py` est testé (`python -m pytest -q iot/llm/tests`, 6 tests) contre un faux serveur
qui reproduit les routes et les formats JSON d'Ollama : modèle compatible, modèle sans `tools`
(type `phi3`), modèle qui répond sans appeler d'outil, arguments renvoyés en texte, modèle non
téléchargé, Ollama arrêté. Le test avec un vrai modèle se fait sur le PC de démo (ce poste de
préparation n'avait pas accès au registre d'Ollama).
