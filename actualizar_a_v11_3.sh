#!/usr/bin/env bash
# =====================================================================
#  ACTUALIZACIÓN v11.2 → v11.3 — fidelidad a la app oficial
#  Aplica la auditoría que alinea la v11 con la app oficial (Compose):
#   · botones «+» y rejilla/lista DENTRO de la barra de búsqueda
#     (trailing icons, como DownloadScreen.kt de la oficial)
#   · contador de capítulos con el formato oficial «N Chapters» o
#     «X/Y Chapters» (ResultScreen.kt FilterHeader)
#   · chip «Has read» alterna a «Not read» como la oficial
#   · eliminado el texto inventado «X of Y chapters»
#  Requisito: tu árbol debe estar en el estado v11.2 (v10 + v11 +
#  arreglos v11.1 y v11.2). El patch va incrustado aquí abajo: solo
#  tienes que descargar y ejecutar ESTE archivo.
#  USO: Git Bash, DENTRO de la carpeta del proyecto:
#     bash actualizar_a_v11_3.sh
# =====================================================================
set -e
DELTA="/tmp/patch_actualizacion_v11_3.patch"

echo "PASO 1/3: comprobando la carpeta del proyecto..."
if [ ! -f "app/src/main/java/com/lagradost/quicknovel/ui/download/DownloadFragment.kt" ]; then
  echo "ERROR: abre Git Bash DENTRO de la carpeta del proyecto y repite."
  exit 1
fi

echo "PASO 2/3: escribiendo y comprobando el patch..."
cat > "$DELTA" <<'PATCH_EOF'
diff --git a/app/src/main/java/com/lagradost/quicknovel/ui/result/ResultFragment.kt b/app/src/main/java/com/lagradost/quicknovel/ui/result/ResultFragment.kt
index 653f4c5..062e56a 100644
--- a/app/src/main/java/com/lagradost/quicknovel/ui/result/ResultFragment.kt
+++ b/app/src/main/java/com/lagradost/quicknovel/ui/result/ResultFragment.kt
@@ -816,11 +816,11 @@ class ResultFragment : BaseFragment<FragmentResultBinding>(
             }
             else -> visible
         }
-        binding.chapterCount.text = if (visible >= total) {
-            getString(R.string.read_action_chapters_formatted, total)
-        } else {
-            getString(R.string.chapters_count_filtered, visible, total)
-        }
+        // Formato oficial (ResultScreen.kt FilterHeader): «N Chapters» o «X/Y Chapters»
+        binding.chapterCount.text = getString(
+            R.string.read_action_chapters_formatted,
+            if (visible >= total) "$total" else "$visible/$total"
+        )
 
         val read = ResultViewModel.filterChapterByRead
         val unread = ResultViewModel.filterChapterByUnread
