package com.lagradost.quicknovel

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.Voice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.animation.doOnEnd
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.children
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.Slider
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import com.lagradost.quicknovel.CommonActivity.showToast
import com.lagradost.quicknovel.DataStore.getKey
import com.lagradost.quicknovel.TTSNotifications.TTS_NOTIFICATION_ID
import com.lagradost.quicknovel.databinding.ChapterSelectDialogSearchBinding
import com.lagradost.quicknovel.databinding.ColorRoundCheckmarkBinding
import com.lagradost.quicknovel.databinding.ReadBottomSettingsBinding
import com.lagradost.quicknovel.databinding.ReadMainBinding
import com.lagradost.quicknovel.databinding.SingleOverscrollChapterBinding
import com.lagradost.quicknovel.mvvm.Resource
import com.lagradost.quicknovel.mvvm.observe
import com.lagradost.quicknovel.mvvm.observeNullable
import com.lagradost.quicknovel.ui.CONFIG_COLOR
import com.lagradost.quicknovel.ui.CONFIG_FONT
import com.lagradost.quicknovel.ui.CONFIG_FONT_BOLD
import com.lagradost.quicknovel.ui.OrientationType
import com.lagradost.quicknovel.ui.ReadingType
import com.lagradost.quicknovel.ui.ScrollIndex
import com.lagradost.quicknovel.ui.ScrollVisibilityIndex
import com.lagradost.quicknovel.ui.ScrollVisibilityItem
import com.lagradost.quicknovel.ui.TextAdapter
import com.lagradost.quicknovel.ui.TextConfig
import com.lagradost.quicknovel.ui.TextVisualLine
import com.lagradost.quicknovel.ui.ViewHolderState
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.lagradost.quicknovel.util.SingleSelectionHelper.showDialog
import com.lagradost.quicknovel.util.UIHelper.colorFromAttribute
import com.lagradost.quicknovel.util.UIHelper.fixPaddingStatusbar
import com.lagradost.quicknovel.util.UIHelper.getStatusBarHeight
import com.lagradost.quicknovel.util.UIHelper.popupMenu
import com.lagradost.quicknovel.util.UIHelper.systemFonts
import com.lagradost.quicknovel.util.divCeil
import com.lagradost.quicknovel.util.toPx
import java.lang.Integer.max
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.properties.Delegates
import com.google.android.material.tabs.TabLayout
import androidx.core.widget.doAfterTextChanged

class ReadActivity2 : AppCompatActivity(), ColorPickerDialogListener {
    companion object {
        private var _readActivity: WeakReference<ReadActivity2>? = null
        var readActivity
            get() = _readActivity?.get()
            private set(value) {
                _readActivity = WeakReference(value)
            }
    }

    private fun hideSystemUI() {

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.readerContainer).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        fun lowerBottomNav(v: View) {
            v.translationY = 0f
            ObjectAnimator.ofFloat(v, "translationY", v.height.toFloat()).apply {
                duration = 200
                start()
            }.doOnEnd {
                v.isVisible = false
            }
        }

        lowerBottomNav(binding.readerBottomViewHolder)

