#!/usr/bin/env bash
# =====================================================================
#  ACTUALIZACIÓN v11.3 → v11.4 — arreglo del crash al abrir la ficha
#  Causa: el contador de capítulos pasa un texto («12/50») al string
#  con formato, y las variantes de idioma values-es/values-ru-rRU
#  seguían con %d (la v11.3 solo cambió el fichero por defecto) →
#  IllegalFormatConversionException al abrir la ficha en un móvil en
#  español o ruso. Ahora %s en TODOS los idiomas, idéntico a la oficial
#  (que usa %s en sus 4 variantes).
#  Requisito: tu árbol debe estar en el estado v11.3 (v10 + v11 +
#  arreglos v11.1 y v11.2 + auditoría v11.3). El patch va incrustado.
#  USO: Git Bash, DENTRO de la carpeta del proyecto:
#     bash actualizar_a_v11_4.sh
# =====================================================================
set -e
DELTA="/tmp/patch_actualizacion_v11_4.patch"

echo "PASO 1/3: comprobando la carpeta del proyecto..."
if [ ! -f "app/src/main/java/com/lagradost/quicknovel/ui/result/ResultFragment.kt" ]; then
  echo "ERROR: abre Git Bash DENTRO de la carpeta del proyecto y repite."
  exit 1
fi

echo "PASO 2/3: escribiendo y comprobando el patch..."
cat > "$DELTA" <<'PATCH_EOF'
diff --git a/app/src/main/res/values-es/strings.xml b/app/src/main/res/values-es/strings.xml
index 908bb34..9c11224 100644
--- a/app/src/main/res/values-es/strings.xml
+++ b/app/src/main/res/values-es/strings.xml
@@ -110,7 +110,7 @@
     <!-- Interfaz del Lector -->
     <string name="read_action_settings">Ajustes</string>
     <string name="read_action_chapters">Capítulos</string>
-    <string name="read_action_chapters_formatted" formatted="true">%d Capítulos</string>
+    <string name="read_action_chapters_formatted" formatted="true">%s Capítulos</string>
     <string name="select_chapter">Seleccionar capítulo</string>
     <string name="search_chapters">Buscar capítulos…</string>
     <string name="reload_chapter">Recargar capítulo</string>
diff --git a/app/src/main/res/values-ru-rRU/strings.xml b/app/src/main/res/values-ru-rRU/strings.xml
index 7c2bde2..c43fcf6 100644
--- a/app/src/main/res/values-ru-rRU/strings.xml
+++ b/app/src/main/res/values-ru-rRU/strings.xml
@@ -86,7 +86,7 @@
     <string name="mainpage_sort_by_button_text">Сортировать</string>
     <string name="read_action_settings">Настройки</string>
     <string name="read_action_chapters">Главы</string>
-    <string name="read_action_chapters_formatted" formatted="true">%d глав</string>
+    <string name="read_action_chapters_formatted" formatted="true">%s глав</string>
 
     <string name="read_action_tts">Озвучка</string>
     <string name="read_action_screen_rotation">Поворот экрана</string>
PATCH_EOF

if ! git apply --check --whitespace=nowarn "$DELTA" 2>/tmp/apply_err.txt; then
  echo "ERROR: el patch no encaja en tu árbol actual."
  echo "Tu árbol debe estar en el estado v11.3 (v10 + v11 + arreglos v11.1/v11.2 + auditoría v11.3)."
  echo "Detalle del rechazo:"
  cat /tmp/apply_err.txt
  exit 1
fi
git apply --whitespace=nowarn "$DELTA"
echo "Patch aplicado: 2 ficheros de idioma corregidos (%d → %s)."

echo "PASO 3/3: guardando en un commit..."
git add -A
git commit -m "v11.4: arreglo de crash al abrir la ficha - %d a %s en values-es y values-ru-rRU (formato del contador)" \
  || echo "(no había nada nuevo que guardar)"
echo
echo "====================================================================="
echo " LISTO. Compila de nuevo: Build > Make Project (o ./gradlew assembleDebug)"
echo " Si el teléfono tenía la app instalada, reinstala (el crash era en"
echo " tiempo de ejecución, no de compilación)."
echo "====================================================================="