@@ -830,7 +830,11 @@ class ResultFragment : BaseFragment<FragmentResultBinding>(
         // «todo activo» y «solo una categoría»; los chips reflejan ese estado.
         binding.chipAll.isChecked = read && unread
         binding.chipDownloaded.isChecked = downloaded && !read && !unread
-        binding.chipRead.isChecked = read && !unread && !downloaded
+        val readOnly = read && !unread && !downloaded
+        val unRead = unread && !read && !downloaded
+        binding.chipRead.isChecked = readOnly || unRead
+        // Como la oficial, el chip alterna su etiqueta «Has read»↔«Not read»
+        binding.chipRead.setText(if (unRead) R.string.not_read else R.string.has_read)
     }
 
     private fun applyChapterFilter(
@@ -1058,11 +1062,13 @@ class ResultFragment : BaseFragment<FragmentResultBinding>(
         }
 
         // Chips de filtro rápido (Todo / Descargado / Has leído): un toque activa,
-        // otro toque vuelve a Todo, como el ciclo de la app oficial
+        // otro toque vuelve al estado anterior, como el ciclo de la app oficial
         binding.chipAll.setOnClickListener {
             applyChapterFilter(binding, read = true, unread = true, downloaded = true)
         }
         binding.chipDownloaded.setOnClickListener {
+            // La oficial alterna Descargado↔No descargado; el modelo del fork no
+            // tiene categoría «no descargado», así que se vuelve a Todo.
             val wasDownloadedOnly = ResultViewModel.filterChapterByDownloads &&
                 !ResultViewModel.filterChapterByRead && !ResultViewModel.filterChapterByUnread
             if (wasDownloadedOnly) {
@@ -1072,10 +1078,11 @@ class ResultFragment : BaseFragment<FragmentResultBinding>(
             }
         }
         binding.chipRead.setOnClickListener {
+            // Como la oficial (Has read↔Not read); Todo lo restablece
             val wasReadOnly = ResultViewModel.filterChapterByRead &&
                 !ResultViewModel.filterChapterByUnread && !ResultViewModel.filterChapterByDownloads
             if (wasReadOnly) {
-                applyChapterFilter(binding, read = true, unread = true, downloaded = true)
+                applyChapterFilter(binding, read = false, unread = true, downloaded = false)
             } else {
                 applyChapterFilter(binding, read = true, unread = false, downloaded = false)
             }
diff --git a/app/src/main/res/layout/fragment_downloads.xml b/app/src/main/res/layout/fragment_downloads.xml
index 58a107e..4cb8e39 100644
--- a/app/src/main/res/layout/fragment_downloads.xml
+++ b/app/src/main/res/layout/fragment_downloads.xml
@@ -8,57 +8,55 @@
         android:background="?attr/primaryGrayBackground"
         android:orientation="vertical">
 
-    <LinearLayout
+    <FrameLayout
             android:layout_height="48dp"
             android:layout_margin="10dp"
-            android:layout_width="match_parent"
-            android:orientation="horizontal">
-
-        <FrameLayout
+            android:background="@drawable/search_background"
+            android:layout_width="match_parent">
+
+        <androidx.appcompat.widget.SearchView
+                android:animateLayoutChanges="true"
+                android:id="@+id/download_search"
+                app:queryBackground="@color/transparent"
+                app:searchIcon="@drawable/search_icon"
+                app:searchHintIcon="@drawable/search_icon"
+                android:paddingStart="-10dp"
+                android:paddingEnd="92dp"
+                android:iconifiedByDefault="false"
+                app:queryHint="@string/search_downloads"
+                android:layout_width="match_parent"
                 android:layout_height="match_parent"
-                android:layout_width="0dp"
-                android:layout_weight="1"
-                android:layout_marginEnd="4dp"
-                android:background="@drawable/search_background">
-
-            <androidx.appcompat.widget.SearchView
-                    android:animateLayoutChanges="true"
-                    android:id="@+id/download_search"
-                    app:queryBackground="@color/transparent"
-                    app:searchIcon="@drawable/search_icon"
-                    app:searchHintIcon="@drawable/search_icon"
-                    android:paddingStart="-10dp"
-                    android:iconifiedByDefault="false"
-                    app:queryHint="@string/search_downloads"
-                    android:layout_width="match_parent"
-                    android:layout_height="match_parent"
-                    android:layout_gravity="center_vertical"
-                    tools:ignore="RtlSymmetry" />
-
-        </FrameLayout>
+                android:layout_gravity="center_vertical"
+                tools:ignore="RtlSymmetry" />
 
-        <!-- Menú de importar + alternar rejilla/lista, como en la app oficial.
+        <!-- Botones dentro de la barra (trailing icons), como en la app oficial
+             (DownloadScreen.kt trailingIcon): menú «+» e interruptor rejilla/lista.
              El botón de continuar de las portadas (resumeReading) no se toca. -->
-        <ImageButton
-                android:id="@+id/download_import_btn"
-                android:layout_width="44dp"
-                android:layout_height="44dp"
-                android:layout_gravity="center_vertical"
-                android:background="?attr/selectableItemBackgroundBorderless"
-                android:contentDescription="@string/import_epub"
-                android:src="@drawable/ic_baseline_add_24"
-                app:tint="?attr/iconColor" />
-
-        <ImageButton
-                android:id="@+id/download_layout_toggle"
-                android:layout_width="44dp"
-                android:layout_height="44dp"
-                android:layout_gravity="center_vertical"
-                android:background="?attr/selectableItemBackgroundBorderless"
-                android:contentDescription="@string/grid_view"
-                android:src="@drawable/ic_baseline_grid_view_24"
-                app:tint="?attr/iconColor" />
-    </LinearLayout>
+        <LinearLayout
+                android:layout_width="wrap_content"
+                android:layout_height="wrap_content"
+                android:layout_gravity="end|center_vertical"
+                android:orientation="horizontal">
+
+            <ImageButton
+                    android:id="@+id/download_import_btn"
+                    android:layout_width="44dp"
+                    android:layout_height="44dp"
+                    android:background="?attr/selectableItemBackgroundBorderless"
+                    android:contentDescription="@string/import_epub"
+                    android:src="@drawable/ic_baseline_add_24"
+                    app:tint="?attr/iconColor" />
+
+            <ImageButton
+                    android:id="@+id/download_layout_toggle"
+                    android:layout_width="44dp"
+                    android:layout_height="44dp"
+                    android:background="?attr/selectableItemBackgroundBorderless"
+                    android:contentDescription="@string/grid_view"
+                    android:src="@drawable/ic_baseline_grid_view_24"
+                    app:tint="?attr/iconColor" />
+        </LinearLayout>
+    </FrameLayout>
 
     <!--<com.google.android.material.appbar.AppBarLayout
             android:background="@android:color/transparent"
diff --git a/app/src/main/res/layout/fragment_result.xml b/app/src/main/res/layout/fragment_result.xml
index 52a197c..da22573 100644
--- a/app/src/main/res/layout/fragment_result.xml
+++ b/app/src/main/res/layout/fragment_result.xml
@@ -695,10 +695,10 @@ Translator: Rainbow Turtle" />
                                 android:id="@+id/chapter_count"
                                 android:layout_width="wrap_content"
                                 android:layout_height="wrap_content"
-                                android:layout_gravity="center_horizontal"
-                                android:layout_marginBottom="6dp"
+                                android:layout_marginHorizontal="16dp"
+                                android:layout_marginBottom="4dp"
                                 android:textColor="?attr/textColor"
-                                android:textSize="12sp"
+                                android:textSize="16sp"
                                 tools:text="120 Chapters" />
 
                             <androidx.recyclerview.widget.RecyclerView
diff --git a/app/src/main/res/values/strings.xml b/app/src/main/res/values/strings.xml
index d0fb26b..663ef0e 100644
--- a/app/src/main/res/values/strings.xml
+++ b/app/src/main/res/values/strings.xml
@@ -13,7 +13,6 @@
 
     <string name="search_hint">Search Novels…</string>
     <string name="search_chapters">Search Chapters…</string>
-    <string name="chapters_count_filtered" formatted="true">%1$d of %2$d chapters</string>
     <string name="synopsis">Synopsis</string>
     <string name="no_data">No Data</string>
     <string name="no_author">Author not found</string>
@@ -90,7 +89,7 @@
     <string name="read_action_chapters">Chapters</string>
     <string name="read_action_prev_chapter">Previous Chapter</string>
     <string name="read_action_next_chapter">Next Chapter</string>
-    <string name="read_action_chapters_formatted" formatted="true">%d Chapters</string>
+    <string name="read_action_chapters_formatted" formatted="true">%s Chapters</string>
 
     <string name="read_action_tts">Text To Speech</string>
     <string name="read_action_screen_rotation">Screen Rotation</string>
PATCH_EOF

if ! git apply --check --whitespace=nowarn "$DELTA" 2>/tmp/apply_err.txt; then
  echo "ERROR: el patch no encaja en tu árbol actual."
  echo "Tu árbol debe estar en el estado v11.2 (v10 + v11 + arreglos v11.1 y v11.2)."
  echo "Detalle del rechazo:"
  cat /tmp/apply_err.txt
  exit 1
fi
git apply --whitespace=nowarn "$DELTA"
echo "Patch aplicado: 4 ficheros actualizados."

echo "PASO 3/3: guardando en un commit..."
git add -A
git commit -m "v11.3: fidelidad a la oficial - botones dentro de la barra, contador N/X-Y Chapters, chip Has read/Not read, fuera el string inventado" \
  || echo "(no había nada nuevo que guardar)"
echo
echo "====================================================================="
echo " LISTO. Compila de nuevo: Build > Make Project (o ./gradlew assembleDebug)"
echo " El detalle de cada cambio está en CAMBIOS_v11.md (download/patches/)."
echo "====================================================================="
