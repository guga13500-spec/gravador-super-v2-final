package br.com.guga.gravadorsuper.fragments

import android.content.Context
import android.util.AttributeSet
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.updateTextColors
import br.com.guga.gravadorsuper.activities.SimpleActivity
import br.com.guga.gravadorsuper.adapters.TrashAdapter
import br.com.guga.gravadorsuper.databinding.FragmentTrashBinding
import br.com.guga.gravadorsuper.extensions.config
import br.com.guga.gravadorsuper.interfaces.RefreshRecordingsListener
import br.com.guga.gravadorsuper.models.Events
import br.com.guga.gravadorsuper.models.Recording
import br.com.guga.gravadorsuper.extensions.*
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.helpers.ensureBackgroundThread
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class TrashFragment(
    context: Context,
    attributeSet: AttributeSet
) : MyViewPagerFragment(context, attributeSet), RefreshRecordingsListener {

    private var itemsIgnoringSearch = ArrayList<Recording>()
    private var lastSearchQuery = ""
    private var bus: EventBus? = null
    private var prevSavePath = ""
    private lateinit var binding: FragmentTrashBinding

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentTrashBinding.bind(this)
    }

    override fun onResume() {
        setupColors()
        if (prevSavePath.isNotEmpty() && context!!.config.saveRecordingsFolder != prevSavePath) {
            loadRecordings(trashed = true)
        } else {
            getRecordingsAdapter()?.updateTextColor(context.getProperTextColor())
        }

        storePrevPath()
    }

    override fun onDestroy() {
        bus?.unregister(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        bus = EventBus.getDefault()
        bus!!.register(this)
        setupColors()
        loadRecordings(trashed = true)
        storePrevPath()
    }

    override fun refreshRecordings() = loadRecordings(trashed = true)

    override fun playRecording(recording: Recording, playOnPrepared: Boolean) {}

    override fun onLoadingStart() {
        if (itemsIgnoringSearch.isEmpty()) {
            binding.loadingIndicator.show()
        } else {
            binding.loadingIndicator.hide()
        }
    }

    override fun onLoadingEnd(recordings: ArrayList<Recording>) {
        binding.loadingIndicator.hide()
        binding.trashPlaceholder.beVisibleIf(recordings.isEmpty())
        itemsIgnoringSearch = recordings
        setupAdapter(itemsIgnoringSearch)
    }

    private fun setupAdapter(recordings: ArrayList<Recording>) {
        binding.trashFastscroller.beVisibleIf(recordings.isNotEmpty())
        if (recordings.isEmpty()) {
            val stringId = if (lastSearchQuery.isEmpty()) {
                org.fossify.commons.R.string.recycle_bin_empty
            } else {
                org.fossify.commons.R.string.no_items_found
            }

            binding.trashPlaceholder.text = context.getString(stringId)
        }

        val adapter = getRecordingsAdapter()
        if (adapter == null) {
            TrashAdapter(context as SimpleActivity, recordings, this, binding.trashList).apply {
                binding.trashList.adapter = this
            }

            if (context.areSystemAnimationsEnabled) {
                binding.trashList.scheduleLayoutAnimation()
            }
        } else {
            adapter.updateItems(recordings)
        }
    }

    fun onSearchTextChanged(text: String) {
        lastSearchQuery = text
        val filtered = itemsIgnoringSearch.filter { it.title.contains(text, true) }
            .toMutableList() as ArrayList<Recording>
        setupAdapter(filtered)
    }

    private fun getRecordingsAdapter() = binding.trashList.adapter as? TrashAdapter

    private fun storePrevPath() {
        prevSavePath = context!!.config.saveRecordingsFolder
    }

    private fun setupColors() {
        val properPrimaryColor = context.getProperPrimaryColor()
        binding.trashFastscroller.updateColors(properPrimaryColor)
        context.updateTextColors(binding.trashHolder)
    }

    fun finishActMode() = getRecordingsAdapter()?.finishActMode()

    fun deleteAll() {
        val adapter = getRecordingsAdapter() ?: return
        if (adapter.recordings.isEmpty()) return

        ConfirmationDialog(context as SimpleActivity, context.getString(org.fossify.commons.R.string.empty_recycle_bin_confirmation)) {
            ensureBackgroundThread {
                (context as SimpleActivity).deleteRecordings(adapter.recordings) { success ->
                    if (success) {
                        refreshRecordings()
                    }
                }
            }
        }
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun recordingMovedToRecycleBin(@Suppress("UNUSED_PARAMETER") event: Events.RecordingTrashUpdated) {
        refreshRecordings()
    }
}
