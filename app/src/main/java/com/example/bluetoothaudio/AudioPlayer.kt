import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.io.InputStream
import kotlin.concurrent.thread

class AudioPlayer(private val inputStream: InputStream) {
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    private val audioTrack = AudioTrack(
        AudioManager.STREAM_MUSIC,
        sampleRate,
        channelConfig,
        audioFormat,
        bufferSize,
        AudioTrack.MODE_STREAM
    )

    @Volatile
    private var isPlaying = false

    fun startPlaying() {
        isPlaying = true
        audioTrack.play()
        thread(start = true) {
            val buffer = ByteArray(bufferSize)
            try {
                while (isPlaying) {
                    val read = inputStream.read(buffer)
                    if (read > 0) {
                        audioTrack.write(buffer, 0, read)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                stopPlaying()
            }
        }
    }

    fun stopPlaying() {
        isPlaying = false
        audioTrack.stop()
        audioTrack.release()
    }

    fun playAudio(data: ByteArray, offset: Int, length: Int) {
        audioTrack.write(data, offset, length)
    }
}
