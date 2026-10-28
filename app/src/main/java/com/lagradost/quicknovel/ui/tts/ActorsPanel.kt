package com.lagradost.quicknovel.ui.tts

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.setKey
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.EPUB_ML_AI_API_KEY
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.TTS_BATCHING
import com.lagradost.quicknovel.TTS_CHAPTER_MODE
import com.lagradost.quicknovel.TTS_ENGINE
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.lagradost.quicknovel.util.SingleSelectionHelper.showDialog
import com.lagradost.quicknovel.util.UIHelper.colorFromAttribute
import com.lagradost.quicknovel.util.toPx
import com.lagradost.quicknovel.util.tts.GeminiSpeechClient
import com.lagradost.quicknovel.util.tts.GeminiTtsPlayer
import com.lagradost.quicknovel.util.tts.GeminiVoices
import com.lagradost.quicknovel.util.tts.NARRATOR_NAME
import com.lagradost.quicknovel.util.tts.TtsActors
import com.lagradost.quicknovel.util.tts.TtsAudioCache
import com.lagradost.quicknovel.util.tts.TtsQuota
import com.lagradost.quicknovel.util.tts.TtsUsage

/**
 * Pestaña "Actores": qué voz de Gemini interpreta a cada personaje (y al narrador) de una novela.
 * Se construye por código porque se usa en dos sitios (la ficha de la novela y los ajustes del
 * lector) y así no hay que duplicar el layout. Los datos son los de [TtsActors], clave = nombre
 * de la novela, igual que "Sustituir".
 */
class ActorsPanel(private val activity: Activity, private val novel: String) {
    private val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    private fun colorOf(attr: Int) = activity.colorFromAttribute(attr)

    fun build(): View {
        val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

        root.addView(TextView(activity).apply {
            setText(R.string.actors_description)
            setTextColor(colorOf(R.attr.grayTextColor))
            textSize = 13f
        })

        // guardar el audio: carpeta TTS/<novela> junto a los epub; si no, se borra a los 3 días
        val save = SwitchCompat(activity).apply {
            setText(R.string.actors_save_audio)
            setTextColor(colorOf(R.attr.textColor))
            isChecked = TtsActors.saveAudio(novel)
            setOnCheckedChangeListener { _, checked -> TtsActors.setSaveAudio(novel, checked) }
            minHeight = 48.toPx
        }
        root.addView(save, lp(top = 12))
        root.addView(TextView(activity).apply {
            text = activity.getString(R.string.actors_save_audio_help, "TTS/$novel")
            setTextColor(colorOf(R.attr.grayTextColor))
            textSize = 12f
        })

        // ahorro de cuota: varias frases por petición (se vuelve a cortar el audio por frases)
        root.addView(SwitchCompat(activity).apply {
            setText(R.string.actors_batching)
            setTextColor(colorOf(R.attr.textColor))
            isChecked = getKey<Boolean>(TTS_BATCHING) ?: true
            setOnCheckedChangeListener { _, checked -> setKey(TTS_BATCHING, checked) }
            minHeight = 48.toPx
        }, lp(top = 8))
        root.addView(TextView(activity).apply {
            text = activity.getString(R.string.actors_batching_help)
            setTextColor(colorOf(R.attr.grayTextColor))
            textSize = 12f
        })
        // generar todo el capítulo por actores (mínimas peticiones) o por ventanas (empieza antes)
        root.addView(SwitchCompat(activity).apply {
            setText(R.string.actors_chapter_mode)
            setTextColor(colorOf(R.attr.textColor))
            isChecked = getKey<Boolean>(TTS_CHAPTER_MODE) ?: true
            setOnCheckedChangeListener { _, checked -> setKey(TTS_CHAPTER_MODE, checked) }
            minHeight = 48.toPx
        }, lp(top = 8))
        root.addView(TextView(activity).apply {
            text = activity.getString(R.string.actors_chapter_mode_help)
            setTextColor(colorOf(R.attr.grayTextColor))
            textSize = 12f
        })
        root.addView(TextView(activity).apply {
            val left = TtsQuota.remainingToday()
            text = activity.getString(R.string.actors_usage_today, TtsUsage.today()) +
                    (if (left != null) " / ${TtsQuota.rpd()}" else "")
            setTextColor(colorOf(R.attr.textColor))
            textSize = 13f
        }, lp(top = 6))

        root.addView(
            com.google.android.material.button.MaterialButton(
                activity, null, com.google.android.material.R.attr.materialButtonStyle
            ).apply {
                setText(R.string.actors_quota)
                setOnClickListener { askQuota() }
            }, lp(top = 8)
        )

        root.addView(
            com.google.android.material.button.MaterialButton(
                activity, null, com.google.android.material.R.attr.materialButtonStyle
            ).apply {
                setText(R.string.actors_add)
                setOnClickListener { askNewActor() }
            }, lp(top = 12)
        )

        root.addView(list, lp(top = 10))
        render()
        return root
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = top.toPx }

