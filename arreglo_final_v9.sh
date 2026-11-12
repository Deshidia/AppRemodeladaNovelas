#!/usr/bin/env bash
# =====================================================================
#  ARREGLO v9 - App Remodelada (Deshidia)
#  Qué hace: elimina UN solo archivo que se me coló por error en el
#  patch anterior (un "puente" duplicado que rompía el cargador de
#  imágenes y provocaba los errores Redeclaration / ForkImageLoader /
#  loadImage). Es el único cambio; el resto del patch ya estaba bien
#  (tu build falló SOLO por esto).
#  CÓMO USARLO: Git Bash, DENTRO de la carpeta del proyecto:
#     bash arreglo_final_v9.sh
# =====================================================================
set -e

SHIM="app/src/main/java/com/lagradost/cloudstream3/utils/ImageLoader.kt"

echo "PASO 1/3: comprobando la carpeta del proyecto..."
if [ ! -d .git ]; then
  echo "ERROR: abre Git Bash DENTRO de la carpeta del proyecto y repite."
  exit 1
fi
if [ ! -f "$SHIM" ]; then
  echo "El archivo problemático ya no existe: no hay nada que arreglar."
  exit 0
fi
echo "OK: proyecto encontrado y el archivo problemático está presente."

echo "PASO 2/3: eliminando $SHIM ..."
git rm -f "$SHIM" 2>/dev/null || rm -f "$SHIM"
rmdir "app/src/main/java/com/lagradost/cloudstream3/utils" \
      "app/src/main/java/com/lagradost/cloudstream3" 2>/dev/null || true
echo "OK: eliminado (era el único archivo defectuoso)."

echo "PASO 3/3: guardando el cambio en un commit..."
git add -A
git commit -m "v9: elimina shim ImageLoader (causaba la redeclaración)" \
  || echo "(no había nada nuevo que guardar)"
echo
echo "====================================================================="
echo " LISTO. Ahora compila en Android Studio: Build > Make Project"
echo " (o en la terminal: ./gradlew assembleDebug)"
echo " Si el build va bien, sube a GitHub con:"
echo "     git push --force origin master"
echo "====================================================================="
