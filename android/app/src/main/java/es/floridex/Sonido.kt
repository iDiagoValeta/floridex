package es.floridex

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread

/**
 * 8-bit beeps made on the fly as square waves: no sound files. The same
 * melodies as the web app. The user can mute them; the choice is kept.
 */
class Sonido(context: Context) {
    private val prefs = context.getSharedPreferences("floridex", Context.MODE_PRIVATE)
    var activo: Boolean = prefs.getBoolean("sonido", true)
        private set

    fun alternar(): Boolean {
        activo = !activo
        prefs.edit().putBoolean("sonido", activo).apply()
        return activo
    }

    fun cursor() = tocar(listOf(1320 to 28), 0.03f)
    fun abrir() = tocar(listOf(784 to 45, 1175 to 70))
    fun volver() = tocar(listOf(880 to 40, 587 to 60))
    fun letra() = tocar(listOf(1760 to 12), 0.015f)
    fun vista() = tocar(listOf(523 to 70, 0 to 20, 659 to 110))
    fun captura() = tocar(listOf(523 to 80, 659 to 80, 784 to 80, 1047 to 80, 0 to 40, 784 to 80, 1047 to 260), 0.06f)
    fun error() = tocar(listOf(196 to 110, 147 to 180))
    fun disparo() = tocar(listOf(2093 to 20, 1047 to 40), 0.04f)

    /** Notes as (frequency in Hz, duration in ms); 0 Hz is a rest. */
    private fun tocar(notas: List<Pair<Int, Int>>, volumen: Float = 0.05f) {
        if (!activo) return
        thread(isDaemon = true, name = "pitido") {
            runCatching {
                val muestras = notas.sumOf { it.second } * FRECUENCIA / 1000
                val pcm = ShortArray(muestras)
                var i = 0
                for ((f, ms) in notas) {
                    val n = ms * FRECUENCIA / 1000
                    for (k in 0 until n) {
                        // Square wave with a linear fade, so notes do not click.
                        val onda = if (f == 0) 0f else if ((k * 2L * f / FRECUENCIA) % 2 == 0L) 1f else -1f
                        pcm[i++] = (onda * volumen * (1f - k.toFloat() / n) * Short.MAX_VALUE).toInt().toShort()
                    }
                }
                val pista = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(FRECUENCIA).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                pista.write(pcm, 0, pcm.size)
                pista.play()
                Thread.sleep(notas.sumOf { it.second }.toLong() + 60)
                pista.release()
            }
        }
    }

    companion object {
        private const val FRECUENCIA = 22050
    }
}
