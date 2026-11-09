package app.tapwell

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

/** Supplies the bundled chime to Android's notification player at the current volume. */
class ReminderSoundProvider : ContentProvider() {
    override fun onCreate() = true

    override fun getType(uri: Uri): String? = if (uri.path == "/chime") "audio/wav" else null

    @Synchronized
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r" || uri.path != "/chime") throw FileNotFoundException()
        val app = checkNotNull(context)
        val audio = app.getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_NOTIFICATION
        val volume = audio.getStreamVolume(stream).coerceIn(audio.getStreamMinVolume(stream), audio.getStreamMaxVolume(stream))
        val attenuation = audio.getStreamVolumeDb(stream, volume, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val gainSteps = chimeGainHalfDecibels(attenuation)
        val file = File(app.cacheDir, "tapwell_chime_1_$gainSteps.wav")
        if (!file.isFile) {
            val wav = app.resources.openRawResource(R.raw.tapwell_chime).use { it.readBytes() }
            // The bundled WAV has a 44-byte PCM header followed by 16-bit samples.
            val samples = ByteBuffer.wrap(wav, 44, wav.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val gain = 10.0.pow(gainSteps / 40.0)
            for (index in 0 until samples.limit()) {
                val amplified = (samples.get(index) * gain).roundToInt()
                samples.put(index, amplified.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try {
                output.write(wav)
                atomic.finishWrite(output)
            } catch (error: java.io.IOException) {
                atomic.failWrite(output)
                throw error
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}

internal fun chimeGainHalfDecibels(attenuation: Float): Int =
    // Compensate for part of the speaker's attenuation so the system slider still changes the loudness.
    if (attenuation.isFinite()) ((-4.0 - 0.45 * attenuation).coerceIn(-4.0, 14.0) * 2).roundToInt() else 0
