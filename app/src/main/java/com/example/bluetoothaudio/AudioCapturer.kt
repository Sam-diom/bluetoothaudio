import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.OutputStream
import kotlin.concurrent.thread

class AudioCapturer(private val outputStream: OutputStream) {
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    @SuppressLint("MissingPermission")
    private val audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize)

    @Volatile
    var isCapturing = false
    private var captureThread: Thread? = null

    fun startCapturing() {
        if (isCapturing) return  // Évite de lancer plusieurs captures
        isCapturing = true
        audioRecord.startRecording()

        captureThread = thread(start = true) {
            val buffer = ByteArray(bufferSize)
            try {
                while (isCapturing) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        outputStream.write(buffer, 0, read)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                stopCapturing() // Assure que les ressources sont libérées
            }
        }
    }

    fun stopCapturing() {
        if (!isCapturing) return  // Évite l'arrêt multiple
        isCapturing = false
        captureThread?.join()  // Attend la fin du fil de capture
        audioRecord.stop()
        audioRecord.release()  // Libère les ressources
        outputStream.close()   // Ferme le flux de sortie pour libérer les ressources
    }

    fun readAudioData() {

    }
}
