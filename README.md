# InReflex Game Solver

App Android : une bulle flottante apparaît par-dessus LinkedIn dès qu'un jeu de réflexion est à l'écran, et disparaît quand tu le quittes. Touche la bulle :
l'app reconnaît le jeu, lit la grille, la résout et affiche la solution en transparence. Tu joues toi-même.

Jeux reconnus : Zip, Queens, Tango, Mini Sudoku, Patches.

## Installer
1. Ouvre la page **Releases** du dépôt depuis ton téléphone et télécharge `InReflexGameSolver.apk`.
2. Ouvre le fichier et autorise l'installation depuis ton navigateur si Android le demande.

## Utiliser
1. Ouvre InReflex Solver, touche « Activer la bulle et ouvrir LinkedIn ».
2. Autorise l'affichage par-dessus les autres apps, puis la capture d'écran (choisis « Tout l'écran »).
3. Ouvre un jeu : la bulle apparaît. Touche-la pour voir la solution. Touche-la à nouveau pour masquer le chemin. Appui long pour la fermer.

## Compiler
Chaque push sur `main` compile l'APK avec GitHub Actions et le publie dans la release `latest`.
