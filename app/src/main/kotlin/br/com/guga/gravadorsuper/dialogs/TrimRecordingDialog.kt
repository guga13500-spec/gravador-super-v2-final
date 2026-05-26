package br.com.guga.gravadorsuper.dialogs

import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.TextInputEditText
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.extensions.getAlertDialogBuilder
import org.fossify.commons.extensions.setupDialogStuff
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.value
import br.com.guga.gravadorsuper.R
import br.com.guga.gravadorsuper.databinding.DialogTrimRecordingBinding
import br.com.guga.gravadorsuper.models.Recording

class TrimRecordingDialog(
    val activity: BaseSimpleActivity,
    val recording: Recording,
    val callback: (start: Int, end: Int) -> Unit
) {
    private var dialog: AlertDialog? = null
    private val binding = DialogTrimRecordingBinding.inflate(activity.layoutInflater)

    init {
        binding.trimRecordingEnd.setText((recording.duration).toString())

        activity.getAlertDialogBuilder()
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create().apply {
                activity.setupDialogStuff(binding.root, this, R.string.trim) {
                    dialog = this
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val startText = binding.trimRecordingStart.value
                        val endText = binding.trimRecordingEnd.value

                        if (startText.isEmpty() || endText.isEmpty()) {
                            return@setOnClickListener
                        }

                        val start = startText.toInt()
                        val end = endText.toInt()

                        if (start >= end || start < 0 || end > recording.duration) {
                            activity.showErrorToast(activity.getString(R.string.trim_error_invalid_times))
                            return@setOnClickListener
                        }

                        callback(start, end)
                        dismiss()
                    }
                }
            }
    }
}
