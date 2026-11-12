#!/usr/bin/env bash
# =====================================================================
#  CORRECCIÓN v11.2 — import que falta para sharedPool.clear()
#  En este fork, AnyAdapter.sharedPool es un Pair<WeakHashMap, lambda>
#  y .clear() es una EXTENSIÓN definida en BaseAdapter.kt (paquete ui).
#  Usarla exige el import com.lagradost.quicknovel.ui.clear (igual que
#  hace SettingsFragment.kt). El patch v11 original llamaba
#  AnyAdapter.sharedPool.clear() en DownloadFragment.kt sin ese import
#  → "Unresolved reference 'clear'" en compileDebugKotlin.
#  Este arreglo añade SOLO esa línea de import. No se toca nada más.
#  USO: Git Bash, DENTRO de la carpeta del proyecto:
#     bash arreglo_v11_2.sh
# =====================================================================
set -e

F="app/src/main/java/com/lagradost/quicknovel/ui/download/DownloadFragment.kt"
IMPORT="import com.lagradost.quicknovel.ui.clear"

echo "PASO 1/3: comprobando la carpeta del proyecto..."
if [ ! -f "$F" ]; then
  echo "ERROR: abre Git Bash DENTRO de la carpeta del proyecto y repite."
  exit 1
fi

if grep -q "$IMPORT" "$F"; then
  echo "El import ya está en DownloadFragment.kt: ¿ya corregido? Nada que hacer."
  exit 0
fi

if ! grep -q "AnyAdapter.sharedPool.clear()" "$F"; then
  echo "AVISO: DownloadFragment.kt no contiene la llamada sharedPool.clear() —"
  echo "parece que la v11 no está aplicada. Ejecuta antes instalar_mejoras_ui_v11.sh"
  echo "(el patch ya viene corregido) y no necesitas este arreglo."
  exit 1
fi

if ! grep -q '^import com\.lagradost\.quicknovel\.ui\.img$' "$F"; then
  echo "ERROR: estructura de imports inesperada (no encuentro la línea"
  echo "'import com.lagradost.quicknovel.ui.img'). No toco nada; revisa $F a mano."
  exit 1
fi

echo "PASO 2/3: añadiendo el import que falta..."
sed -i '/^import com\.lagradost\.quicknovel\.ui\.img$/i import com.lagradost.quicknovel.ui.clear' "$F"

N=$(grep -c "$IMPORT" "$F" || true)
if [ "$N" != "1" ]; then
  echo "ERROR: tras insertar hay $N ocurrencias del import (se esperaba 1). Revisa a mano."
  exit 1
fi
echo "Hecho: DownloadFragment.kt ahora importa com.lagradost.quicknovel.ui.clear"
echo "       (la misma extensión Pair.clear() que usa SettingsFragment.kt:534)."

echo "PASO 3/3: guardando en un commit..."
git add -A
git commit -m "v11.2: añade el import de la extensión Pair.clear() (BaseAdapter) que exigía sharedPool.clear()" \
  || echo "(no había nada nuevo que guardar)"
echo
echo "====================================================================="
echo " LISTO. Compila de nuevo: Build > Make Project (o ./gradlew assembleDebug)"
echo "====================================================================="
