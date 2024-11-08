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
    private val audioTrack = AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelConfig, audioFormat, bufferSize, AudioTrack.MODE_STREAM)

    @Volatile private var isPlaying = false
    private var playThread: Thread? = null

    fun startPlaying() {
        if (isPlaying) return  // Évite de démarrer plusieurs fois
        isPlaying = true
        audioTrack.play()

        playThread = thread(start = true) {
            val buffer = ByteArray(bufferSize)
            try {
                while (isPlaying) {
                    val read = inputStream.read(buffer)
                    if (read > 0) {
                        audioTrack.write(buffer, 0, read)
                    } else {
                        break // Fin de lecture
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
        if (!isPlaying) return  // Évite l'arrêt multiple
        isPlaying = false
        playThread?.join()  // Attend la fin du fil de lecture
        audioTrack.stop()
        audioTrack.release()  // Libère les ressources
    }

    fun playAudio(audioData: ByteArray, i: Int, bytesRead: Int) {

    }
}