    private fun render() {
        list.removeAllViews()
        for (actor in TtsActors.get(novel)) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.search_background)
                setPadding(16.toPx, 8.toPx, 6.toPx, 8.toPx)
                isClickable = true
                setOnClickListener { pickVoice(actor.name, actor.voice) }
            }
            val info = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            info.addView(TextView(activity).apply {
                text = actor.name
                setTextColor(colorOf(R.attr.textColor))
                textSize = 15f
            })
            info.addView(TextView(activity).apply {
                val auto = if (actor.auto) " · ${activity.getString(R.string.actors_auto)}" else ""
                text = GeminiVoices.label(actor.voice) + auto
                setTextColor(colorOf(R.attr.grayTextColor))
                textSize = 12f
            })
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(icon(R.drawable.ic_baseline_play_arrow_24, R.string.actors_preview) {
                preview(actor.voice)
            })
            if (actor.name != NARRATOR_NAME) {
                row.addView(icon(R.drawable.ic_baseline_delete_outline_24, R.string.replace_delete) {
                    TtsActors.remove(novel, actor.name)
                    render()
                })
            }
            list.addView(row, lp(top = 6))
        }
    }

    private fun icon(res: Int, description: Int, onClick: () -> Unit) = ImageView(activity).apply {
        setImageResource(res)
        contentDescription = activity.getString(description)
        setColorFilter(colorOf(R.attr.textColor))
        setPadding(8.toPx, 8.toPx, 8.toPx, 8.toPx)
        layoutParams = LinearLayout.LayoutParams(40.toPx, 40.toPx)
        setBackgroundResource(android.R.drawable.list_selector_background)
        setOnClickListener { onClick() }
    }

    /** Cuota del TTS (peticiones por minuto y por día), tal como la muestra AI Studio. */
    private fun askQuota() {
        fun field(hintRes: Int, value: Int) = EditText(activity).apply {
            setHint(hintRes)
            setText(value.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setSingleLine()
        }
        val rpm = field(R.string.actors_quota_rpm, TtsQuota.rpm())
        val rpd = field(R.string.actors_quota_rpd, TtsQuota.rpd())
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.toPx, 8.toPx, 24.toPx, 0)
            addView(TextView(activity).apply {
                setText(R.string.actors_quota_help)
                setTextColor(colorOf(R.attr.grayTextColor))
                textSize = 12f
            })
            addView(rpm)
            addView(rpd)
        }
        AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setTitle(R.string.actors_quota_title)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                TtsQuota.setLimits(
                    rpm.text.toString().toIntOrNull() ?: TtsQuota.DEFAULT_RPM,
                    rpd.text.toString().toIntOrNull() ?: TtsQuota.DEFAULT_RPD
                )
            }
            .show()
    }

    private fun askNewActor() {
        val input = EditText(activity).apply {
            setHint(R.string.actors_name_hint)
            setSingleLine()
        }
        val frame = FrameLayout(activity).apply {
            setPadding(24.toPx, 8.toPx, 24.toPx, 0)
            addView(input)
        }
        AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setTitle(R.string.actors_add)
            .setView(frame)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.actors_next) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val voice = TtsActors.get(novel).firstOrNull { it.name.equals(name, true) }?.voice
                    ?: GeminiVoices.suggest(name, null, TtsActors.get(novel).map { it.voice }.toSet())
                pickVoice(name, voice)
            }
            .show()
    }

    private fun pickVoice(name: String, current: String) {
        VoicePicker.show(activity, current) { voice ->
            TtsActors.setVoice(novel, name, voice)
            render()
        }
    }

    private fun preview(voice: String) {
        val key = getKey<String>(EPUB_ML_AI_API_KEY).orEmpty()
        if (key.isBlank()) {
            showToast(R.string.tts_gemini_no_key)
            return
        }
        val engine = getKey<String>(TTS_ENGINE).orEmpty()
        // la prueba usa el modelo elegido o, si se usa el TTS del sistema, el barato (Flash-Lite)
        val model = if (engine.startsWith("gemini")) engine else GeminiSpeechClient.MODEL_FLASH_LITE
        val player = GeminiTtsPlayer(java.io.File(activity.cacheDir, TtsAudioCache.DIR), novel, key, model)
        ioSafe {
            try {
                player.preview(voice, activity.getString(R.string.actors_preview_sample))
            } catch (t: Throwable) {
                showToast(t.message)
            } finally {
                player.release()
            }
        }
    }
}

