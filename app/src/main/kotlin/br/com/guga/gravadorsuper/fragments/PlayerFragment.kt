package br.com.guga.gravadorsuper.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.RECEIVER_NOT_EXPORTED
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.net.toUri
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getFormattedDuration
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.value
import org.fossify.commons.helpers.isQPlus
import org.fossify.commons.helpers.isTiramisuPlus
import br.com.guga.gravadorsuper.R
import br.com.guga.gravadorsuper.activities.SimpleActivity
import br.com.guga.gravadorsuper.activities.MainActivity
import androidx.viewpager.widget.ViewPager
import br.com.guga.gravadorsuper.adapters.RecordingsAdapter
import br.com.guga.gravadorsuper.databinding.FragmentPlayerBinding
import br.com.guga.gravadorsuper.extensions.config
import br.com.guga.gravadorsuper.interfaces.RefreshRecordingsListener
import br.com.guga.gravadorsuper.models.Events
import br.com.guga.gravadorsuper.models.Recording
import br.com.guga.gravadorsuper.receivers.BecomingNoisyReceiver
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.toast
import br.com.guga.gravadorsuper.extensions.*
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.Stack
import java.util.Timer
import java.util.TimerTask

class PlayerFragment(
    context: Context,
    attributeSet: AttributeSet
) : MyViewPagerFragment(context, attributeSet), RefreshRecordingsListener {

    companion object {
        private const val FAST_FORWARD_SKIP_MS = 10000
    }

    private var player: MediaPlayer? = null
    private var progressTimer = Timer()
    private var playedRecordingIDs = Stack<Int>()
    private var itemsIgnoringSearch = ArrayList<Recording>()
    private var lastSearchQuery = ""
    private var bus: EventBus? = null
    private var prevSavePath = ""
    private var prevRecycleBinState = context.config.useRecycleBin
    private var playOnPreparation = true
    private lateinit var binding: FragmentPlayerBinding

    private var becomingNoisyReceiver: BecomingNoisyReceiver? = null
    private var isReceiverRegistered = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = FragmentPlayerBinding.bind(this)
    }

    override fun onResume() {
        setupColors()
        if (prevSavePath.isNotEmpty() && context!!.config.saveRecordingsFolder != prevSavePath || context.config.useRecycleBin != prevRecycleBinState) {
            loadRecordings()
        } else {
            getRecordingsAdapter()?.updateTextColor(context.getProperTextColor())
        }

        storePrevState()
    }

    override fun onDestroy() {
        unregisterNoisyAudioReceiver()
        player?.stop()
        player?.release()
        player = null

        bus?.unregister(this)
        progressTimer.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        bus = EventBus.getDefault()
        bus!!.register(this)
        setupColors()
        loadRecordings()
        initMediaPlayer()
        setupViews()
        storePrevState()
    }

    override fun onLoadingStart() {
        if (itemsIgnoringSearch.isEmpty()) {
            binding.loadingIndicator.show()
        } else {
            binding.loadingIndicator.hide()
        }
    }

    override fun onLoadingEnd(recordings: ArrayList<Recording>) {
        binding.loadingIndicator.hide()
        binding.recordingsPlaceholder.beVisibleIf(recordings.isEmpty())
        itemsIgnoringSearch = recordings
        setupAdapter(itemsIgnoringSearch)
    }

    private fun setupViews() {
        binding.playPauseBtn.setOnClickListener {
            if (playedRecordingIDs.empty() || binding.playerProgressbar.max == 0) {
                binding.nextBtn.callOnClick()
            } else {
                togglePlayPause()
            }
        }

        binding.playerProgressCurrent.setOnClickListener {
            skip(false)
        }

        binding.playerProgressMax.setOnClickListener {
            skip(true)
        }

        binding.previousBtn.setOnClickListener {
            if (playedRecordingIDs.isEmpty()) {
                return@setOnClickListener
            }

            val adapter = getRecordingsAdapter() ?: return@setOnClickListener
            var wantedRecordingID = playedRecordingIDs.pop()
            if (wantedRecordingID == adapter.currRecordingId && !playedRecordingIDs.isEmpty()) {
                wantedRecordingID = playedRecordingIDs.pop()
            }

            val prevRecordingIndex = adapter.recordings.indexOfFirst { it.id == wantedRecordingID }
            val prevRecording = adapter.recordings
                .getOrNull(prevRecordingIndex) ?: return@setOnClickListener
            playRecording(prevRecording, true)
        }

        binding.playbackSpeed.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                playbackSpeed = when (playbackSpeed) {
                    1.0f -> 1.25f
                    1.25f -> 1.5f
                    1.5f -> 2.0f
                    2.0f -> 0.5f
                    0.5f -> 0.75f
                    else -> 1.0f
                }
                if (getIsPlaying()) {
                    player?.playbackParams = player?.playbackParams?.setSpeed(playbackSpeed) ?: android.media.PlaybackParams().setSpeed(playbackSpeed)
                }
                binding.playbackSpeed.text = "${playbackSpeed}x"
            }
        }

        binding.playerTitle.setOnClickListener {
            val adapter = getRecordingsAdapter()
            if (adapter != null && !playedRecordingIDs.isEmpty()) {
                val currID = adapter.currRecordingId
                val recording = adapter.recordings.firstOrNull { it.id == currID }
                if (recording != null) {
                    (context as SimpleActivity).showRenameRecordingDialog(recording) {
                        refreshRecordingsList()
                    }
                }
            }
        }

        binding.playerTitle.setOnLongClickListener {
            if (binding.playerTitle.value.isNotEmpty()) {
                context.copyToClipboard(binding.playerTitle.value)
            }
            true
        }

        binding.nextBtn.setOnClickListener {
            val adapter = getRecordingsAdapter()
            if (adapter == null || adapter.recordings.isEmpty()) {
                return@setOnClickListener
            }

            val oldRecordingIndex =
                adapter.recordings.indexOfFirst { it.id == adapter.currRecordingId }
            val newRecordingIndex = (oldRecordingIndex + 1) % adapter.recordings.size
            val newRecording =
                adapter.recordings.getOrNull(newRecordingIndex) ?: return@setOnClickListener
            playRecording(newRecording, true)
            playedRecordingIDs.push(newRecording.id)
        }
    }

    override fun refreshRecordings() = loadRecordings()

    private fun refreshRecordingsList() {
        val adapter = getRecordingsAdapter()
        if (adapter != null) {
            val currID = adapter.currRecordingId
            (context as SimpleActivity).getRecordings { recordings ->
                adapter.updateItems(recordings)
                if (currID != 0) {
                    val updatedRecording = recordings.firstOrNull { it.id == currID }
                    if (updatedRecording != null) {
                        binding.playerTitle.text = updatedRecording.title
                    }
                }
            }
        }
    }

    private fun setupAdapter(recordings: ArrayList<Recording>) {
        binding.recordingsFastscroller.beVisibleIf(recordings.isNotEmpty())
        if (recordings.isEmpty()) {
            val stringId = if (lastSearchQuery.isEmpty()) {
                if (isQPlus()) {
                    R.string.no_recordings_found
                } else {
                    R.string.no_recordings_in_folder_found
                }
            } else {
                org.fossify.commons.R.string.no_items_found
            }

            binding.recordingsPlaceholder.text = context.getString(stringId)
            resetProgress(null)
            player?.stop()
        }

        val adapter = getRecordingsAdapter()
        if (adapter == null) {
            RecordingsAdapter(context as SimpleActivity, recordings, this, binding.recordingsList) {
                playRecording(it as Recording, true)
                if (playedRecordingIDs.isEmpty() || playedRecordingIDs.peek() != it.id) {
                    playedRecordingIDs.push(it.id)
                }
            }.apply {
                binding.recordingsList.adapter = this
            }

            if (context.areSystemAnimationsEnabled) {
                binding.recordingsList.scheduleLayoutAnimation()
            }
        } else {
            adapter.updateItems(recordings)
        }
    }

    private fun initMediaPlayer() {
        player = MediaPlayer().apply {
            setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )

            setOnCompletionListener {
                progressTimer.cancel()
                unregisterNoisyAudioReceiver()
                binding.playerProgressbar.progress = binding.playerProgressbar.max
                binding.playerProgressCurrent.text = binding.playerProgressMax.text
                binding.playPauseBtn.setImageDrawable(getToggleButtonIcon(false))
            }

            setOnPreparedListener {
                if (playOnPreparation) {
                    resumePlayback()
                }

                playOnPreparation = true
            }
        }
    }

    override fun playRecording(recording: Recording, playOnPrepared: Boolean) {
        resetProgress(recording)
        (binding.recordingsList.adapter as RecordingsAdapter).updateCurrentRecording(recording.id)
        playOnPreparation = playOnPrepared

        player!!.apply {
            reset()

            try {
                setDataSource(context, recording.path.toUri())
            } catch (e: Exception) {
                context?.showErrorToast(e)
                return
            }

            try {
                prepareAsync()
            } catch (e: Exception) {
                context.showErrorToast(e)
                return
            }
        }

        binding.playPauseBtn.setImageDrawable(getToggleButtonIcon(playOnPreparation))
        binding.playerProgressbar.setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && !playedRecordingIDs.isEmpty()) {
                    player?.seekTo(progress * 1000)
                    binding.playerProgressCurrent.text = progress.getFormattedDuration()
                    resumePlayback()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    @SuppressLint("DiscouragedApi")
    private fun setupProgressTimer() {
        progressTimer.cancel()
        progressTimer = Timer()
        progressTimer.scheduleAtFixedRate(getProgressUpdateTask(), 1000, 1000)
    }

    private fun getProgressUpdateTask() = object : TimerTask() {
        override fun run() {
            Handler(Looper.getMainLooper()).post {
                if (player != null) {
                    val progress = Math.round(player!!.currentPosition / 1000.toDouble()).toInt()
                    updateCurrentProgress(progress)
                    binding.playerProgressbar.progress = progress
                }
            }
        }
    }

    private fun updateCurrentProgress(seconds: Int) {
        binding.playerProgressCurrent.text = seconds.getFormattedDuration()
    }

    private fun resetProgress(recording: Recording?) {
        updateCurrentProgress(0)
        binding.playerProgressbar.progress = 0
        binding.playerProgressbar.max = recording?.duration ?: 0
        binding.playerTitle.text = recording?.title ?: ""
        binding.playerProgressMax.text = (recording?.duration ?: 0).getFormattedDuration()
    }

    fun onSearchTextChanged(text: String) {
        lastSearchQuery = text
        val filtered = itemsIgnoringSearch
            .filter { it.title.contains(text, true) }
            .toMutableList() as ArrayList<Recording>
        setupAdapter(filtered)
    }

    private fun togglePlayPause() {
        if (getIsPlaying()) {
            pausePlayback()
        } else {
            resumePlayback()
        }
    }

    private fun pausePlayback() {
        unregisterNoisyAudioReceiver()
        player?.pause()
        binding.playPauseBtn.setImageDrawable(getToggleButtonIcon(false))
        progressTimer.cancel()
    }

    private var playbackSpeed = 1.0f

    private fun resumePlayback() {
        registerNoisyAudioReceiver()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            player?.playbackParams = player?.playbackParams?.setSpeed(playbackSpeed) ?: android.media.PlaybackParams().setSpeed(playbackSpeed)
        }
        player?.start()
        binding.playPauseBtn.setImageDrawable(getToggleButtonIcon(true))
        setupProgressTimer()
    }

    private fun getToggleButtonIcon(isPlaying: Boolean): Drawable {
        val drawable = if (isPlaying) {
            org.fossify.commons.R.drawable.ic_pause_vector
        } else {
            org.fossify.commons.R.drawable.ic_play_vector
        }

        return resources.getColoredDrawableWithColor(
            drawableId = drawable,
            color = context.getProperPrimaryColor().getContrastColor()
        )
    }

    private fun skip(forward: Boolean) {
        if (playedRecordingIDs.empty()) {
            return
        }

        val curr = player?.currentPosition ?: return
        var newProgress = if (forward) curr + FAST_FORWARD_SKIP_MS else curr - FAST_FORWARD_SKIP_MS
        if (newProgress > player!!.duration) {
            newProgress = player!!.duration
        }

        player!!.seekTo(newProgress)
        resumePlayback()
    }

    private fun getIsPlaying() = player?.isPlaying == true

    private fun getRecordingsAdapter() = binding.recordingsList.adapter as? RecordingsAdapter

    private fun storePrevState() {
        prevSavePath = context!!.config.saveRecordingsFolder
        prevRecycleBinState = context.config.useRecycleBin
    }

    private fun setupColors() {
        val properPrimaryColor = context.getProperPrimaryColor()
        binding.recordingsFastscroller.updateColors(properPrimaryColor)
        context.updateTextColors(binding.playerHolder)

        val textColor = context.getProperTextColor()
        arrayListOf(binding.previousBtn, binding.nextBtn, binding.playbackSpeed).forEach {
            if (it is ImageView) {
                it.applyColorFilter(textColor)
            } else if (it is TextView) {
                it.setTextColor(textColor)
            }
        }

        binding.playPauseBtn.background.applyColorFilter(properPrimaryColor)
        binding.playPauseBtn.setImageDrawable(getToggleButtonIcon(getIsPlaying()))

        binding.loadingIndicator.setIndicatorColor(properPrimaryColor)
    }

    fun finishActMode() = getRecordingsAdapter()?.finishActMode()

    fun deleteAll() {
        val adapter = getRecordingsAdapter() ?: return
        if (adapter.recordings.isEmpty()) return

        val itemsCnt = adapter.recordings.size
        val items = resources.getQuantityString(R.plurals.delete_recordings, itemsCnt, itemsCnt)
        val baseString = if (context.config.useRecycleBin) R.string.trash_recordings_confirmation else R.string.delete_recordings_confirmation
        val question = String.format(resources.getString(baseString), items)

        ConfirmationDialog(context as SimpleActivity, question) {
            val recordingsToRemove = ArrayList(adapter.recordings)
            if (context.config.useRecycleBin) {
                (context as SimpleActivity).trashRecordings(recordingsToRemove) { success ->
                    if (success) {
                        refreshRecordings()
                        EventBus.getDefault().post(Events.RecordingTrashUpdated())
                    }
                }
            } else {
                (context as SimpleActivity).deleteRecordings(recordingsToRemove) { success ->
                    if (success) {
                        refreshRecordings()
                    }
                }
            }
        }
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun recordingCompleted(@Suppress("UNUSED_PARAMETER") event: Events.RecordingCompleted) {
        refreshRecordings()
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun recordingMovedToRecycleBin(@Suppress("UNUSED_PARAMETER") event: Events.RecordingTrashUpdated) {
        refreshRecordings()
    }

    private fun registerNoisyAudioReceiver() {
        if (isReceiverRegistered) return
        if (becomingNoisyReceiver == null) {
            becomingNoisyReceiver = BecomingNoisyReceiver(onBecomingNoisy = ::pausePlayback)
        }

        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (isTiramisuPlus()) {
            context.registerReceiver(becomingNoisyReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(becomingNoisyReceiver, filter)
        }

        isReceiverRegistered = true
    }

    private fun unregisterNoisyAudioReceiver() {
        if (!isReceiverRegistered || becomingNoisyReceiver == null) return
        try {
            isReceiverRegistered = false
            context.unregisterReceiver(becomingNoisyReceiver)
        } catch (ignored: IllegalArgumentException) {
        }
    }

    @Suppress("unused")
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun playExternalAudio(event: Events.PlayExternalAudio) {
        val uri = event.uri
        val title = uri.lastPathSegment ?: "Audio"
        val recording = Recording(id = -1, title = title, path = uri.toString(), timestamp = System.currentTimeMillis(), duration = 0, size = 0)
        (context as? MainActivity)?.let { mainActivity -> mainActivity.findViewById<androidx.viewpager.widget.ViewPager>(R.id.view_pager)?.currentItem = 1 }
        playRecording(recording, true)
    }
}
