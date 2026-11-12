#!/usr/bin/env bash
# =====================================================================
#  ARREGLO FICHA v10 - App Remodelada (Deshidia)
#  Problema: el patch v9 adoptó el nav graph oficial upstream, que abre
#  la ficha de novelas con la clase Compose ResultFragment2 (excluida del
#  patch -> no existe en tu app) -> al tocar una novela, nada/crash.
#  Arreglo: devolver UN archivo (mobile_navigation.xml) a tu versión
#  original. No se toca ninguna línea de código tuyo.
#  CÓMO USARLO: Git Bash, DENTRO de la carpeta del proyecto:
#     bash arreglo_ficha_v10.sh
# =====================================================================
set -e

NAV="app/src/main/res/navigation/mobile_navigation.xml"

echo "PASO 1/3: comprobando la carpeta del proyecto..."
if [ ! -d .git ] || [ ! -f "$NAV" ]; then
  echo "ERROR: abre Git Bash DENTRO de la carpeta del proyecto y repite."
  exit 1
fi

echo "PASO 2/3: examinando el nav graph..."
if ! grep -q "ResultFragment2" "$NAV"; then
  echo "Tu nav graph ya es el correcto (apunta a ResultFragment):"
  echo "no hay nada que arreglar."
  exit 0
fi
echo "Detectado: apunta a ResultFragment2 (la clase Compose que no existe)."

echo "PASO 3/3: devolviendo tu nav graph original y guardando..."
git checkout d0f29f3 -- "$NAV"
git add -A
git commit -m "v10: nav graph del fork (arregla la ficha de novelas)" \
  || echo "(no había nada nuevo que guardar)"
echo
echo "====================================================================="
echo " LISTO. Compila en Android Studio: Build > Make Project"
echo " (o ./gradlew assembleDebug) y prueba a abrir una novela."
echo " Si va bien, sube a GitHub con:"
echo "     git push --force origin master"
echo "====================================================================="