/** Elegir voz: las 30 prediseñadas, la Biblioteca ampliada (búsqueda) o un id escrito a mano. */
object VoicePicker {
    fun show(activity: Activity, current: String, onPick: (String) -> Unit) {
        val prebuilt = GeminiVoices.prebuilt
        val items = prebuilt.map { GeminiVoices.label(it.id) } +
                activity.getString(R.string.actors_voice_library) +
                activity.getString(R.string.actors_voice_custom)
        activity.showDialog(
            items,
            prebuilt.indexOfFirst { it.id == current },
            activity.getString(R.string.actors_choose_voice),
            false,
            {}
        ) { index ->
            when {
                index in prebuilt.indices -> onPick(prebuilt[index].id)
                index == prebuilt.size -> searchLibrary(activity, onPick)
                else -> askId(activity, current, onPick)
            }
        }
    }

    private fun askId(activity: Activity, current: String, onPick: (String) -> Unit) {
        val input = EditText(activity).apply {
            setText(current)
            setHint(R.string.actors_voice_custom_hint)
            setSingleLine()
        }
        val frame = FrameLayout(activity).apply {
            setPadding(24.toPx, 8.toPx, 24.toPx, 0)
            addView(input)
        }
        AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setTitle(R.string.actors_voice_custom)
            .setView(frame)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.actors_next) { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onPick)
            }
            .show()
    }

    private fun searchLibrary(activity: Activity, onPick: (String) -> Unit) {
        val key = getKey<String>(EPUB_ML_AI_API_KEY).orEmpty()
        if (key.isBlank()) {
            showToast(R.string.tts_gemini_no_key)
            return
        }
        val input = EditText(activity).apply {
            setHint(R.string.actors_voice_search_hint)
            setSingleLine()
        }
        val frame = FrameLayout(activity).apply {
            setPadding(24.toPx, 8.toPx, 24.toPx, 0)
            addView(input)
        }
        AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setTitle(R.string.actors_voice_library)
            .setView(frame)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.actors_voice_search) { _, _ ->
                val query = input.text.toString().trim()
                ioSafe {
                    try {
                        val voices = GeminiSpeechClient.listVoices(key, query, null)
                        activity.runOnUiThread {
                            if (voices.isEmpty()) {
                                showToast(R.string.actors_voice_none)
                                return@runOnUiThread
                            }
                            activity.showDialog(
                                voices.map {
                                    listOf(it.name, it.language, it.gender)
                                        .filter { p -> p.isNotBlank() }.joinToString(" · ")
                                },
                                -1,
                                activity.getString(R.string.actors_voice_library),
                                false,
                                {}
                            ) { i -> voices.getOrNull(i)?.let { onPick(it.id) } }
                        }
                    } catch (t: Throwable) {
                        showToast(t.message)
                    }
                }
            }
            .show()
    }
}
