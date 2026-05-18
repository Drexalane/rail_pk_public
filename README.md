# Rail PK

Overlay Android affichant le PK ferroviaire en temps réel sur toutes les lignes françaises.

PK superposé au-dessus de toute application (PDF, navigation…). Usage en cabine de conduite.

## Téléchargement

APK dans les [Releases](https://github.com/Drexalane/rail_pk_public/releases).

## Installation

Télécharger l'APK, autoriser les sources inconnues, installer. Au premier lancement, accepter les permissions (affichage par-dessus, localisation, batterie).

## Utilisation

1. Ouvrir un PDF d'aide à la conduite (Adobe Reader) — optionnel
2. Lancer **Rail PK** → page de garde avec :
   - **Démarrer** → lance la fenêtre overlay
   - **Couleur du PK** → 7 couleurs (magenta, rouge, orange, jaune, vert, bleu, noir)
   - **Centrer** → replace la fenêtre en bas
   - **README** → documentation intégrée
3. La fenêtre affiche :
   - **PK** en grand (arrondi à 200 m)
   - **Code ligne** (ex: 570000)
   - **Ø** vitesse moyenne de marche, **Σ** distance parcourue
   - Perte GPS : `~` (extrapolation) ou `PAS GPS`
4. Déplacer la fenêtre (glisser-déposer)

## FAQ

- **PK gelé** → vitesse < 3 km/h, normal. Reprend au-dessus.
- **Pas de GPS** → attendre le fix (10-30 s).
- **PK avec ~** → perte GPS > 5 s : extrapolation. Recalage automatique.
- **La fenêtre disparaît** → désactiver l'optimisation batterie pour Rail PK.

## Vie privée

Aucune donnée collectée. GPS local uniquement. Aucune connexion Internet. 100 % offline.

## Avertissement

Aide visuelle, pas un instrument réglementaire.

## Retour utilisateur

Ouvrir une [Issue](https://github.com/Drexalane/rail_pk_public/issues) (compte GitHub gratuit requis).

## Licence

MIT — Copyright (c) 2026 Drexalane
