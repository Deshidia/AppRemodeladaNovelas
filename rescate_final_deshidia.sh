#!/usr/bin/env bash
# =====================================================================
#  RESCATE FINAL - App Remodelada (Deshidia)
#  Qué hace: devuelve tu proyecto a tu versión completa (d0f29f3, con la
#  función de la cruz de traducción y TODO tu trabajo), borra la v4 vieja
#  que se quedó mezclada, y aplica el patch oficial v3.9.0 (v8).
#  CÓMO USARLO: Git Bash, DENTRO de la carpeta del proyecto:
#     bash rescate_final_deshidia.sh
# =====================================================================
set -e

echo "PASO 1/6: comprobando que estás en la carpeta del proyecto..."
if [ ! -d .git ] || [ ! -f app/build.gradle ]; then
  echo "ERROR: esta carpeta no es el proyecto (falta .git o app/build.gradle)."
  echo "Abre Git Bash DENTRO de la carpeta del proyecto y vuelve a ejecutar."
  exit 1
fi
echo "OK: proyecto encontrado en $(pwd)"
echo

echo "PASO 2/6: guardando tu estado actual en una rama de respaldo 'respaldo-viejo'..."
git branch -f respaldo-viejo
echo "OK: por si acaso, el estado roto queda guardado en la rama respaldo-viejo"
echo

echo "PASO 3/6: volviendo a d0f29f3 (tu versión completa, SIN la v4 vieja)..."
git reset --hard d0f29f3
echo "OK: tu función 'cruz en la pantalla de traducción' y los archivos"
echo "    UntranslatedTermsDrawer / UntranslatedTerms vuelven a estar en el proyecto"
echo

echo "PASO 4/6: limpiando restos sueltos de los patches anteriores..."
rm -rf app/src/main/java/com/lagradost/cloudstream3
git clean -fdq app/src/main/java/com/lagradost/cloudstream4 \
               app/src/main/java/com/lagradost/quicknovel/mihon \
               app/src/main/java/com/lagradost/quicknovel/downloader 2>/dev/null || true
echo "OK"
echo

echo "PASO 5/6: aplicando el patch de mejoras oficiales v3.9.0 (v8)..."
PATCH=$(ls -t ~/Downloads/patch_mejoras_oficiales_v3.9.0_para_AppRemodeladaNovelas.patch \
        "$PWD"/patch_mejoras_oficiales_v3.9.0_para_AppRemodeladaNovelas.patch 2>/dev/null | head -1)
if [ -z "$PATCH" ]; then
  echo "No encontré el archivo del patch. Descárgalo del chat y guárdalo en la"
  echo "carpeta del proyecto (o en C:\\Users\\Alejandro\\Downloads) y vuelve a ejecutar el script."
  exit 1
fi
echo "Usando: $PATCH"
git apply --whitespace=nowarn "$PATCH"
echo "OK: patch aplicado (49 archivos)"
echo

echo "PASO 6/6: guardando todo en un commit..."
git add -A
git commit -m "Adopción de mejoras oficiales v3.9.0 (v8, tras rescate)"
echo
echo "====================================================================="
echo " LISTO. Ahora compila en Android Studio: Build > Make Project"
echo " (o en la terminal: ./gradlew assembleDebug)"
echo " Si el build va bien, sube la versión arreglada a GitHub con:"
echo "     git push --force origin master"
echo " (sustituye en GitHub los commits rotos; tu respaldo queda en la"
echo "  rama local 'respaldo-viejo' por si acaso)"
echo "====================================================================="
