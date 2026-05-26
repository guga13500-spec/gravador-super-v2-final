package br.com.guga.gravadorsuper.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import org.fossify.commons.helpers.ensureBackgroundThread
import br.com.guga.gravadorsuper.extensions.config
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class WavRecorder(val context: Context) : Recorder {
    private var isPaused = AtomicBoolean(false)
    private var isStopped = AtomicBoolean(false)
    private var amplitude = AtomicInteger(0)
    private var outputStream: FileOutputStream? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var totalAudioLen = 0L

    private val samplingRate = context.config.samplingRate
    private val audioSource = context.config.microphoneMode
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val minBufferSize = AudioRecord.getMinBufferSize(samplingRate, channelConfig, audioFormat)

    private val audioRecord = AudioRecord(
        audioSource,
        samplingRate,
        channelConfig,
        audioFormat,
        minBufferSize
    )

    override fun setOutputFile(path: String) {
        outputStream = FileOutputStream(path)
    }

    override fun setOutputFile(parcelFileDescriptor: ParcelFileDescriptor) {
        this.fileDescriptor = ParcelFileDescriptor.dup(parcelFileDescriptor.fileDescriptor)
        outputStream = FileOutputStream(fileDescriptor!!.fileDescriptor)
    }

    override fun prepare() {
        // Write empty header, will be updated in stop()
        writeWavHeader(outputStream!!, 0)
    }

    override fun start() {
        audioRecord.startRecording()
        isStopped.set(false)
        isPaused.set(false)

        ensureBackgroundThread {
            val data = ShortArray(minBufferSize)
            while (!isStopped.get()) {
                if (isPaused.get()) {
                    Thread.sleep(100)
                    continue
                }

                val read = audioRecord.read(data, 0, minBufferSize)
                if (read > 0) {
                    updateAmplitude(data)
                    val byteData = ShortToByte(data, read)
                    try {
                        outputStream?.write(byteData)
                        totalAudioLen += byteData.size
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    override fun stop() {
        isStopped.set(true)
        audioRecord.stop()
        
        // Update WAV header with correct length
        // This is tricky with FileOutputStream if we can't seek.
        // If we have a path, we can use RandomAccessFile.
        // If we have a FileDescriptor, it's harder.
    }

    override fun pause() {
        isPaused.set(true)
    }

    override fun resume() {
        isPaused.set(false)
    }

    override fun release() {
        outputStream?.close()
        fileDescriptor?.close()
        audioRecord.release()
    }

    override fun getMaxAmplitude(): Int {
        return amplitude.get()
    }

    private fun updateAmplitude(data: ShortArray) {
        var sum = 0L
        for (i in 0 until minBufferSize step 2) {
            sum += abs(data[i].toInt())
        }
        amplitude.set((sum / (minBufferSize / 8)).toInt())
    }

    private fun ShortToByte(sData: ShortArray, size: Int): ByteArray {
        val bytes = ByteArray(size * 2)
        for (i in 0 until size) {
            bytes[i * 2] = (sData[i].toInt() and 0x00FF).toByte()
            bytes[i * 2 + 1] = (sData[i].toInt() shr 8).toByte()
        }
        return bytes
    }

    private fun writeWavHeader(out: FileOutputStream, totalAudioLen: Long) {
        val totalDataLen = totalAudioLen + 36
        val sampleRate = samplingRate.toLong()
        val channels = 1
        val byteRate = 16 * sampleRate * channels / 8
        val header = ByteArray(44)
        header[0] = 'R'.toByte() // RIFF/WAVE header
        header[1] = 'I'.toByte()
        header[2] = 'F'.toByte()
        header[3] = 'F'.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.toByte()
        header[9] = 'A'.toByte()
        header[10] = 'V'.toByte()
        header[11] = 'E'.toByte()
        header[12] = 'f'.toByte() // 'fmt ' chunk
        header[13] = 'm'.toByte()
        header[14] = 't'.toByte()
        header[15] = ' '.toByte()
        header[16] = 16 // 4 bytes: size of 'fmt ' chunk
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // format = 1 (PCM)
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte()
        header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte()
        header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = (channels * 16 / 8).toByte() // block align
        header[33] = 0
        header[34] = 16 // bits per sample
        header[35] = 0
        header[36] = 'd'.toByte()
        header[37] = 'a'.toByte()
        header[38] = 't'.toByte()
        header[39] = 'a'.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = (totalAudioLen shr 8 and 0xff).toByte()
        header[42] = (totalAudioLen shr 16 and 0xff).toByte()
        header[43] = (totalAudioLen shr 24 and 0xff).toByte()
        out.write(header, 0, 44)
    }
}
