package com.lagradost.quicknovel.ui.history

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import com.lagradost.quicknovel.R
import com.lagradost.quicknovel.databinding.FragmentHistoryBinding
import com.lagradost.quicknovel.mvvm.observe
import com.lagradost.quicknovel.ui.BaseFragment
import com.lagradost.quicknovel.ui.setRecycledViewPool
import com.lagradost.quicknovel.util.UIHelper.fixPaddingStatusbar
import com.lagradost.quicknovel.util.UIHelper.popupMenu


class HistoryFragment : BaseFragment<FragmentHistoryBinding>(
    BindingCreator.Inflate(FragmentHistoryBinding::inflate)
) {
    private val viewModel: HistoryViewModel by viewModels()

    override fun fixLayout(view: View) {
        val compactView = true //requireContext().getDownloadIsCompact()
        val spanCountLandscape = if (compactView) 2 else 6
        val spanCountPortrait = if (compactView) 1 else 3
        val orientation = resources.configuration.orientation

        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            binding?.historyCardSpace?.spanCount = spanCountLandscape
        } else {
            binding?.historyCardSpace?.spanCount = spanCountPortrait
        }
    }

    override fun onBindingCreated(binding: FragmentHistoryBinding) {
        activity?.fixPaddingStatusbar(binding.historyToolbar)

        val historyAdapter = HistoryAdapter(viewModel)

        binding.historyCardSpace.apply {
            setRecycledViewPool(HistoryAdapter.sharedPool)
            adapter = historyAdapter
        }

        observe(viewModel.cards) { cards ->
            historyAdapter.submitList(cards)
            binding.historyNothingHolder.isVisible = cards.isEmpty()
        }

        binding.historyOpenLinkFab.setOnClickListener {
            showOpenLinkDialog()
        }

        binding.historyToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_history_more -> {
                    binding.historyToolbar.findViewById<View>(R.id.action_history_more)
                        .popupMenu(listOf(1 to R.string.clear_history)) {
                            if (itemId == 1) {
                                viewModel.deleteAllAlert()
                            }
                        }
                }

                else -> {
                }
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun showOpenLinkDialog() {
        val ctx = context ?: return
        val density = resources.displayMetrics.density
        val margin = (20 * density).toInt()

        val input = EditText(ctx).apply {
            hint = getString(R.string.history_open_link_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            // Si hay un enlace en el portapapeles, lo rellenamos para ahorrar un paso
            val clip = (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                ?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
            if (clip != null && (clip.startsWith("http://") || clip.startsWith("https://"))) {
                setText(clip)
                selectAll()
            }
        }
        val container = FrameLayout(ctx).apply {
            setPadding(margin, margin / 2, margin, 0)
            addView(input)
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.history_open_link_title)
            .setView(container)
            .setPositiveButton(R.string.ok, null) // se sobreescribe abajo para no cerrar si es inválido
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (viewModel.openLink(input.text.toString())) {
                    dialog.dismiss()
                } else {
                    Toast.makeText(ctx, R.string.history_open_link_invalid, Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
        dialog.show()
    }
}
