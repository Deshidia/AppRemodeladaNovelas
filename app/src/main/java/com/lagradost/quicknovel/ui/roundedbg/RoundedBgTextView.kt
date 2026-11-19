/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lagradost.quicknovel.ui.roundedbg

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Annotation
import android.text.Layout
import android.text.Spanned
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withTranslation
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.util.toPx
import kotlin.math.max
import kotlin.math.min

/** Valor de la [Annotation] que marca el subrayado del usuario (ver ReaderMark). */
const val USER_MARK_ANNOTATION = "usermark"

/**
 * A TextView that can draw rounded background to the portions of the text. See
 * [TextRoundedBgHelper] for more information.
 *
 * See [TextRoundedBgAttributeReader] for supported attributes.
 */
class RoundedBgTextView// PREVENT OVERLAP, SUE ME
@JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private val textRoundedBgHelper: TextRoundedBgHelper

    // Subrayado del usuario: una línea gruesa bajo el texto + un tinte suave detrás
    private val userMarkColor = ContextCompat.getColor(context, R.color.readerUserMark)
    private val userMarkLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = userMarkColor
        style = Paint.Style.FILL
    }
    private val userMarkTintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(userMarkColor, 0x33)
        style = Paint.Style.FILL
    }
    private val userMarkLineThickness = 2.5f.toPx
    private val userMarkLineOffset = 2f.toPx

    init {
        val attributeReader = TextRoundedBgAttributeReader(context, attrs)
        textRoundedBgHelper = TextRoundedBgHelper(
            horizontalPadding = attributeReader.horizontalPadding,
            verticalPadding = attributeReader.verticalPadding - (75.toPx / 100), // PREVENT OVERLAP, SUE ME
            drawable = attributeReader.drawable,
            drawableLeft = attributeReader.drawableLeft,
            drawableMid = attributeReader.drawableMid,
            drawableRight = attributeReader.drawableRight
        )
    }

    override fun onDraw(canvas: Canvas) {
        // need to draw bg first so that text can be on top during super.onDraw()
        if (text is Spanned && layout != null) {
            canvas.withTranslation(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat()) {
                textRoundedBgHelper.draw(canvas, text as Spanned, layout)
                drawUserMark(canvas, text as Spanned, layout)
            }
        }
        super.onDraw(canvas)
    }

    /**
     * Dibuja el subrayado del usuario (si este TextView contiene la [Annotation]
     * [USER_MARK_ANNOTATION]) línea a línea, así que funciona igual con frases partidas en varias
     * líneas. Como el resaltado TTS, se pinta antes que el texto para quedar por debajo.
     */
    private fun drawUserMark(canvas: Canvas, text: Spanned, layout: Layout) {
        val spans = text.getSpans(0, text.length, Annotation::class.java)
        for (span in spans) {
            if (span.value != USER_MARK_ANNOTATION) continue
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            if (start < 0 || end <= start) continue

            val firstLine = layout.getLineForOffset(start)
            // end es exclusivo: con end - 1 evitamos una línea vacía extra si acaba justo al salto
            val lastLine = layout.getLineForOffset(end - 1)
            for (line in firstLine..lastLine) {
                val from = max(start, layout.getLineStart(line))
                // getLineVisibleEnd ignora los espacios/saltos finales de la línea
                val to = min(end, layout.getLineVisibleEnd(line))
                if (to <= from) continue

                val x1 = layout.getPrimaryHorizontal(from)
                val x2 = layout.getPrimaryHorizontal(to)
                val left = min(x1, x2)
                val right = max(x1, x2)

                canvas.drawRect(
                    left,
                    layout.getLineTopWithoutPadding(line).toFloat(),
                    right,
                    layout.getLineBottomWithoutPadding(line).toFloat(),
                    userMarkTintPaint
                )
                val underlineTop = layout.getLineBaseline(line) + userMarkLineOffset
                canvas.drawRect(
                    left,
                    underlineTop,
                    right,
                    underlineTop + userMarkLineThickness,
                    userMarkLinePaint
                )
            }
        }
    }
}