        binding.readToolbarHolder.translationY = 0f
        ObjectAnimator.ofFloat(
            binding.readToolbarHolder,
            "translationY",
            -binding.readToolbarHolder.height.toFloat()
        ).apply {
            duration = 200
            start()
        }.doOnEnd {
            binding.readToolbarHolder.isVisible = false
        }
    }

    private fun showSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(
            window,
            binding.readerContainer
        ).show(WindowInsetsCompat.Type.systemBars())

        binding.readToolbarHolder.isVisible = true

        fun higherBottomNavView(v: View) {
            v.isVisible = true
            v.translationY = v.height.toFloat()
            ObjectAnimator.ofFloat(v, "translationY", 0f).apply {
                duration = 200
                start()
            }
        }

        higherBottomNavView(binding.readerBottomViewHolder)

        binding.readToolbarHolder.translationY = -binding.readToolbarHolder.height.toFloat()

        ObjectAnimator.ofFloat(binding.readToolbarHolder, "translationY", 0f).apply {
            duration = 200
            start()
        }
    }

    lateinit var binding: ReadMainBinding
    private val viewModel: ReadActivityViewModel by viewModels()

    /** Lets other screens (e.g. the library's mini-player) reach this reader's TTS controls
     * without holding a direct/strong reference to the Activity itself. */
    fun activeViewModel(): ReadActivityViewModel? = if (this.isDestroyed) null else viewModel

    private var _imageHolder: WeakReference<LinearLayout>? = null
    var imageHolder
        get() = _imageHolder?.get()
        set(value) {
            _imageHolder = WeakReference(value)
        }

    override fun onColorSelected(dialog: Int, color: Int) {
        when (dialog) {
            0 -> setBackgroundColor(color)
            1 -> setTextColor(color)
        }
    }

    private fun setBackgroundColor(color: Int) {
        viewModel.backgroundColor = color
    }

    private fun setTextColor(color: Int) {
        viewModel.textColor = color
    }

    override fun onDialogDismissed(dialog: Int) {
        updateImages()
    }

    private fun updateImages() {
        val bgColors = resources.getIntArray(R.array.readerBgColors)
        val textColors = resources.getIntArray(R.array.readerTextColors)
        val color = viewModel.backgroundColor
        val colorPrimary = colorFromAttribute(R.attr.colorPrimary)
        val colorPrim = ColorStateList.valueOf(colorPrimary)
        val colorTrans = ColorStateList.valueOf(Color.TRANSPARENT)
        var foundCurrentColor = false
        val fullAlpha = 200
        val fadedAlpha = 50

        for ((index, imgHolder) in imageHolder?.children?.withIndex() ?: return) {
            val img = imgHolder.findViewById<ImageView>(R.id.image1) ?: return

            if (index == bgColors.size) { // CUSTOM COLOR
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    img.foregroundTintList = colorPrim
                    img.foreground = ContextCompat.getDrawable(
                        this,
                        if (foundCurrentColor) R.drawable.ic_baseline_add_24 else R.drawable.ic_baseline_check_24
                    )
                }
                img.imageAlpha = if (foundCurrentColor) fadedAlpha else fullAlpha
                img.backgroundTintList =
                    ColorStateList.valueOf(if (foundCurrentColor) Color.parseColor("#161616") else color)
                continue
            }

            if ((color == bgColors[index] && viewModel.textColor == textColors[index])) {
                foundCurrentColor = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    img.foregroundTintList = colorPrim
                }
                img.imageAlpha = fullAlpha
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    img.foregroundTintList = colorTrans
                }
                img.imageAlpha = fadedAlpha
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            exitReader()
            return true
        }
        if ((keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && keyCode != KeyEvent.KEYCODE_VOLUME_UP)) return false

        // if we have the bottom bar up then we ignore the override functionality
        if (viewModel.bottomVisibility.isInitialized && viewModel.bottomVisibility.value == true) return false

        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (viewModel.isTTSRunning()) {
                    viewModel.forwardsTTS()
                    return true
                } else if (viewModel.scrollWithVolume) {
                    val bottomY = getBottomY()
                    val lines = getAllLines()
                    val line = lines.firstOrNull {
                        it.bottom >= bottomY
                    } ?: lines.lastOrNull() ?: return true
                    binding.realText.scrollBy(0, line.top - getTopY())

                    return true
                }
            }

            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (viewModel.isTTSRunning()) {
                    viewModel.backwardsTTS()
                    return true
                } else if (viewModel.scrollWithVolume) {
                    binding.realText.scrollBy(0, getTopY() - getBottomY())
                    binding.realText.post {
                        val lines = getAllLines()
                        val topY = getTopY()
                        val line = lines.firstOrNull {
                            it.top >= topY
                        } ?: return@post
                        binding.realText.scrollBy(0, line.top - getTopY())
                    }

                    return true
                }
            }
        }

        return false
    }

    private fun kill() {
        with(NotificationManagerCompat.from(this)) { // KILLS NOTIFICATION
            cancel(TTS_NOTIFICATION_ID)
        }
        finish()
    }

    /** Leaves the reader screen. While TTS is playing or paused we don't want to kill it just
     * because the user wants to browse the library, so instead of finishing this Activity (which
     * would tear down the TTS session) we bring the library back to the front and leave this
     * Activity alive in the background, still running TTS. If TTS isn't active this behaves
     * exactly like before: close the reader. */
    private fun exitReader() {
        if (viewModel.currentTTSStatus != TTSHelper.TTSStatus.IsStopped) {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
            )
        } else {
            kill()
        }
    }

    val mBatInfoReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctxt: Context?, intent: Intent) {
            val batteryPct: Float = run {
                val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                level * 100 / scale.toFloat()
            }
            binding.readBattery.text =
                binding.readBattery.context?.getString(R.string.battery_format)
                    ?.format(batteryPct.toInt())
        }
    }

    private fun registerBattery() {
        this.registerReceiver(mBatInfoReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    }

    fun parseAction(input: TTSHelper.TTSActionType): Boolean {
        return viewModel.parseAction(input)
    }

    private lateinit var textAdapter: TextAdapter
    private lateinit var textLayoutManager: LinearLayoutManager

    private fun transformIndexToScrollVisibilityItem(adapterPosition: Int): ScrollVisibilityItem {
        return ScrollVisibilityItem(
            adapterPosition = adapterPosition,
            viewHolder = binding.realText.findViewHolderForAdapterPosition(adapterPosition),
        )
    }

    private fun getTopY(): Int {
        val outLocation = IntArray(2)
        binding.readTopItem.getLocationInWindow(outLocation)
        val (_, topY) = outLocation
        return topY + binding.realText.paddingTop
    }

    private fun getBottomY(): Int {
        val outLocation = IntArray(2)
        binding.readBottomItem.getLocationInWindow(outLocation)
        val (_, bottomY) = outLocation
        return bottomY - max(
            binding.realText.paddingBottom,
            if (viewModel.showTime || viewModel.showBattery) binding.readOverlay.height else 0
        )
    }

    /**

    ________________
    [ hello ]
    -- screen cut --
    [ world ]
    [ ! ]
    [ From kotlin ]
    ________________

    here the first index of "world" would be stored as it is the first whole line visible,
    while "hello" would be stored as the first invisible line, this is used to scroll the exact char
    you are on. so while rotating you would rotate to the first line that contains "world"

    This is also used for TTS because TTS *must* start at the first visible whole sentence,
    so in this case it would start at "From kotlin" because hello is not visible.
     */

    private fun getAllLines(): ArrayList<TextVisualLine> {
        val lines: ArrayList<TextVisualLine> = arrayListOf()

        for (i in textLayoutManager.findFirstVisibleItemPosition()..textLayoutManager.findLastVisibleItemPosition()) {
            lines.addAll(textAdapter.getLines(transformIndexToScrollVisibilityItem(i)))
        }
        return lines
    }

    private fun postLines(lines: ArrayList<TextVisualLine>) {
        if (lines.isEmpty()) {
            return
        }

        val topY = getTopY()
        val bottomY = getBottomY()

        viewModel.onScroll(
            ScrollVisibilityIndex(
                firstInMemory = lines.first(),
                lastInMemory = lines.last(),
                firstFullyVisible = lines.firstOrNull {
                    it.top >= topY
                },
                firstFullyVisibleUnderLine = lines.firstOrNull {
                    it.top >= topBarHeight
                },
                lastHalfVisible = lines.firstOrNull {
                    it.bottom >= bottomY
                },
            )
        )
    }

    fun onScroll() {
        postLines(getAllLines())
    }

    private var cachedChapter: List<SpanDisplay> = emptyList()
    private fun scrollToDesired() {
        val desired: ScrollIndex = viewModel.desiredIndex ?: return
        val adapterPosition =
            cachedChapter.indexOfFirst { display -> display.index == desired.index && display.innerIndex == desired.innerIndex }
        if (adapterPosition == -1) return

        //val offset = 7.toPx
        textLayoutManager.scrollToPositionWithOffset(adapterPosition, 1)

        // don't inner-seek if zero because that is chapter break
        if (desired.innerIndex == 0) return

        binding.realText.post {
            getAllLines().also { postLines(it) }.firstOrNull { line ->
                line.index == desired.index && line.endChar >= desired.char
            }?.let { line ->
                //binding.tmpTtsStart2.fixLine(line.top)
                //binding.tmpTtsEnd2.fixLine(line.bottom)
                binding.realText.scrollBy(0, line.top - getTopY())
            }
        }

        /*desired.firstVisibleChar?.let { visible ->
                binding.realText.post {
                    binding.realText.scrollBy(
                        0,
                        (textAdapter.getViewOffset(
                            transformIndexToScrollVisibilityItem(adapterPosition),
                            visible
                        ) ?: 0) + offset
                    )
                }
            }*/

    }

    private fun View.fixLine(offset: Int) {
        // this.setPadding(0, 200, 0, 0)
        val layoutParams =
            this.layoutParams as FrameLayout.LayoutParams// FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,offset)
        layoutParams.setMargins(0, offset, 0, 0)
        this.layoutParams = layoutParams
    }

    var lockTop: Int? = null
    var lockBottom: Int? = null
    var currentScroll: Int = 0

    private fun updateTTSLine(line: TTSHelper.TTSLine?, depth: Int = 0) {
        // update the visual component
        /*println("LINE: ${line?.speakOutMsg} =>")
        line?.speakOutMsg?.codePoints()?.forEachOrdered {
            println(">>" + String(intArrayOf(it), 0, 1) + "|" + it.toString())
        }*/

        textAdapter.updateTTSLine(line)
        val first = textLayoutManager.findFirstVisibleItemPosition()
        val last = textLayoutManager.findLastVisibleItemPosition()
        textAdapter.notifyItemRangeChanged(first, last + 1 - first)
        /*for (position in textLayoutManager.findFirstVisibleItemPosition()..textLayoutManager.findLastVisibleItemPosition()) {
            val viewHolder = binding.realText.findViewHolderForAdapterPosition(position)
            if (viewHolder !is TextAdapter.TextAdapterHolder) continue
            viewHolder.updateTTSLine(line)
        }*/

        // update the lock area
        if (line == null || !viewModel.ttsLock) {
            lockTop = null
            lockBottom = null
            return
        }

        val lines = getAllLines()
        postLines(lines)

        val top = lines.firstOrNull { it.index == line.index && it.endChar > line.startChar }
        val bottom =
            lines.firstOrNull { it.index == line.index && it.startChar <= line.endChar && line.endChar <= it.endChar }

        if (top == null || bottom == null) {
            lockTop = null
            lockBottom = null

            // this should never happened as tts line must be valid
            val innerIndex = viewModel.innerCharToIndex(line.index, line.startChar) ?: return

            // scroll to the top of that line, first search the adapter
            val adapterPosition =
                cachedChapter.indexOfFirst { display -> display.index == line.index && display.innerIndex == innerIndex }

            // if we tts out of bounds somehow? we scroll to that and refresh everything
            if (adapterPosition == -1) {
                viewModel.scrollToDesired(
                    ScrollIndex(
                        index = line.index,
                        innerIndex = innerIndex,
                        line.startChar
                    )
                )
                return
            }

            if (depth < 3) {
                textLayoutManager.scrollToPositionWithOffset(adapterPosition, 1)
                textLayoutManager.postOnAnimation {
                    updateTTSLine(line, depth = depth + 1)
                }
            }

            return
        }

        val topScroll = top.top - getTopY()
        lockTop = currentScroll + topScroll
        val bottomScroll =
            bottom.bottom - getBottomY()
        lockBottom = currentScroll + bottomScroll

        // binding.tmpTtsStart.fixLine(top.top)
        //binding.tmpTtsEnd.fixLine(bottom.bottom)

        // we have reached the end, scroll to the top
        if (bottomScroll > 0) {
            binding.realText.scrollBy(0, topScroll)
        }
        // we have scrolled up while being on top
        else if (topScroll < 0) {
            binding.realText.scrollBy(0, topScroll)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // we save this just in case the user fucks it up somehow
        postDesired(binding.realText)
        super.onConfigurationChanged(newConfig)
    }

    private fun updateOtherTextConfig(config: TextConfig) {
        config.setArgs(binding.loadingText, CONFIG_FONT or CONFIG_COLOR)
        config.setArgs(binding.readBattery, CONFIG_FONT or CONFIG_COLOR or CONFIG_FONT_BOLD)
        config.setArgs(binding.readTimeClock, CONFIG_FONT or CONFIG_COLOR or CONFIG_FONT_BOLD)
        config.setArgs(binding.readLoadingBar)
    }

    private fun updatePadding() {
        val h = viewModel.paddingHorizontal.toPx
        val v = viewModel.paddingVertical.toPx
        binding.realText.apply {
            if (paddingLeft == h && paddingRight == h && paddingBottom == v && paddingTop == v) return
            setPadding(
                h,
                v,
                h,
                v
            )
            scrollToDesired()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun updateTextAdapterConfig() {
        // this did not work so I just rebind everything, it does not happend often so idc
        textAdapter.notifyDataSetChanged()
        updateOtherTextConfig(textAdapter.config)
        /* binding.realText.apply {
             for (idx in 0..childCount) {//textLayoutManager.findFirstVisibleItemPosition()..textLayoutManager.findLastVisibleItemPosition()) {
                 val viewHolder = getChildViewHolder(getChildAt(idx) ?: continue) ?: continue
                 if (viewHolder !is TextAdapter.TextAdapterHolder) continue
                 viewHolder.setConfig(textAdapter.config)
             }
         }*/
    }

    private fun postDesired(view: View) {
        val currentDesired = viewModel.desiredIndex
        view.post {
            viewModel.desiredIndex = currentDesired
            scrollToDesired()
            updateTTSLine(viewModel.ttsLine.value)
        }
    }

    override fun onResume() {
        viewModel.resumedApp()
        super.onResume()
    }

    override fun onPause() {
        viewModel.leftApp()
        super.onPause()
    }

    /*private fun pendingPost() {
        binding.readToolbarHolder.post {
            val height = binding.readToolbarHolder.height
            // height cant be 0
            if(height == 0) {
                pendingPost()
                return@post
            }

            if(textAdapter.changeHeight(binding.readToolbarHolder.height + getStatusBarHeight())) {
                updateTextAdapterConfig()
            }
        }
    }*/
    private fun showFonts() {
        val builder =
            AlertDialog.Builder(this, R.style.AlertDialogCustom).setView(R.layout.font_bottom_sheet)

        val dialog = builder.create()
        dialog.show()

        val res = dialog.findViewById<RecyclerView>(R.id.sort_click)!!

        val fonts = systemFonts
        val fontsList = listOf(FontFile(null)) + fonts.map { FontFile(it) }

        val currentName = getKey(EPUB_FONT) ?: ""
        val storingIndex = fontsList.indexOfFirst { (it.file?.name ?: "") == currentName }

        val adapter = FontAdapter(this, storingIndex) { file ->
            viewModel.textFont = file.file?.name ?: ""
            dialog.dismiss()
        }
        res.adapter = adapter
        adapter.submitIncomparableList(fontsList)
        res.scrollToPosition(storingIndex)
    }

    /*  private fun updateTimeText() {
          val string = if (viewModel.time12H) "hh:mm a" else "HH:mm"

          val currentTime: String = SimpleDateFormat(string, Locale.getDefault()).format(Date())

          binding.readTime.text = currentTime
          binding.readTime.postDelayed({ -> updateTimeText() }, 1000)
      }*/
    private var topBarHeight by Delegates.notNull<Int>()


    private var currentOverScrollValue = 0.0f

    private fun setProgressOfOverscroll(index: Int, progress: Float) {
        val id = generateId(5, index, 0, 0)
        ((binding.realText.findViewHolderForItemId(id) as? ViewHolderState<*>)?.view as? SingleOverscrollChapterBinding)?.let {
            it.progress.max = 10000
            it.progress.progress = (progress.absoluteValue * 10000.0f).toInt()
            it.progress.alpha = if (progress.absoluteValue > 0.05f) 1.0f else 0.0f
        }
    }

    private var currentOverScroll: Float
        get() = currentOverScrollValue
        set(value) {
            currentOverScrollValue = if (viewModel.readerType != ReadingType.OVERSCROLL_SCROLL) {
                0.0f
            } else {
                val setTo = value.coerceIn(-1.0f, 1.0f)
                if (setTo == 0.0f) {
                    setProgressOfOverscroll(viewModel.currentIndex + 1, setTo)
                    setProgressOfOverscroll(viewModel.currentIndex - 1, setTo)
                    if (currentOverScrollValue > 0.9) {
                        viewModel.seekToChapter(viewModel.currentIndex - 1)
                    } else if (currentOverScrollValue < -0.9) {
                        viewModel.seekToChapter(viewModel.currentIndex + 1)
                    }
                } else {
                    setProgressOfOverscroll(
                        viewModel.currentIndex + if (setTo < 0.0f) 1 else -1,
                        setTo
                    )
                }
                setTo
            }
            // binding.realText.alpha = (1.0f - currentOverScrollValue.absoluteValue)


            //  val nextId = generateId(5, viewModel.currentIndex+1,0,0)
            //  val prevId = generateId(5, viewModel.currentIndex-1,0,0)


            // binding.realText.translationY =
            //     overscrollMaxTranslation * currentOverScrollValue //alpha = (1.0f - currentOverScrollValue.absoluteValue)
        }

    override fun onDestroy() {
        // si se sale del lector con el TTS activo, la frase actual queda subrayada (progreso)
        viewModel.saveTTSProgressAsUserMark()
        viewModel.stopTTS()
        this.unregisterReceiver(mBatInfoReceiver)
        if (readActivity === this) {
            readActivity = null
        }
        super.onDestroy()
    }

    fun Slider.setValueRounded(value: Float) {
        this.value = (value.coerceIn(this.valueFrom, this.valueTo) / this.stepSize).roundToInt()
            .toFloat() * this.stepSize
    }


    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        CommonActivity.loadThemes(this)
        super.onCreate(savedInstanceState)
        readActivity = this
        binding = ReadMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        registerBattery()

        onBackPressedDispatcher.addCallback(this) {
            exitReader()
        }

        viewModel.init(intent, this)
        topBarHeight = binding.readToolbarHolder.minimumHeight + getStatusBarHeight()
        binding.readToolbarHolder.minimumHeight = topBarHeight
        textAdapter = TextAdapter(
            viewModel,
            TextConfig(
                toolbarHeight = topBarHeight,
                defaultFont = binding.readText.typeface,
                textColor = viewModel.textColor,
                textSize = viewModel.textSize,
                textFont = viewModel.textFont,
                backgroundColor = viewModel.backgroundColor,
                bionicReading = viewModel.bionicReading,
                isTextSelectable = viewModel.isTextSelectable,
                verticalPadding = viewModel.textVerticalPadding,
            ).also { config ->
                updateOtherTextConfig(config)
            }
        ).apply {
            setHasStableIds(true)
        }

        binding.readToolbar.apply {
            setNavigationIcon(R.drawable.ic_baseline_arrow_back_24)
            setNavigationOnClickListener {
                exitReader()
            }
            setOnLongClickListener {
                viewModel.copyChapterToClipboard(viewModel.currentIndex)
                true
            }
            inflateMenu(R.menu.read_toolbar_menu)
            setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.action_toggle_original_text) {
                    viewModel.toggleShowOriginalText()
                    true
                } else {
                    false
                }
            }
        }

        observe(viewModel.showOriginalTextLive) { showOriginal ->
            binding.readToolbar.menu.findItem(R.id.action_toggle_original_text)?.apply {
                setIcon(if (showOriginal) R.drawable.translate_24px else R.drawable.ic_baseline_language_24)
                setTitle(if (showOriginal) R.string.show_translated_text else R.string.show_original_text)
            }
        }

        //updateTimeText()
        fixPaddingStatusbar(binding.readToolbarHolder)

        observe(viewModel.paddingHorizontalLive) {
            updatePadding()
        }

        observe(viewModel.paddingVerticalLive) {
            updatePadding()
        }


        //observe(viewModel.time12HLive) { time12H ->
        //    binding.readTimeClock.is24HourModeEnabled = !time12H
        //}

        observe(viewModel.backgroundColorLive) { color ->
            binding.root.setBackgroundColor(color)
            binding.readOverlay.setBackgroundColor(color)
            if (textAdapter.changeBackgroundColor(color)) {
                updateTextAdapterConfig()
            }
        }

        observe(viewModel.textVerticalPaddingLive) { padding ->
            if (textAdapter.changeTextVerticalPadding(padding)) {
                updateTextAdapterConfig()
            }
        }


        observe(viewModel.bionicReadingLive) { color ->
            if (textAdapter.changeBionicReading(color)) {
                updateTextAdapterConfig()
            }
        }
        observe(viewModel.isTextSelectableLive) { isTextSelectable ->
            if (textAdapter.changeTextSelectable(isTextSelectable)) {
                updateTextAdapterConfig()
            }
        }

        observe(viewModel.showBatteryLive) { show ->
            binding.readBattery.isVisible = show
            binding.readOverlay.isVisible = show && viewModel.showTime
        }

        observe(viewModel.showTimeLive) { show ->
            binding.readTimeClock.isVisible = show
            binding.readOverlay.isVisible = show && viewModel.showBattery
        }

        observe(viewModel.screenAwakeLive) { awake ->
            if (awake)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        observe(viewModel.textSizeLive) { size ->
            if (textAdapter.changeSize(size)) {
                updateTextAdapterConfig()
                postDesired(binding.realText)
            }
        }

        observe(viewModel.textColorLive) { color ->
            if (textAdapter.changeColor(color)) {
                updateTextAdapterConfig()
            }
        }

        observe(viewModel.textFontLive) { font ->
            if (textAdapter.changeFont(font)) {
                updateTextAdapterConfig()
            }
        }

        textLayoutManager = LinearLayoutManager(binding.realText.context)

        binding.ttsActionPausePlay.setOnClickListener {
            viewModel.pausePlayTTS()
        }

        binding.ttsActionStop.setOnClickListener {
            viewModel.stopTTS()
        }

        binding.readActionTts.setOnClickListener {
            //scrollToDesired()
            viewModel.startTTS()
        }

        binding.ttsActionForward.setOnClickListener {
            viewModel.forwardsTTS()
        }

        binding.ttsActionBack.setOnClickListener {
            viewModel.backwardsTTS()
        }

        binding.readActionPrevChapter.setOnClickListener {
            viewModel.prevChapter()
        }

        binding.readActionNextChapter.setOnClickListener {
            viewModel.nextChapter()
        }

        observe(viewModel.orientationLive) { position ->
            val org = OrientationType.fromSpinner(position)
            requestedOrientation = org.flag
            binding.readActionRotate.setImageResource(org.iconRes)

            binding.readActionRotate.apply {
                setOnClickListener {
                    popupMenu(
                        items = OrientationType.entries.map { it.prefValue to it.stringRes },
                        selectedItemId = org.prefValue
                    ) {
                        viewModel.orientation = itemId
                    }
                }
            }
        }

        observeNullable(viewModel.ttsLine) { line ->
            updateTTSLine(line)
        }

        // El subrayado del usuario cambió (nuevo, sustituido, quitado, o borrado al terminar de
        // leerlo con el TTS). Se invalida TODO el adapter y no solo lo visible: los holders que
        // están en la caché del RecyclerView se reutilizan sin volver a hacer bind y, si no, al
        // volver a hacer scroll aparecería un subrayado ya borrado. Solo se re-enlaza lo que se
        // ve; el resto queda marcado como sucio hasta que haga falta.
        observeNullable(viewModel.userMark) {
            textAdapter.notifyItemRangeChanged(0, textAdapter.itemCount)
        }

        observe(viewModel.title) { title ->
            binding.readToolbar.title = title
        }

        observe(viewModel.chapterTile) { title ->
            binding.readToolbar.subtitle = title.asString(binding.readToolbar.context)
        }

        observe(viewModel.chaptersTitles) { titles ->
            binding.readActionChapters.setOnClickListener {
                val builderSingle: AlertDialog.Builder = AlertDialog.Builder(this)
                //builderSingle.setIcon(R.drawable.ic_launcher)
                val currentChapter = viewModel.desiredIndex?.index
                // cant be too safe here
                val validChapter =
                    currentChapter != null && currentChapter >= 0 && currentChapter < titles.size
                if (validChapter) {
                    builderSingle.setTitle(titles[currentChapter].asString(this)) //  "Select Chapter"
                } else {
                    builderSingle.setTitle(R.string.select_chapter)
                }

                val dialogBinding = ChapterSelectDialogSearchBinding.inflate(layoutInflater)
                // altura fija para que el dialogo no cambie de tamano al filtrar
                dialogBinding.chapterSearchRoot.layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (resources.displayMetrics.heightPixels * 0.6f).toInt()
                    )

                val allTitles = titles.map { it.asString(this) }
                // posiciones (indice real del capitulo) que se muestran ahora mismo en la lista
                var shownIndices: List<Int> = allTitles.indices.toList()

                val arrayAdapter = ArrayAdapter<String>(this, R.layout.chapter_select_dialog)
                arrayAdapter.addAll(allTitles)
                dialogBinding.chapterSearchList.adapter = arrayAdapter

                builderSingle.setView(dialogBinding.root)
                builderSingle.setNegativeButton(R.string.cancel) { dialog, _ -> dialog.dismiss() }

                val dialog = builderSingle.create()

                fun markCurrentChapter() {
                    val list = dialogBinding.chapterSearchList
                    list.clearChoices()
                    val pos = if (validChapter) shownIndices.indexOf(currentChapter) else -1
                    if (pos >= 0) {
                        list.setItemChecked(pos, true)
                    }
                    arrayAdapter.notifyDataSetChanged()
                }

                dialogBinding.chapterSearchList.setOnItemClickListener { _, _, position, _ ->
                    val realIndex = shownIndices.getOrNull(position) ?: return@setOnItemClickListener
                    dialog.dismiss()
                    viewModel.seekToChapter(realIndex)
                }

                dialogBinding.chapterSearchBar.setOnQueryTextListener(object :
                    SearchView.OnQueryTextListener {
                    private fun filter(query: String?) {
                        val q = query?.trim().orEmpty()
                        shownIndices = if (q.isEmpty()) {
                            allTitles.indices.toList()
                        } else {
                            allTitles.indices.filter { allTitles[it].contains(q, ignoreCase = true) }
                        }
                        arrayAdapter.clear()
                        arrayAdapter.addAll(shownIndices.map { allTitles[it] })
                        markCurrentChapter()
                    }

                    override fun onQueryTextSubmit(query: String?): Boolean {
                        filter(query)
                        dialogBinding.chapterSearchBar.clearFocus()
                        return true
                    }

                    override fun onQueryTextChange(newText: String?): Boolean {
                        filter(newText)
                        return true
                    }
                })

                // que el buscador siga visible al abrir el teclado, sin abrirlo automaticamente
                dialog.window?.setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
                )
                dialog.show()
                dialogBinding.chapterSearchBar.clearFocus()

                markCurrentChapter()
                if (validChapter) {
                    dialogBinding.chapterSearchList.setSelection(currentChapter)
                }
            }
        }

        /*binding.readToolbar.setOnMenuItemClickListener {
            TimePickerDialog(
                binding.readToolbar.context,
                { _, hourOfDay, minute -> println("TIME PICKED: $hourOfDay , $minute") },
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
                Calendar.getInstance().get(Calendar.MINUTE),
                true
            )
            true
        }*/

        observe(viewModel.ttsStatus) { status ->
            val isTTSRunning = status != TTSHelper.TTSStatus.IsStopped

            /*if (isTTSRunning) {
                binding.readToolbar.inflateMenu(R.menu.sleep_timer)
            } else {
                binding.readToolbar.menu.clear()
            }*/

            binding.readerBottomView.isGone = isTTSRunning
            binding.readerBottomViewTts.isVisible = isTTSRunning
            binding.ttsActionPausePlay.setImageResource(
                when (status) {
                    TTSHelper.TTSStatus.IsPaused -> R.drawable.ic_baseline_play_arrow_24
                    TTSHelper.TTSStatus.IsRunning -> R.drawable.ic_baseline_pause_24
                    TTSHelper.TTSStatus.IsStopped -> R.drawable.ic_baseline_play_arrow_24
                }
            )
        }

        binding.apply {
            realText.setOnClickListener {
                viewModel.switchVisibility()
            }
            readToolbar.setOnClickListener {
                viewModel.switchVisibility()
            }
            readerLinContainer.setOnClickListener {
                viewModel.switchVisibility()
            }
        }

        observe(viewModel.bottomVisibility) { visibility ->
            if (visibility) {
                showSystemUI()
                // here we actually do not want to fix the tts bug, as it will cause a bad behavior
                // when the text is very low
            } else {
                hideSystemUI()
                // otherwise we have a shitty bug with tts locking range
                binding.root.post {
                    updateTTSLine(viewModel.ttsLine.value)
                }
            }
        }

        var last: Resource<Boolean> = Resource.Loading() // very dirty
        observe(viewModel.loadingStatus) { loading ->
            val different = last != loading
            last = loading
            when (loading) {
                is Resource.Success -> {
                    binding.readLoading.isVisible = false
                    binding.readFail.isVisible = false

                    binding.readNormalLayout.isVisible = true

                    if (different) {
                        binding.readNormalLayout.alpha = 0.01f

                        ObjectAnimator.ofFloat(binding.readNormalLayout, "alpha", 1f).apply {
                            duration = 300
                            start()
                        }
                    } else {
                        binding.readNormalLayout.alpha = 1.0f
                    }
                }

                is Resource.Loading -> {
                    binding.readNormalLayout.isVisible = false
                    binding.readFail.isVisible = false
                    binding.readLoading.isVisible = true
                    binding.loadingText.apply {
                        isGone = loading.url.isNullOrBlank()
                        text = loading.url ?: ""
                    }
                }

                is Resource.Failure -> {
                    binding.readLoading.isVisible = false
                    binding.readFail.isVisible = true
                    binding.failText.text = loading.errorString
                    binding.readNormalLayout.isVisible = false
                }
            }
        }

        binding.realText.apply {
            layoutManager = textLayoutManager
            adapter = textAdapter
            itemAnimator = null
            // testing overscroll
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_MOVE -> {
                        if (event.historySize <= 1) return@setOnTouchListener false
                        val startY = event.getHistoricalY(0, event.historySize - 1)
                        val endY = event.getY(0)
                        val dy = (endY - startY).div(Resources.getSystem().displayMetrics.density)
                            .coerceIn(-1.5f, 1.5f)
                        // if cant scroll in the direction then translate Y with the dy
                        val translated = !canScrollVertically(-1) || !canScrollVertically(1)
                        if (translated) {
                            // * (maxScrollOver - currentOverScroll.absoluteValue))
                            currentOverScroll += dy * 0.1f
                        }

                        // if we can scroll down then we cant translate down
                        if (canScrollVertically(1) && currentOverScroll < 0.0f) {
                            currentOverScroll = 0.0f
                            return@setOnTouchListener false
                        }

                        // if we can scroll up then we cant translate up
                        if (canScrollVertically(-1) && currentOverScroll > 0.0f) {
                            currentOverScroll = 0.0f
                            return@setOnTouchListener false
                        }

                        return@setOnTouchListener false
                    }

                    MotionEvent.ACTION_UP -> {
                        currentOverScroll = 0.0f
                    }

                    else -> {}
                }
                return@setOnTouchListener false
            }


            addOnScrollListener(object :
                RecyclerView.OnScrollListener() {
                var updateFromCode = false
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (dy != 0 && !updateFromCode) {
                        var rdy = dy

                        lockTop?.let { lock ->
                            if (currentScroll + rdy > lock) {
                                rdy = lock - currentScroll
                                fling(0, 0)
                            }
                        }

                        lockBottom?.let { lock ->
                            if (currentScroll + rdy < lock) {
                                rdy = lock - currentScroll
                                fling(0, 0)
                            }
                        }

                        if (currentOverScroll < 0.0f && rdy < 0) {
                            rdy = 0
                        } else if (currentOverScroll > 0.0f && rdy > 0) {
                            rdy = 0
                        }
                        /*println("currentOverScrollTranslation=$currentOverScrollTranslation rdy=$rdy")
                        val dscroll = minOf(currentOverScrollTranslation.absoluteValue.toInt(), rdy.absoluteValue)
                        if(currentOverScrollTranslation < 0 && rdy < 0) {
                            currentOverScrollTranslation += dscroll
                            rdy += dscroll
                        }
                        if(currentOverScrollTranslation > 0 && rdy > 0) {
                            currentOverScrollTranslation -= dscroll
                            rdy -= dscroll
                        }*/

                        currentScroll += dy
                        val delta = rdy - dy
                        if (delta != 0 && canScrollVertically(delta)) {
                            //updateFromCode = true
                            scrollBy(0, delta)
                        }
                    } else {
                        updateFromCode = false
                        onScroll()
                    }

                    super.onScrolled(recyclerView, dx, dy)

                    // binding.tmpTtsEnd.fixLine((getBottomY()- remainingBottom) + 7.toPx)
                    // binding.tmpTtsStart.fixLine(remainingTop + 7.toPx)

                }

                //this is to reduce onScroll calls
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    super.onScrollStateChanged(recyclerView, newState)
                    if(newState == RecyclerView.SCROLL_STATE_SETTLING || newState == RecyclerView.SCROLL_STATE_IDLE){
                        onScroll()
                    }
                }
            })

        }

        //here inserted novel chapter text into recyclerview
        observe(viewModel.chapter) { chapter ->
            cachedChapter = chapter.data

            if (chapter.seekToDesired) {
                textAdapter.submitIncomparableList(chapter.data) {
                    viewModel._loadingStatus.postValue(Resource.Success(true))
                    scrollToDesired()
                    onScroll()
                }
            } else {
                textAdapter.submitList(chapter.data) {
                    viewModel._loadingStatus.postValue(Resource.Success(true))
                    onScroll()
                }
            }
        }

        observeNullable(viewModel.ttsTimeRemaining) { time ->
            if (time == null) {
                binding.ttsStopTime.isVisible = false
            } else {
                binding.ttsStopTime.isVisible = true
                binding.ttsStopTime.text =
                    binding.ttsStopTime.context.getString(R.string.sleep_format_stop)
                        .format(time.divCeil(60_000L))
            }
        }

        binding.readActionSettings.setOnClickListener {
            val bottomSheetDialog = BottomSheetDialog(this)

            val bindingSettings = ReadBottomSettingsBinding.inflate(layoutInflater, null, false)
            bottomSheetDialog.setContentView(bindingSettings.root)

            //Create and Order tabs
            val tabsToViews = listOf(
                R.string.read_display_settings to bindingSettings.tabSettingsContainer,     // Tab 0 — Settings
                R.string.tab_translation to bindingSettings.tabTranslationContainer,  // Tab 1 — Translation
                R.string.tts_voice to bindingSettings.tabVoiceContainer,        // Tab 2 — Voice
                R.string.read_text_size to bindingSettings.tabTextContainer,         // Tab 3 — Text
            )
            tabsToViews.forEachIndexed { index, (resId, view) ->
                bindingSettings.readSettingsTabs.addTab(bindingSettings.readSettingsTabs.newTab().setText(resId))
                view.isVisible = index == 0
            }

            // Show specific tab
            bindingSettings.readSettingsTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    tabsToViews.forEachIndexed { index, (_, view) ->
                        view.isVisible = index == tab.position
                    }
                }
                override fun onTabUnselected(tab: TabLayout.Tab) {}
                override fun onTabReselected(tab: TabLayout.Tab) {}
            })

            bindingSettings.readReadingType.setText(viewModel.readerType.stringRes)
            bindingSettings.readReadingType.setOnLongClickListener {
                it.popupMenu(items = listOf(1 to R.string.reset_value), selectedItemId = null) {
                    if (itemId == 1) {
                        bindingSettings.readReadingType.setText(ReadingType.DEFAULT.stringRes)
                        viewModel.readerType = ReadingType.DEFAULT
                    }
                }
                return@setOnLongClickListener true
            }
            bindingSettings.readReadingType.setOnClickListener {
                it.popupMenu(
                    items = ReadingType.entries.map { v -> v.prefValue to v.stringRes },
                    selectedItemId = viewModel.readerType.prefValue
                ) {
                    val set = ReadingType.fromSpinner(itemId)
                    bindingSettings.readReadingType.setText(set.stringRes)
                    viewModel.readerType = set
                }
            }

            bindingSettings.readSettingsTextSizeText.setOnClickListener {
                it.popupMenu(items = listOf(1 to R.string.reset_value), selectedItemId = null) {
                    if (itemId == 1) {
                        viewModel.textSize = DEF_FONT_SIZE
                        bindingSettings.readSettingsTextSize.setValueRounded(
                            DEF_FONT_SIZE.toFloat()
                        )
                    }
                }
            }

            bindingSettings.readSettingsTextSize.apply {
                valueTo = 30.0f
                valueFrom = 10.0f
                setValueRounded((viewModel.textSize).toFloat())
                addOnChangeListener { slider, value, fromUser ->
                    viewModel.textSize = value.roundToInt()
                }
            }

            bindingSettings.readSettingsTtsPitchText.setOnClickListener {
                it.popupMenu(
                    items = listOf(1 to R.string.reset_value),
                    selectedItemId = null
                ) {
                    if (itemId == 1) {
                        viewModel.ttsPitch = 1.0f
                        bindingSettings.readSettingsTtsPitch.setValueRounded(viewModel.ttsPitch)
                    }
                }
            }

            bindingSettings.readSettingsTtsSpeedText.setOnClickListener {
                it.popupMenu(
                    items = listOf(1 to R.string.reset_value),
                    selectedItemId = null
                ) {
                    if (itemId == 1) {
                        viewModel.ttsSpeed = 1.0f
                        bindingSettings.readSettingsTtsSpeed.setValueRounded(viewModel.ttsSpeed)
                    }
                }
            }

            bindingSettings.readSettingsTextVerticalPaddingText.setOnClickListener {
                it.popupMenu(
                    items = listOf(1 to R.string.reset_value),
                    selectedItemId = null
                ) {
                    if (itemId == 1) {
                        viewModel.textVerticalPadding = 7.5f
                        bindingSettings.readSettingsTextVerticalPadding.setValueRounded(viewModel.textVerticalPadding)
                    }
                }
            }

            bindingSettings.readSettingsTtsPitch.apply {
                setValueRounded(viewModel.ttsPitch)
                addOnChangeListener { slider, value, fromUser ->
                    viewModel.ttsPitch = value
                }
            }

            bindingSettings.readSettingsTtsSpeed.apply {
                setValueRounded(viewModel.ttsSpeed)
                addOnChangeListener { slider, value, fromUser ->
                    viewModel.ttsSpeed = value
                }
            }

            bindingSettings.readSettingsTextPaddingText.setOnClickListener {
                it.popupMenu(
                    items = listOf(1 to R.string.reset_value),
                    selectedItemId = null
                ) {
                    if (itemId == 1) {
                        viewModel.paddingHorizontal = DEF_HORIZONTAL_PAD
                        bindingSettings.readSettingsTextPadding.setValueRounded(DEF_HORIZONTAL_PAD.toFloat())
                    }
                }
            }

            bindingSettings.readSettingsTextPaddingTextTop.setOnClickListener {
                it.popupMenu(
                    items = listOf(1 to R.string.reset_value),
                    selectedItemId = null
                ) {
                    if (itemId == 1) {
                        viewModel.paddingVertical = DEF_VERTICAL_PAD
                        bindingSettings.readSettingsTextPaddingTop.setValueRounded(DEF_VERTICAL_PAD.toFloat())
                    }
                }
            }

            bindingSettings.readSettingsTextPadding.apply {
                valueTo = 50.0f
                setValueRounded(viewModel.paddingHorizontal.toFloat())
                addOnChangeListener { slider, value, fromUser ->
                    viewModel.paddingHorizontal = value.roundToInt()
                }
            }

            bindingSettings.readSettingsTextPaddingTop.apply {
                valueTo = 50.0f
                setValueRounded(viewModel.paddingVertical.toFloat())
                addOnChangeListener { slider, value, fromUser ->
                    viewModel.paddingVertical = value.roundToInt()
                }
            }

            bindingSettings.readSettingsTextVerticalPadding.apply {
                setValueRounded(viewModel.textVerticalPadding)
                addOnChangeListener { slider, value, fromUser ->
                    viewModel.textVerticalPadding = value
                }
            }


            bindingSettings.readShowFonts.apply {
                //text = UIHelper.parseFontFileName(getKey(EPUB_FONT))
                setOnClickListener {
                    showFonts()
                }
            }

            bindingSettings.readSettingsTextFontText.setOnClickListener {
                it.popupMenu(items = listOf(1 to R.string.reset_value), selectedItemId = null) {
                    if (itemId == 1) {
                        viewModel.textFont = ""
                    }
                }
            }

            bindingSettings.readMlTo.setOnClickListener { view ->
                if (view == null) return@setOnClickListener
                val context = view.context

                val items = ReadActivityViewModel.MLSettings.list

                context.showDialog(
                    items.map {
                        it.second
                    },
                    items.map { it.first }.indexOf(viewModel.mlToLanguage),
                    context.getString(R.string.sleep_timer), false, {}
                ) { index ->
                    viewModel.mlToLanguage = items[index].first
                    bindingSettings.readMlTo.text =
                        ReadActivityViewModel.MLSettings.fromShortToDisplay(viewModel.mlToLanguage)
                }
            }

            bindingSettings.readMlFrom.setOnClickListener { view ->
                if (view == null) return@setOnClickListener
                val context = view.context

                val items = ReadActivityViewModel.MLSettings.list

                context.showDialog(
                    items.map {
                        it.second
                    },
                    items.map { it.first }.indexOf(viewModel.mlFromLanguage),
                    context.getString(R.string.sleep_timer), false, {}
                ) { index ->
                    viewModel.mlFromLanguage = items[index].first
                    bindingSettings.readMlFrom.text =
                        ReadActivityViewModel.MLSettings.fromShortToDisplay(viewModel.mlFromLanguage)
                }
            }

            // The three translation engines (Google online, DeepL, Gemini AI) are mutually
            // exclusive: checking one unchecks the other two.
            bindingSettings.readOnlineTranslationSwitch.isChecked = viewModel.mlUseOnlineTransaltion
            bindingSettings.readOnlineTranslationSwitch.setOnCheckedChangeListener { _, isChecked ->
                viewModel.mlUseOnlineTransaltion = isChecked
                if (isChecked) {
                    bindingSettings.readDeeplTranslationSwitch.isChecked = false
                    bindingSettings.readAiTranslationSwitch.isChecked = false
                }
            }

            // The API key field is only relevant (and only shown) while DeepL is selected.
            fun updateDeepLKeyVisibility(visible: Boolean) {
                val visibility = if (visible) View.VISIBLE else View.GONE
                bindingSettings.readDeeplApiKeyLayout.visibility = visibility
                bindingSettings.readDeeplApiKeyHint.visibility = visibility
            }

            bindingSettings.readDeeplTranslationSwitch.isChecked = viewModel.mlUseDeepLTranslation
            updateDeepLKeyVisibility(viewModel.mlUseDeepLTranslation)
            bindingSettings.readDeeplTranslationSwitch.setOnCheckedChangeListener { _, isChecked ->
                viewModel.mlUseDeepLTranslation = isChecked
                updateDeepLKeyVisibility(isChecked)
                if (isChecked) {
                    bindingSettings.readOnlineTranslationSwitch.isChecked = false
                    bindingSettings.readAiTranslationSwitch.isChecked = false
                }
            }

            bindingSettings.readDeeplApiKey.setText(viewModel.mlDeepLApiKey)
            bindingSettings.readDeeplApiKey.doAfterTextChanged { s ->
                viewModel.mlDeepLApiKey = s?.toString()?.trim() ?: ""
            }

            bindingSettings.readAiTranslationSwitch.isChecked = viewModel.mlUseAiTranslation
            bindingSettings.readAiTranslationSwitch.setOnCheckedChangeListener { _, isChecked ->
                viewModel.mlUseAiTranslation = isChecked
                if (isChecked) {
                    bindingSettings.readOnlineTranslationSwitch.isChecked = false
                    bindingSettings.readDeeplTranslationSwitch.isChecked = false
                }
            }

            bindingSettings.readAiApiKey.setText(viewModel.mlAiApiKey)
            bindingSettings.readAiApiKey.doAfterTextChanged { s ->
                viewModel.mlAiApiKey = s?.toString() ?: ""
            }

            bindingSettings.readAiModel.setText(viewModel.mlAiModel)
            bindingSettings.readAiModel.doAfterTextChanged { s ->
                viewModel.mlAiModel = s?.toString() ?: ""
            }

            bindingSettings.readAiBackupModel.setText(viewModel.mlAiBackupModel)
            bindingSettings.readAiBackupModel.doAfterTextChanged { s ->
                viewModel.mlAiBackupModel = s?.toString() ?: ""
            }

            bindingSettings.readAiPrompt.setText(viewModel.mlAiPrompt)
            bindingSettings.readAiPrompt.doAfterTextChanged { s ->
                viewModel.mlAiPrompt = s?.toString() ?: ""
            }

            bindingSettings.readAiMaxChars.setText(viewModel.mlAiMaxCharsPerRequest.toString())
            bindingSettings.readAiMaxChars.doAfterTextChanged { s ->
                val value = s?.toString()?.toIntOrNull()
                if (value != null && value > 0) {
                    viewModel.mlAiMaxCharsPerRequest = value
                }
            }

            bindingSettings.readAiMaxRpmText.text =
                getString(R.string.ai_max_rpm_format, viewModel.mlAiMaxRequestsPerMinute)
            bindingSettings.readAiMaxRpm.apply {
                setValueRounded(viewModel.mlAiMaxRequestsPerMinute.toFloat())
                addOnChangeListener { _, value, _ ->
                    viewModel.mlAiMaxRequestsPerMinute = value.roundToInt()
                    bindingSettings.readAiMaxRpmText.text =
                        getString(R.string.ai_max_rpm_format, value.roundToInt())
                }
            }

            bindingSettings.readAiThinkingLevel.text = getString(
                GeminiThinkingLevel.list.first { it.first.name == viewModel.mlAiThinkingLevel }.second
            )
            bindingSettings.readAiThinkingLevel.setOnClickListener { view ->
                if (view == null) return@setOnClickListener
                val context = view.context
                val items = GeminiThinkingLevel.list

                context.showDialog(
                    items.map { context.getString(it.second) },
                    items.map { it.first.name }.indexOf(viewModel.mlAiThinkingLevel),
                    context.getString(R.string.ai_thinking_level_label), false, {}
                ) { index ->
                    viewModel.mlAiThinkingLevel = items[index].first.name
                    bindingSettings.readAiThinkingLevel.text = context.getString(items[index].second)
                }
            }

            bindingSettings.readAiTemperatureText.text =
                getString(R.string.ai_temperature_format, viewModel.mlAiTemperature)
            bindingSettings.readAiTemperature.apply {
                setValueRounded(viewModel.mlAiTemperature)
                addOnChangeListener { _, value, _ ->
                    viewModel.mlAiTemperature = value
                    bindingSettings.readAiTemperatureText.text =
                        getString(R.string.ai_temperature_format, value)
                }
            }

            bindingSettings.readAiTopPText.text =
                getString(R.string.ai_top_p_format, viewModel.mlAiTopP)
            bindingSettings.readAiTopP.apply {
                setValueRounded(viewModel.mlAiTopP)
                addOnChangeListener { _, value, _ ->
                    viewModel.mlAiTopP = value
                    bindingSettings.readAiTopPText.text =
                        getString(R.string.ai_top_p_format, value)
                }
            }

            bindingSettings.readAiTopKText.text =
                getString(R.string.ai_top_k_format, viewModel.mlAiTopK)
            bindingSettings.readAiTopK.apply {
                setValueRounded(viewModel.mlAiTopK.toFloat())
                addOnChangeListener { _, value, _ ->
                    viewModel.mlAiTopK = value.roundToInt()
                    bindingSettings.readAiTopKText.text =
                        getString(R.string.ai_top_k_format, value.roundToInt())
                }
            }

            bindingSettings.readApplyTranslation.setOnClickListener { view ->
                if (view == null) return@setOnClickListener
                ioSafe {
                    try {
                        if (!viewModel.requireMLDownload()) {
                            viewModel.applyMLSettings(true)
                            runOnUiThread { bottomSheetDialog.dismiss() }

                            return@ioSafe
                        }
                        runOnUiThread {
                            val builder: AlertDialog.Builder =
                                AlertDialog.Builder(view.context, R.style.AlertDialogCustom)
                            builder.setTitle(R.string.download_ml)
                            builder.setMessage(R.string.download_ml_long)
                            builder.setPositiveButton(R.string.download) { _, _ ->
                                viewModel.applyMLSettings(true)
                                bottomSheetDialog.dismiss()
                            }
                            builder.setCancelable(true)
                            builder.setNegativeButton(R.string.cancel) { _, _ -> }
                            builder.show()
                        }
                    } catch (t: Throwable) {
                        showToast(t.message ?: t.toString())
                    }
                }
            }

            // Long-press "Aplicar" to force a fresh AI translation of the current chapter,
            // bypassing the cache. Useful when a previous attempt partially failed
            // (timeout / model overloaded) and left a mix of translated and original text cached.
            bindingSettings.readApplyTranslation.setOnLongClickListener { view ->
                if (view == null) return@setOnLongClickListener false
                if (!viewModel.mlUseAiTranslation && !viewModel.mlUseDeepLTranslation) return@setOnLongClickListener false
                showToast(R.string.retranslating_chapter)
                viewModel.reTranslateChapter(viewModel.currentIndex)
                bottomSheetDialog.dismiss()
                true
            }

            bindingSettings.readMlTo.text =
                ReadActivityViewModel.MLSettings.fromShortToDisplay(viewModel.mlToLanguage)
            bindingSettings.readMlFrom.text =
                ReadActivityViewModel.MLSettings.fromShortToDisplay(viewModel.mlFromLanguage)

            val mlSettings = viewModel.mlSettings

            if (mlSettings.isInvalid()) {
                bindingSettings.readMlTitle.setText(R.string.google_translate)
            } else {
                bindingSettings.readMlTitle.text =
                    "${bindingSettings.readMlTitle.context.getString(R.string.google_translate)} (${mlSettings.fromDisplay} -> ${mlSettings.toDisplay})"
            }

            bindingSettings.readLanguage.setOnClickListener { _ ->
                ioSafe {
                    val tts = viewModel.ttsSession?.requireTTS() ?: return@ioSafe

                    runOnUiThread {
                        val languages = mutableListOf<Locale?>(null).apply {
                            addAll(tts.availableLanguages?.filterNotNull() ?: emptySet())
                        }
                        val ctx = bindingSettings.readLanguage.context ?: return@runOnUiThread
                        ctx.showDialog(
                            languages.map {
                                it?.displayName ?: ctx.getString(R.string.default_text)
                            },
                            languages.indexOf(tts.voice?.locale),
                            ctx.getString(R.string.tts_locale), false, {}
                        ) { index ->
                            viewModel.setTTSLanguage(languages.getOrNull(index))
                        }
                    }
                }
            }

            bindingSettings.readLanguage.setOnLongClickListener {
                it.popupMenu(items = listOf(1 to R.string.reset_value), selectedItemId = null) {
                    if (itemId == 1) {
                        viewModel.setTTSLanguage(null)
                    }
                }

                return@setOnLongClickListener true
            }

            bindingSettings.readVoice.setOnLongClickListener {
                it.popupMenu(items = listOf(1 to R.string.reset_value), selectedItemId = null) {
                    if (itemId == 1) {
                        viewModel.setTTSVoice(null)
                    }
                }

                return@setOnLongClickListener true
            }

            bindingSettings.readSleepTimer.setOnClickListener { view ->
                if (view == null) return@setOnClickListener
                val context = view.context

                val items =
                    mutableListOf(
                        context.getString(R.string.default_text) to 0L,
                    )

                for (i in 1L..120L) {
                    items.add(
                        context.getString(R.string.sleep_format).format(i.toInt()) to (i * 60000L)
                    )
                }

                context.showDialog(
                    items.map {
                        it.first
                    },
                    items.map { it.second }.indexOf(viewModel.ttsTimer),
                    context.getString(R.string.sleep_timer), false, {}
                ) { index ->
                    viewModel.ttsTimer = items[index].second
                }
            }

            bindingSettings.readVoice.setOnClickListener {
                ioSafe {
                    val tts = viewModel.ttsSession?.requireTTS() ?: return@ioSafe

                    runOnUiThread {
                        val matchAgainst = tts.voice.locale
                        val ctx = bindingSettings.readLanguage.context ?: return@runOnUiThread
                        val voices =
                            mutableListOf<Pair<String, Voice?>>(ctx.getString(R.string.default_text) to null).apply {
                                val voicesFiltered =
                                    tts.voices.filter { it != null && it.locale == matchAgainst }
                                        .map {
                                            // ${"★".repeat(it.quality / 100) }
                                            ("${it.name} ${
                                                if (it.isNetworkConnectionRequired) {
                                                    "(☁)"
                                                } else {
                                                    ""
                                                }
                                            }") to it
                                        }

                                addAll(voicesFiltered.sortedBy { (name, _) -> name })
                            }

                        ctx.showDialog(
                            voices.map { it.first },
                            voices.map { it.second }.indexOf(tts.voice),
                            ctx.getString(R.string.tts_locale), false, {}
                        ) { index ->
                            val voice = voices.getOrNull(index)?.second
                            viewModel.setTTSVoice(voice)
                        }
                    }
                }
            }

            bindingSettings.apply {
                hardResetStream.isVisible = viewModel.canReload()
                hardResetStream.setOnClickListener {
                    showToast(getString(R.string.reload_chapter_format).format(""))
                    viewModel.reloadChapter()
                }

                readSettingsScrollVol.isChecked = viewModel.scrollWithVolume
                readSettingsScrollVol.isChecked = viewModel.scrollWithVolume
                readSettingsScrollVol.setOnCheckedChangeListener { _, isChecked ->
                    viewModel.scrollWithVolume = isChecked
                }

                readSettingsAuthorNotes.isChecked = viewModel.authorNotes
                readSettingsAuthorNotes.setOnCheckedChangeListener { _, isChecked ->
                    viewModel.authorNotes = isChecked
                    viewModel.refreshChapters()
                }

                readSettingsShowBionic.isChecked = viewModel.bionicReading
                readSettingsShowBionic.setOnCheckedChangeListener { _, isChecked ->
                    viewModel.bionicReading = isChecked
                }

                readSettingsIsTextSelectable.isChecked = viewModel.isTextSelectable
                readSettingsIsTextSelectable.setOnCheckedChangeListener { _, isChecked ->
                    viewModel.isTextSelectable = isChecked
                }

                readSettingsLockTts.isChecked = viewModel.ttsLock
                readSettingsLockTts.setOnCheckedChangeListener { _, isChecked ->
                    viewModel.ttsLock = isChecked
                }

                readSettingsShowTime.isChecked = viewModel.showTime
                readSettingsShowTime.setOnCheckedChangeListener { _, isChecked ->
                    viewModel.showTime = isChecked
                }

                //readSettingsTwelveHourTime.isChecked = viewModel.time12H
                //readSettingsTwelveHourTime.setOnCheckedChangeListener { _, isChecked ->
                //    viewModel.time12H = isChecked
                //}

                readSettingsShowBattery.isChecked = viewModel.showBattery
                readSettingsShowBattery.setOnCheckedChangeListener { _, isChecked ->
                    viewModel.showBattery = isChecked
                }

                readSettingsKeepScreenActive.isChecked = viewModel.screenAwake
                readSettingsKeepScreenActive.setOnCheckedChangeListener { _, isChecked ->
                    viewModel.screenAwake = isChecked
                }

            }

            val bgColors = resources.getIntArray(R.array.readerBgColors)
            val textColors = resources.getIntArray(R.array.readerTextColors)

            imageHolder = bindingSettings.readSettingsColors
            for ((newBgColor, newTextColor) in bgColors zip textColors) {
                ColorRoundCheckmarkBinding.inflate(
                    layoutInflater,
                    bindingSettings.readSettingsColors,
                    true
                ).image1.apply {
                    backgroundTintList = ColorStateList.valueOf(newBgColor)
                    //foregroundTintList = ColorStateList.valueOf(newTextColor)
                    setOnClickListener {
                        viewModel.backgroundColor = newBgColor
                        viewModel.textColor = newTextColor
                        updateImages()
                    }
                }
            }

            ColorRoundCheckmarkBinding.inflate(
                layoutInflater,
                bindingSettings.readSettingsColors,
                true
            ).image1.apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    foreground =
                        ContextCompat.getDrawable(this.context, R.drawable.ic_baseline_add_24)
                }

                setOnClickListener {
                    val builder: AlertDialog.Builder = AlertDialog.Builder(this.context)
                    builder.setTitle(getString(R.string.reading_color))

                    val colorAdapter =
                        ArrayAdapter<String>(this.context, R.layout.chapter_select_dialog)
                    val array = arrayListOf(
                        getString(R.string.background_color),
                        getString(R.string.text_color)
                    )
                    colorAdapter.addAll(array)

                    builder.setPositiveButton(R.string.ok) { dialog, _ ->
                        dialog.dismiss()
                        updateImages()
                    }

                    builder.setAdapter(colorAdapter) { _, which ->
                        ColorPickerDialog.newBuilder()
                            .setDialogId(which)
                            .setColor(
                                when (which) {
                                    0 -> viewModel.backgroundColor
                                    1 -> viewModel.textColor
                                    else -> 0
                                }
                            )
                            .show(readActivity ?: return@setAdapter)
                    }

                    builder.show()
                    updateImages()
                }
            }
            updateImages()

            bottomSheetDialog.show()
        }
    }
}
