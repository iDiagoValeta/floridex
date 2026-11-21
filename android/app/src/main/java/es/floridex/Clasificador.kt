package es.floridex

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class Candidato(val clave: String, val probabilidad: Float)

/**
 * Runs the calibrated, int8-quantized DINOv2 flower classifier on device.
 *
 * Preprocessing mirrors training (torchvision on PIL), see [preprocesar].
 */
class Clasificador(context: Context) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val sesion: OrtSession
    val etiquetas: List<String>

    init {
        val modelo = context.assets.open("modelo/model_quantized.onnx").use { it.readBytes() }
        sesion = env.createSession(modelo, OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        })
        etiquetas = etiquetasDelModelo(context)
    }

    fun clasificar(foto: Bitmap, top: Int = 5): List<Candidato> {
        val entrada = preprocesar(foto)
        OnnxTensor.createTensor(env, entrada, longArrayOf(1, 3, LADO.toLong(), LADO.toLong())).use { tensor ->
            sesion.run(mapOf("pixel_values" to tensor)).use { salida ->
                @Suppress("UNCHECKED_CAST")
                val logits = (salida[0].value as Array<FloatArray>)[0]
                val m = logits.max()
                val e = logits.map { exp((it - m).toDouble()) }
                val suma = e.sum()
                return e.indices.sortedByDescending { e[it] }.take(top)
                    .map { Candidato(etiquetas[it], (e[it] / suma).toFloat()) }
            }
        }
    }

    override fun close() {
        sesion.close()
    }

    companion object {
        const val LADO = 224
        private const val CORTO = 256
        private val MEDIA = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val DESV = floatArrayOf(0.229f, 0.224f, 0.225f)

        /**
         * The model's preprocessing exactly as in training (torchvision on PIL),
         * the same as the web app: shortest side to 256 with PIL's bicubic
         * filter, centre crop 224 with Python's rounding, ImageNet mean and std.
         * Android's own bilinear scaling changed answers on small photos.
         */
        fun preprocesar(origen: Bitmap): FloatBuffer {
            val b = if (origen.config == Bitmap.Config.ARGB_8888) origen else origen.copy(Bitmap.Config.ARGB_8888, false)
            val w = b.width
            val h = b.height
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            val rgb = IntArray(w * h * 3)
            for (i in px.indices) {
                rgb[i * 3] = (px[i] shr 16) and 0xFF
                rgb[i * 3 + 1] = (px[i] shr 8) and 0xFF
                rgb[i * 3 + 2] = px[i] and 0xFF
            }
            // torchvision's Resize(int): the long side is truncated, not rounded.
            val (nw, nh) = if (w <= h) CORTO to (CORTO.toLong() * h / w).toInt() else (CORTO.toLong() * w / h).toInt() to CORTO
            val r = redimensionar(rgb, w, h, nw, nh)
            val x0 = redondear((nw - LADO) / 2.0)
            val y0 = redondear((nh - LADO) / 2.0)
            val buf = FloatBuffer.allocate(3 * LADO * LADO)
            val plano = LADO * LADO
            for (y in 0 until LADO) for (x in 0 until LADO) {
                val i = ((y0 + y) * nw + x0 + x) * 3
                val o = y * LADO + x
                buf.put(o, (r[i] / 255f - MEDIA[0]) / DESV[0])
                buf.put(plano + o, (r[i + 1] / 255f - MEDIA[1]) / DESV[1])
                buf.put(2 * plano + o, (r[i + 2] / 255f - MEDIA[2]) / DESV[2])
            }
            return buf
        }

        /** Python's round(): halves go to the even neighbour. */
        private fun redondear(x: Double): Int = Math.rint(x).toInt()

        private const val PRECISION = 22 // PIL's fixed-point bits for 8-bit images
        private const val ESCALA = (1 shl PRECISION).toDouble()

        private fun bicubica(t: Double): Double {
            val a = -0.5
            val x = abs(t)
            return when {
                x < 1 -> ((a + 2) * x - (a + 3)) * x * x + 1
                x < 2 -> (((x - 5) * x + 8) * x - 4) * a
                else -> 0.0
            }
        }

        private class Coeficientes(val limites: IntArray, val pesos: LongArray, val tam: Int)

        /** PIL's precompute_coeffs (ImagingResample, bicubic) in fixed point. */
        private fun coeficientes(entrada: Int, salida: Int): Coeficientes {
            val escala = entrada.toDouble() / salida
            val filtro = max(escala, 1.0)
            val soporte = 2 * filtro
            val tam = ceil(soporte).toInt() * 2 + 1
            val limites = IntArray(salida * 2)
            val pesos = LongArray(salida * tam)
            val k = DoubleArray(tam)
            for (xx in 0 until salida) {
                val centro = (xx + 0.5) * escala
                val xmin = max((centro - soporte + 0.5).toInt(), 0)
                val n = min((centro + soporte + 0.5).toInt(), entrada) - xmin
                var total = 0.0
                for (x in 0 until n) {
                    k[x] = bicubica((x + xmin - centro + 0.5) / filtro)
                    total += k[x]
                }
                for (x in 0 until n) {
                    val v = if (total != 0.0) k[x] / total else 0.0
                    pesos[xx * tam + x] = if (v < 0) (-0.5 + v * ESCALA).toLong() else (0.5 + v * ESCALA).toLong()
                }
                limites[xx * 2] = xmin
                limites[xx * 2 + 1] = n
            }
            return Coeficientes(limites, pesos, tam)
        }

        private fun recortar8(v: Long): Int = if (v <= 0) 0 else min((v shr PRECISION).toInt(), 255)

        /** PIL's Image.resize(BICUBIC) on RGB: a horizontal pass rounded to 8 bits, then a vertical one. */
        private fun redimensionar(rgb: IntArray, w: Int, h: Int, nw: Int, nh: Int): IntArray {
            var datos = rgb
            var ancho = w
            if (nw != w) {
                val c = coeficientes(w, nw)
                val fuera = IntArray(nw * h * 3)
                for (y in 0 until h) for (xx in 0 until nw) {
                    val xmin = c.limites[xx * 2]
                    val n = c.limites[xx * 2 + 1]
                    for (canal in 0 until 3) {
                        var s = 1L shl (PRECISION - 1)
                        for (x in 0 until n) s += datos[(y * ancho + xmin + x) * 3 + canal] * c.pesos[xx * c.tam + x]
                        fuera[(y * nw + xx) * 3 + canal] = recortar8(s)
                    }
                }
                datos = fuera
                ancho = nw
            }
            if (nh != h) {
                val c = coeficientes(h, nh)
                val fuera = IntArray(ancho * nh * 3)
                for (yy in 0 until nh) {
                    val ymin = c.limites[yy * 2]
                    val n = c.limites[yy * 2 + 1]
                    for (x in 0 until ancho) for (canal in 0 until 3) {
                        var s = 1L shl (PRECISION - 1)
                        for (y in 0 until n) s += datos[((ymin + y) * ancho + x) * 3 + canal] * c.pesos[yy * c.tam + y]
                        fuera[(yy * ancho + x) * 3 + canal] = recortar8(s)
                    }
                }
                datos = fuera
            }
            return datos
        }
    }
}
