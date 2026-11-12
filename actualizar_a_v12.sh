#!/usr/bin/env bash
# =====================================================================
#  ACTUALIZACIÓN v11.4 → v12 — lista de capítulos y barras
#  • Pulsación larga en un capítulo = modo selección con barra inferior
#    («N Seleccionados», cerrar, extender arriba/abajo, marcar leído/
#    no leído, descargar) — como el BottomBar oficial. Ítem de capítulo
#    al estilo oficial (check de leído, 2 líneas, colores).
#  • Barra de la biblioteca: solo «+» (sin interruptor de vistas) y el
#    SearchView conserva su comportamiento original del fork.
#  Nota: el descargador del fork trabaja por RANGOS: «descargar» lanza
#  la descarga desde el primer capítulo seleccionado hasta el final.
#  Requisito: tu árbol debe estar en el estado v11.4 (v10 + v11 +
#  arreglos v11.1-v11.4). El patch va incrustado.
#  USO: Git Bash, DENTRO de la carpeta del proyecto:
#     bash actualizar_a_v11_4.sh
# =====================================================================
set -e
DELTA="/tmp/patch_actualizacion_v12.patch"

echo "PASO 1/3: comprobando la carpeta del proyecto..."
if [ ! -f "app/src/main/java/com/lagradost/quicknovel/ui/result/ResultFragment.kt" ]; then
  echo "ERROR: abre Git Bash DENTRO de la carpeta del proyecto y repite."
  exit 1
fi

echo "PASO 2/3: escribiendo y comprobando el patch..."
cat > "$DELTA" <<'PATCH_EOF'
diff --git a/app/src/main/java/com/lagradost/quicknovel/ui/download/DownloadFragment.kt b/app/src/main/java/com/lagradost/quicknovel/ui/download/DownloadFragment.kt
index 35a8fe1..b61b177 100644
--- a/app/src/main/java/com/lagradost/quicknovel/ui/download/DownloadFragment.kt
+++ b/app/src/main/java/com/lagradost/quicknovel/ui/download/DownloadFragment.kt
@@ -6,7 +6,6 @@ import android.widget.ImageView
 import androidx.appcompat.widget.PopupMenu
 import androidx.appcompat.widget.SearchView
 import androidx.fragment.app.viewModels
-import androidx.preference.PreferenceManager
 import androidx.lifecycle.lifecycleScope
 import androidx.recyclerview.widget.RecyclerView
 import androidx.viewpager2.widget.ViewPager2
@@ -34,9 +33,7 @@ import com.lagradost.quicknovel.TTSGlobalState
 import com.lagradost.quicknovel.ui.BaseFragment
 import com.lagradost.quicknovel.ui.SortingMethodAdapter
 import com.lagradost.quicknovel.ui.UiImage
-import com.lagradost.quicknovel.ui.clear
 import com.lagradost.quicknovel.ui.img
-import com.lagradost.quicknovel.util.SettingsHelper.getDownloadIsCompact
 import com.lagradost.quicknovel.util.UIHelper.colorFromAttribute
 import com.lagradost.quicknovel.ui.TTSMiniPlayerBinder
 import com.lagradost.quicknovel.util.UIHelper.fixPaddingStatusbar
