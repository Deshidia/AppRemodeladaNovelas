#!/usr/bin/env bash
# =====================================================================
#  MEJORAS DE INTERFAZ v11 — biblioteca + ficha (App Remodelada)
#  Qué trae:
#   • Biblioteca: barra de búsqueda más grande con lupa completa (como la
#     oficial), botón "+" con Importar libro y Actualizar, y botón para
#     alternar rejilla/lista sin ir a Ajustes.
#   • Ficha: chips de filtro rápido Todo / Descargado / Has leído sobre la
#     lista de capítulos + contador de capítulos visibles. Usan las mismas
#     preferencias que tu panel de filtros (nada se duplica ni se pierde).
#   • Tu barra "Search Chapters" no se rediseña: solo se le añade la lupa
#     bien visible y el tamaño de la oficial.
#  Necesita: patch_ui_biblioteca_ficha_v11.patch en esta misma carpeta,
#  y el estado actual (patch oficial v10 ya aplicado).
#  USO: Git Bash, DENTRO de la carpeta del proyecto:
#     bash instalar_mejoras_ui_v11.sh
# =====================================================================
set -e

PATCH="patch_ui_biblioteca_ficha_v11.patch"

echo "PASO 1/3: comprobando la carpeta del proyecto..."
if [ ! -d .git ]; then
  echo "ERROR: abre Git Bash DENTRO de la carpeta del proyecto y repite."
  exit 1
fi
if [ ! -f "$PATCH" ]; then
  echo "ERROR: no encuentro $PATCH en esta carpeta. Descárgalo y ponlo"
  echo "junto a este script (en la carpeta del proyecto)."
  exit 1
fi
if grep -q "chip_all" app/src/main/res/layout/fragment_result.xml 2>/dev/null; then
  echo "La v11 ya está aplicada. Nada que hacer."
  exit 0
fi
if grep -q "ResultFragment2" app/src/main/res/navigation/mobile_navigation.xml 2>/dev/null; then
  echo "AVISO: tu nav graph todavía es el roto (falta el arreglo v10)."
  echo "La ficha seguiría sin abrir aunque aplique la v11."
  echo "Ejecuta PRIMERO arreglo_ficha_v10.sh y luego repite este script."
  exit 1
fi

echo "PASO 2/3: aplicando el patch v11 (5 archivos)..."
git apply --whitespace=nowarn "$PATCH"

echo "PASO 3/3: guardando el cambio en un commit..."
git add -A
git commit -m "v11: biblioteca (+/actualizar, rejilla-lista, barra oficial) y ficha (chips Todo/Descargado/Has leído, contador, barra capítulos con lupa)" \
  || echo "(no había nada nuevo que guardar)"
echo
echo "====================================================================="
echo " LISTO. Compila en Android Studio: Build > Make Project"
echo " (o ./gradlew assembleDebug) y prueba la biblioteca y una ficha."
echo " Si va bien, sube a GitHub con:"
echo "     git push --force origin master"
echo "====================================================================="
