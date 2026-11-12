#!/usr/bin/env bash
# =====================================================================
#  CORRECCIÓN v11.1 — quita el string "all" duplicado
#  El primer patch v11 añadió <string name="all">All</string> a
#  values/strings.xml, pero la v10 YA lo define en
#  values/upstream_compat_injerto.xml con el mismo valor → el build
#  falla con "Duplicate resources: string/all".
#  Este arreglo borra SOLO la línea añadida por la v11. No se pierde
#  nada: R.string.all sigue resolviendo a "All" (el del injerto).
#  USO: Git Bash, DENTRO de la carpeta del proyecto:
#     bash arreglo_v11_1.sh
# =====================================================================
set -e

F="app/src/main/res/values/strings.xml"

echo "PASO 1/3: comprobando la carpeta del proyecto..."
if [ ! -f "$F" ]; then
  echo "ERROR: abre Git Bash DENTRO de la carpeta del proyecto y repite."
  exit 1
fi

if ! grep -q "chip_all" app/src/main/res/layout/fragment_result.xml 2>/dev/null; then
  echo "AVISO: la v11 no está aplicada todavía. Ejecuta primero"
  echo "instalar_mejoras_ui_v11.sh (el patch ya viene corregido) y no"
  echo "necesitas este arreglo."
  exit 1
fi

N=$(grep -c '<string name="all">All</string>' "$F" || true)
if [ "$N" = "0" ]; then
  echo "No encuentro el duplicado: ¿ya corregido? Nada que hacer."
  exit 0
fi
if [ "$N" != "1" ]; then
  echo "ERROR inesperado: hay $N líneas con <string name=\"all\">All</string>."
  echo "No toco nada automáticamente; revisa $F a mano."
  exit 1
fi

echo "PASO 2/3: quitando la línea duplicada de $F ..."
grep -v '^[[:space:]]*<string name="all">All</string>[[:space:]]*$' "$F" > "$F.tmp"
mv "$F.tmp" "$F"

if grep -q '<string name="all">All</string>' "$F"; then
  echo "ERROR: la línea sigue ahí. Revisa $F a mano."
  exit 1
fi
echo "Hecho: strings.xml ya no define 'all'; se usará el del injerto (valor idéntico: All)."

echo "PASO 3/3: guardando en un commit..."
git add -A
git commit -m "v11.1: quita el string 'all' duplicado (ya lo define upstream_compat_injerto.xml de la v10)" \
  || echo "(no había nada nuevo que guardar)"
echo
echo "====================================================================="
echo " LISTO. Compila de nuevo: Build > Make Project (o ./gradlew assembleDebug)"
echo "====================================================================="