@@ -140,17 +137,6 @@ class DownloadFragment : BaseFragment<FragmentDownloadsBinding>(
     private var tabLayoutMediator: TabLayoutMediator? = null
     private var pagerAdapter: ViewpagerAdapter? = null
 
-    // Como en la app oficial: el icono muestra la vista a la que cambiarías al pulsar
-    private fun updateLayoutToggleIcon(binding: FragmentDownloadsBinding) {
-        val context = binding.root.context
-        val isGrid = !context.getDownloadIsCompact()
-        binding.downloadLayoutToggle.setImageResource(
-            if (isGrid) R.drawable.ic_baseline_list_24 else R.drawable.ic_baseline_grid_view_24
-        )
-        binding.downloadLayoutToggle.contentDescription =
-            context.getString(if (isGrid) R.string.list_view else R.string.grid_view)
-    }
-
     override fun onResume() {
         super.onResume()
         // Picks up any list added/renamed/removed/reordered in Settings while
@@ -214,24 +200,6 @@ class DownloadFragment : BaseFragment<FragmentDownloadsBinding>(
             popup.show()
         }
 
-        // Alternar rejilla/lista sin salir de la biblioteca (misma preferencia que ya usa la app)
-        binding.downloadLayoutToggle.setOnClickListener { view ->
-            val context = view.context
-            val isGrid = !context.getDownloadIsCompact()
-            PreferenceManager.getDefaultSharedPreferences(context)
-                .edit()
-                .putString(context.getString(R.string.download_format_key), if (isGrid) "list" else "grid")
-                .apply()
-            updateLayoutToggleIcon(binding)
-            // Reconstruir las páginas con el nuevo formato, conservando la pestaña actual
-            val current = binding.viewpager.currentItem
-            AnyAdapter.sharedPool.clear()
-            binding.viewpager.adapter = null
-            binding.viewpager.adapter = pagerAdapter
-            binding.viewpager.setCurrentItem(current, false)
-        }
-        updateLayoutToggleIcon(binding)
-
 
         val adapter = ViewpagerAdapter(viewModel, this) { isScrollingDown ->
             if (isScrollingDown)
diff --git a/app/src/main/java/com/lagradost/quicknovel/ui/result/ChapterAdapter.kt b/app/src/main/java/com/lagradost/quicknovel/ui/result/ChapterAdapter.kt
index bb29b64..e2d635b 100644
--- a/app/src/main/java/com/lagradost/quicknovel/ui/result/ChapterAdapter.kt
+++ b/app/src/main/java/com/lagradost/quicknovel/ui/result/ChapterAdapter.kt
@@ -1,9 +1,12 @@
 package com.lagradost.quicknovel.ui.result
 
+import android.graphics.Color
+import android.util.TypedValue
 import android.view.LayoutInflater
 import android.view.ViewGroup
 import androidx.core.view.isGone
 import com.lagradost.quicknovel.ChapterData
+import com.lagradost.quicknovel.R
 import com.lagradost.quicknovel.databinding.SimpleChapterBinding
 import com.lagradost.quicknovel.ui.BaseDiffCallback
 import com.lagradost.quicknovel.ui.NoStateAdapter
@@ -39,10 +42,21 @@ class ChapterAdapter(val viewModel: ResultViewModel) :
         card: ChapterData,
         viewModel: ResultViewModel
     ) {
-        val alpha = if (viewModel.hasReadChapter(chapter = card)) 0.5F else 1.0F
+        // Icono de leído a la izquierda, como el ChapterItem oficial
+        // (ResultScreen.kt: AnimatedVisibility(response.chaptersRead[...]))
+        binding.chapterReadCheck.isGone = !viewModel.hasReadChapter(chapter = card)
 
-        binding.name.alpha = alpha
-        binding.releaseDate.alpha = alpha
+        // Resalte de selección, como la oficial:
+        // onBackground.copy(alpha = 0.2f) — aquí ?attr/textColor con alpha 0.2
+        binding.root.setBackgroundColor(
+            if (viewModel.isChapterSelected(card)) {
+                val tv = TypedValue()
+                binding.root.context.theme.resolveAttribute(R.attr.textColor, tv, true)
+                Color.argb(51, Color.red(tv.data), Color.green(tv.data), Color.blue(tv.data))
+            } else {
+                Color.TRANSPARENT
+            }
+        )
     }
 
     override fun onBindContent(holder: ViewHolderState<Any>, item: ChapterData, position: Int) {
@@ -52,15 +66,21 @@ class ChapterAdapter(val viewModel: ResultViewModel) :
             releaseDate.text = item.dateOfRelease
             releaseDate.isGone = item.dateOfRelease.isNullOrBlank()
             root.setOnClickListener {
-                viewModel.streamRead(item)
-                viewModel.isResume = true//to update read status
+                // Con selección activa, el toque alterna la selección (oficial:
+                // onClick = if (isSelectingChapters) toggleClick else Stream)
+                if (viewModel.isSelectingChapters) {
+                    viewModel.toggleChapterSelection(item)
+                } else {
+                    viewModel.streamRead(item)
+                    viewModel.isResume = true//to update read status
+                }
             }
+            // Pulsación larga = entrar en modo selección (oficial: onLongClick = toggleClick)
             root.setOnLongClickListener {
-                viewModel.setReadChapter(chapter = item, !viewModel.hasReadChapter(item))
-                refresh(binding, item, viewModel)
+                viewModel.toggleChapterSelection(item)
                 return@setOnLongClickListener true
             }
             refresh(binding, item, viewModel)
         }
     }
-}
\ No newline at end of file
+}
diff --git a/app/src/main/java/com/lagradost/quicknovel/ui/result/ResultFragment.kt b/app/src/main/java/com/lagradost/quicknovel/ui/result/ResultFragment.kt
index 062e56a..80a2858 100644
--- a/app/src/main/java/com/lagradost/quicknovel/ui/result/ResultFragment.kt
+++ b/app/src/main/java/com/lagradost/quicknovel/ui/result/ResultFragment.kt
@@ -12,6 +12,7 @@ import android.view.View
 import android.view.animation.DecelerateInterpolator
 import android.widget.LinearLayout
 import androidx.appcompat.app.AlertDialog
+import androidx.activity.OnBackPressedCallback
 import androidx.appcompat.widget.SearchView
 import androidx.coordinatorlayout.widget.CoordinatorLayout
 import androidx.core.view.doOnNextLayout
@@ -850,6 +851,7 @@ class ResultFragment : BaseFragment<FragmentResultBinding>(
         updateChapterChips(binding)
     }
 
+    @SuppressLint("NotifyDataSetChanged")
     override fun onBindingCreated(binding: FragmentResultBinding, savedInstanceState: Bundle?) {
         val url = savedInstanceState?.getString("url") ?: arguments?.getString("url")
         ?: throw NotImplementedError()
@@ -1106,6 +1108,72 @@ class ResultFragment : BaseFragment<FragmentResultBinding>(
             }
         })
 
+        // Selección de capítulos (pulsación larga) + barra de acciones, como el
+        // BottomBar oficial (ResultScreen.kt:229-369): cerrar, extender la selección
+        // arriba/abajo (solo con 1 capítulo), marcar leído/no leído y descargar.
+        val selectionCallback = object : OnBackPressedCallback(false) {
+            override fun handleOnBackPressed() {
+                viewModel.clearChapterSelection()
+            }
+        }
+        activity?.onBackPressedDispatcher?.addCallback(viewLifecycleOwner, selectionCallback)
+
+        observe(viewModel.chapterSelection) { selected ->
+            val sel = selected ?: emptySet()
+            (binding.chapterList.adapter as? ChapterAdapter)?.notifyDataSetChanged()
+            val chapters = viewModel.chapters.value.orEmpty()
+            val selectedChapters = chapters.filter { it.url in sel }
+            binding.chapterSelectionBar.isVisible = selectedChapters.isNotEmpty()
+            selectionCallback.isEnabled = selectedChapters.isNotEmpty()
+            if (selectedChapters.isEmpty()) return@observe
+            binding.chapterSelectionCount.text =
+                getString(R.string.selected_format, selectedChapters.size)
+            val readCount = selectedChapters.count { viewModel.hasReadChapter(it) }
+            binding.chapterSelectionRead.isVisible = readCount > 0
+            binding.chapterSelectionUnread.isVisible = readCount < selectedChapters.size
+            val pos =
+                if (selectedChapters.size == 1) chapters.indexOfFirst { it.url == selectedChapters.first().url } else -1
+            binding.chapterSelectionUp.isVisible = pos > 0
+            binding.chapterSelectionDown.isVisible = pos in 0 until chapters.lastIndex
+            binding.chapterSelectionDownload.isVisible =
+                viewModel.firstSelectedFullIndex() != null
+        }
+
+        binding.chapterSelectionClose.setOnClickListener {
+            viewModel.clearChapterSelection()
+        }
+        binding.chapterSelectionUp.setOnClickListener {
+            // oficial: BitSet().apply { set(0, index + 1) } — selecciona hasta aquí
+            val chapters = viewModel.chapters.value.orEmpty()
+            val sel = viewModel.chapterSelection.value.orEmpty()
+            val pos = chapters.indexOfFirst { it.url in sel }
+            if (pos > 0) {
+                viewModel.selectChapters(chapters.subList(0, pos + 1).map { it.url }.toSet())
+            }
+        }
+        binding.chapterSelectionDown.setOnClickListener {
+            // oficial: BitSet().apply { set(index, size) } — selecciona desde aquí al final
+            val chapters = viewModel.chapters.value.orEmpty()
+            val sel = viewModel.chapterSelection.value.orEmpty()
+            val pos = chapters.indexOfFirst { it.url in sel }
+            if (pos >= 0) {
+                viewModel.selectChapters(chapters.subList(pos, chapters.size).map { it.url }.toSet())
+            }
+        }
+        binding.chapterSelectionRead.setOnClickListener {
+            viewModel.markSelectionRead(true)
+        }
+        binding.chapterSelectionUnread.setOnClickListener {
+            viewModel.markSelectionRead(false)
+        }
+        binding.chapterSelectionDownload.setOnClickListener {
+            // El descargador del fork trabaja por rangos (no conjuntos arbitrarios
+            // como el de la oficial): descarga desde el primer capítulo seleccionado
+            // hasta el final — el «download_from_chapter» que el fork ya tiene.
+            viewModel.firstSelectedFullIndex()?.let { viewModel.downloadFrom(it) }
+            viewModel.clearChapterSelection()
+        }
+
         binding.chaptersFab.setOnClickListener { _ ->
             val act = activity ?: return@setOnClickListener
             val bottomSheetDialog = BottomSheetDialog(act)
diff --git a/app/src/main/java/com/lagradost/quicknovel/ui/result/ResultViewModel.kt b/app/src/main/java/com/lagradost/quicknovel/ui/result/ResultViewModel.kt
index ccc8f9a..d512215 100644
--- a/app/src/main/java/com/lagradost/quicknovel/ui/result/ResultViewModel.kt
+++ b/app/src/main/java/com/lagradost/quicknovel/ui/result/ResultViewModel.kt
@@ -240,6 +240,52 @@ class ResultViewModel : ViewModel() {
     val chapters: MutableLiveData<List<ChapterData>?> =
         MutableLiveData<List<ChapterData>?>()
 
+    /** Selección de capítulos (pulsación larga), como en la oficial:
+     *  ResultScreen.kt usa un BitSet; aquí un set de urls, estable ante reordenados. */
+    val chapterSelection: MutableLiveData<Set<String>> =
+        MutableLiveData<Set<String>>(emptySet())
+
+    val isSelectingChapters: Boolean
+        get() = chapterSelection.value?.isNotEmpty() == true
+
+    fun isChapterSelected(chapter: ChapterData): Boolean =
+        chapterSelection.value?.contains(chapter.url) == true
+
+    fun toggleChapterSelection(chapter: ChapterData) {
+        val cur = chapterSelection.value?.toMutableSet() ?: mutableSetOf()
+        if (!cur.add(chapter.url)) {
+            cur.remove(chapter.url)
+        }
+        chapterSelection.postValue(cur)
+    }
+
+    fun selectChapters(urls: Set<String>) {
+        chapterSelection.postValue(urls)
+    }
+
+    fun clearChapterSelection() {
+        if (isSelectingChapters) {
+            chapterSelection.postValue(emptySet())
+        }
+    }
+
+    /** Marca leídos/no leídos todos los capítulos seleccionados (oficial: MarkSelection). */
+    fun markSelectionRead(read: Boolean) {
+        val sel = chapterSelection.value ?: return
+        val list = chapters.value ?: return
+        list.filter { it.url in sel }.forEach { setReadChapter(it, read) }
+        chapterSelection.postValue(sel) // repinta los checks
+    }
+
+    /** Primer capítulo seleccionado en el orden COMPLETO de la novela; null si no
+     *  aplica (EPUB) o nada seleccionado. Sirve para downloadFrom(start). */
+    fun firstSelectedFullIndex(): Int? {
+        val sel = chapterSelection.value ?: return null
+        if (sel.isEmpty()) return null
+        val stream = (load as? StreamResponse) ?: return null
+        return stream.data.indexOfFirst { it.url in sel }.takeIf { it >= 0 }
+    }
+
     /** Se incrementa cuando termina de traducirse en segundo plano el nombre de los capítulos. */
     val chapterNamesChanged: MutableLiveData<Int> = MutableLiveData<Int>(0)
 
diff --git a/app/src/main/res/layout/fragment_downloads.xml b/app/src/main/res/layout/fragment_downloads.xml
index 4cb8e39..c992039 100644
--- a/app/src/main/res/layout/fragment_downloads.xml
+++ b/app/src/main/res/layout/fragment_downloads.xml
@@ -21,7 +21,7 @@
                 app:searchIcon="@drawable/search_icon"
                 app:searchHintIcon="@drawable/search_icon"
                 android:paddingStart="-10dp"
-                android:paddingEnd="92dp"
+                android:paddingEnd="44dp"
                 android:iconifiedByDefault="false"
                 app:queryHint="@string/search_downloads"
                 android:layout_width="match_parent"
@@ -29,33 +29,19 @@
                 android:layout_gravity="center_vertical"
                 tools:ignore="RtlSymmetry" />
 
-        <!-- Botones dentro de la barra (trailing icons), como en la app oficial
-             (DownloadScreen.kt trailingIcon): menú «+» e interruptor rejilla/lista.
-             El botón de continuar de las portadas (resumeReading) no se toca. -->
-        <LinearLayout
-                android:layout_width="wrap_content"
-                android:layout_height="wrap_content"
+        <!-- Botón «+» dentro de la barra (trailing icon), como en la app oficial
+             (DownloadScreen.kt trailingIcon): menú con importar libro y actualizar.
+             Sin interruptor rejilla/lista: no se pidió. El SearchView conserva su
+             comportamiento original del fork (filtrado en vivo, botón de borrar). -->
+        <ImageButton
+                android:id="@+id/download_import_btn"
+                android:layout_width="44dp"
+                android:layout_height="44dp"
                 android:layout_gravity="end|center_vertical"
-                android:orientation="horizontal">
-
-            <ImageButton
-                    android:id="@+id/download_import_btn"
-                    android:layout_width="44dp"
-                    android:layout_height="44dp"
-                    android:background="?attr/selectableItemBackgroundBorderless"
-                    android:contentDescription="@string/import_epub"
-                    android:src="@drawable/ic_baseline_add_24"
-                    app:tint="?attr/iconColor" />
-
-            <ImageButton
-                    android:id="@+id/download_layout_toggle"
-                    android:layout_width="44dp"
-                    android:layout_height="44dp"
-                    android:background="?attr/selectableItemBackgroundBorderless"
-                    android:contentDescription="@string/grid_view"
-                    android:src="@drawable/ic_baseline_grid_view_24"
-                    app:tint="?attr/iconColor" />
-        </LinearLayout>
+                android:background="?attr/selectableItemBackgroundBorderless"
+                android:contentDescription="@string/import_epub"
+                android:src="@drawable/ic_baseline_add_24"
+                app:tint="?attr/iconColor" />
     </FrameLayout>
 
     <!--<com.google.android.material.appbar.AppBarLayout
diff --git a/app/src/main/res/layout/fragment_result.xml b/app/src/main/res/layout/fragment_result.xml
index da22573..6bdd1c1 100644
--- a/app/src/main/res/layout/fragment_result.xml
+++ b/app/src/main/res/layout/fragment_result.xml
@@ -711,6 +711,90 @@ Translator: Rainbow Turtle" />
                                 android:paddingTop="5dp"
                                 app:layoutManager="androidx.recyclerview.widget.LinearLayoutManager"
                                 app:spanCount="1" />
+
+                            <!-- Barra de acciones sobre los capítulos seleccionados
+                                 (pulsación larga), como el BottomBar oficial
+                                 (ResultScreen.kt:229-369): cerrar, extender selección,
+                                 marcar leído/no leído y descargar. -->
+                            <LinearLayout
+                                android:id="@+id/chapter_selection_bar"
+                                android:layout_width="match_parent"
+                                android:layout_height="wrap_content"
+                                android:background="?attr/primaryBlackBackground"
+                                android:orientation="vertical"
+                                android:paddingHorizontal="10dp"
+                                android:paddingVertical="8dp"
+                                android:visibility="gone"
+                                tools:visibility="gone">
+
+                                <TextView
+                                    android:id="@+id/chapter_selection_count"
+                                    android:layout_width="match_parent"
+                                    android:layout_height="wrap_content"
+                                    android:textColor="?attr/textColor"
+                                    android:textSize="18sp"
+                                    android:textStyle="bold"
+                                    tools:text="Selected 3" />
+
+                                <LinearLayout
+                                    android:layout_width="match_parent"
+                                    android:layout_height="wrap_content"
+                                    android:gravity="center_vertical"
+                                    android:orientation="horizontal">
+
+                                    <ImageButton
+                                        android:id="@+id/chapter_selection_close"
+                                        android:layout_width="44dp"
+                                        android:layout_height="44dp"
+                                        android:background="?attr/selectableItemBackgroundBorderless"
+                                        android:contentDescription="@string/close"
+                                        android:src="@drawable/close_24px"
+                                        app:tint="?attr/iconColor" />
+
+                                    <ImageButton
+                                        android:id="@+id/chapter_selection_up"
+                                        android:layout_width="44dp"
+                                        android:layout_height="44dp"
+                                        android:background="?attr/selectableItemBackgroundBorderless"
+                                        android:src="@drawable/move_selection_up"
+                                        app:tint="?attr/iconColor" />
+
+                                    <ImageButton
+                                        android:id="@+id/chapter_selection_down"
+                                        android:layout_width="44dp"
+                                        android:layout_height="44dp"
+                                        android:background="?attr/selectableItemBackgroundBorderless"
+                                        android:src="@drawable/move_selection_down"
+                                        app:tint="?attr/iconColor" />
+
+                                    <ImageButton
+                                        android:id="@+id/chapter_selection_read"
+                                        android:layout_width="44dp"
+                                        android:layout_height="44dp"
+                                        android:background="?attr/selectableItemBackgroundBorderless"
+                                        android:contentDescription="@string/has_read"
+                                        android:src="@drawable/done_all"
+                                        app:tint="?attr/iconColor" />
+
+                                    <ImageButton
+                                        android:id="@+id/chapter_selection_unread"
+                                        android:layout_width="44dp"
+                                        android:layout_height="44dp"
+                                        android:background="?attr/selectableItemBackgroundBorderless"
+                                        android:contentDescription="@string/not_read"
+                                        android:src="@drawable/remove_done"
+                                        app:tint="?attr/iconColor" />
+
+                                    <ImageButton
+                                        android:id="@+id/chapter_selection_download"
+                                        android:layout_width="44dp"
+                                        android:layout_height="44dp"
+                                        android:background="?attr/selectableItemBackgroundBorderless"
+                                        android:contentDescription="@string/download"
+                                        android:src="@drawable/arrow_circle_down_24px"
+                                        app:tint="?attr/iconColor" />
+                                </LinearLayout>
+                            </LinearLayout>
                         </LinearLayout>
 
                         <LinearLayout
diff --git a/app/src/main/res/layout/simple_chapter.xml b/app/src/main/res/layout/simple_chapter.xml
index 20fd5ae..97a4845 100644
--- a/app/src/main/res/layout/simple_chapter.xml
+++ b/app/src/main/res/layout/simple_chapter.xml
@@ -1,42 +1,55 @@
 <?xml version="1.0" encoding="utf-8"?>
-<androidx.cardview.widget.CardView xmlns:android="http://schemas.android.com/apk/res/android"
-        xmlns:tools="http://schemas.android.com/tools"
+<!-- Ítem de capítulo como en la app oficial (ResultScreen.kt ChapterItem):
+     fila plana con icono de leído a la izquierda, nombre (máx. 2 líneas) y
+     fecha debajo; la selección se resalta desde ChapterAdapter. -->
+<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
         xmlns:app="http://schemas.android.com/apk/res-auto"
-        android:backgroundTint="?attr/primaryBlackBackground"
-        android:layout_marginBottom="5dp"
+        xmlns:tools="http://schemas.android.com/tools"
         android:layout_width="match_parent"
-        app:cardCornerRadius="0dp"
-        android:foreground="?android:attr/selectableItemBackgroundBorderless"
-        android:layout_height="50dp">
+        android:layout_height="wrap_content"
+        android:minHeight="48dp"
+        android:background="@android:color/transparent"
+        android:foreground="?android:attr/selectableItemBackground"
+        android:gravity="center_vertical"
+        android:orientation="horizontal"
+        android:paddingHorizontal="8dp"
+        android:paddingVertical="8dp">
+
+    <ImageView
+            android:id="@+id/chapter_read_check"
+            android:layout_width="20dp"
+            android:layout_height="20dp"
+            android:layout_marginEnd="8dp"
+            android:contentDescription="@string/has_read"
+            android:src="@drawable/check_24px"
+            app:tint="?attr/iconColor"
+            android:visibility="gone"
+            tools:ignore="ContentDescription" />
 
     <LinearLayout
-            android:layout_gravity="center"
-            android:gravity="center"
-            android:padding="5dp"
-            android:orientation="vertical"
-            android:layout_width="match_parent"
-            android:layout_height="match_parent">
+            android:layout_width="0dp"
+            android:layout_height="wrap_content"
+            android:layout_weight="1"
+            android:orientation="vertical">
 
         <TextView
-                android:ellipsize="marquee"
-                android:singleLine="true"
-                tools:textSize="16sp"
-                tools:textColor="?attr/textColor"
-                android:layout_gravity="center"
-                tools:text="Chapter 1"
                 android:id="@+id/name"
                 android:layout_width="match_parent"
-                android:layout_height="wrap_content" />
+                android:layout_height="wrap_content"
+                android:ellipsize="end"
+                android:maxLines="2"
+                android:textColor="?attr/textColor"
+                android:textSize="14sp"
+                tools:text="Chapter 1" />
 
         <TextView
-                tools:textSize="14sp"
-                tools:textColor="?attr/grayTextColor"
-                android:layout_gravity="center"
-                tools:text="Released 10h ago"
                 android:id="@+id/release_date"
                 android:layout_width="match_parent"
-                android:layout_height="wrap_content">
-
-        </TextView>
+                android:layout_height="wrap_content"
+                android:ellipsize="end"
+                android:maxLines="1"
+                android:textColor="?attr/grayTextColor"
+                android:textSize="12sp"
+                tools:text="Released 10h ago" />
     </LinearLayout>
-</androidx.cardview.widget.CardView>
\ No newline at end of file
+</LinearLayout>
diff --git a/app/src/main/res/values-es/strings.xml b/app/src/main/res/values-es/strings.xml
index 9c11224..c019c7f 100644
--- a/app/src/main/res/values-es/strings.xml
+++ b/app/src/main/res/values-es/strings.xml
@@ -111,6 +111,8 @@
     <string name="read_action_settings">Ajustes</string>
     <string name="read_action_chapters">Capítulos</string>
     <string name="read_action_chapters_formatted" formatted="true">%s Capítulos</string>
+    <string name="selected_format" formatted="true">Seleccionados: %d</string>
+    <string name="not_read">No leído</string>
     <string name="select_chapter">Seleccionar capítulo</string>
     <string name="search_chapters">Buscar capítulos…</string>
     <string name="reload_chapter">Recargar capítulo</string>
PATCH_EOF

if ! git apply --check --whitespace=nowarn "$DELTA" 2>/tmp/apply_err.txt; then
  echo "ERROR: el patch no encaja en tu árbol actual."
  echo "Tu árbol debe estar en el estado v11.4 (v10 + v11 + arreglos v11.1-v11.4)."
  echo "Detalle del rechazo:"
  cat /tmp/apply_err.txt
  exit 1
fi
git apply --whitespace=nowarn "$DELTA"
echo "Patch aplicado: 8 ficheros (selección de capítulos + barras)."

echo "PASO 3/3: guardando en un commit..."
git add -A
git commit -m "v12: seleccion de capitulos con pulsacion larga (menu oficial) + barras ajustadas" \
  || echo "(no había nada nuevo que guardar)"
echo
echo "====================================================================="
echo " LISTO. Compila de nuevo: Build > Make Project (o ./gradlew assembleDebug)"
echo " Los cambios son de interfaz: abre una ficha, mantén pulsado un capítulo."
echo "====================================================================="
