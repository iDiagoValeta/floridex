package es.floridex

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertTrue
import kotlin.math.abs
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the on-device classifier over the held-out test photos in
 * assets/muestra (50 per dataset plus 50 non-flower photos, written by
 * shared/muestra.py for the shipped model) and checks it against the ground
 * truth and against the predictions the same ONNX model made in Python on the
 * same files, so a preprocessing bug in the Kotlin port cannot hide behind a
 * decent accuracy. The accuracy bar is Python's own on the sample, not a fixed
 * number, so it holds for any model dropped in.
 */
@RunWith(AndroidJUnit4::class)
class ClasificadorTest {
    @Test
    fun coincideConPythonYAcierta() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val pruebas = inst.context.assets
        val muestra = Muestra.indice()
        val clasificador = Clasificador(inst.targetContext)
        var aciertos = 0
        var aciertosPython = 0
        var iguales = 0
        var decisiones = 0
        var ms = 0L
        for (i in 0 until muestra.length()) {
            val m = muestra.getJSONObject(i)
            val foto = pruebas.open("muestra/${m.getString("archivo")}").use { BitmapFactory.decodeStream(it) }
            val t0 = System.currentTimeMillis()
            val mejor = clasificador.clasificar(foto).first()
            val top = mejor.clave
            // The decision (capture or ask) must match Python's unless Python's
            // confidence is within 0.05 of the line, where decoding can tip it.
            val p = m.getDouble("p")
            if (m.getString("python") != NO_FLOR && abs(p - UMBRAL_DUDA) > 0.05 && (p >= UMBRAL_DUDA) != (top != NO_FLOR && mejor.probabilidad >= UMBRAL_DUDA)) {
                decisiones++
                Log.i(TAG, "${m.getString("archivo")}: android ${top} ${mejor.probabilidad}, python ${m.getString("python")} $p: different decision")
            }
            ms += System.currentTimeMillis() - t0
            if (top == m.getString("clave")) aciertos++
            if (m.getString("python") == m.getString("clave")) aciertosPython++
            if (top == m.getString("python")) iguales++
            else Log.i(TAG, "${m.getString("dataset")}: android=$top python=${m.getString("python")} true=${m.getString("clave")}")
        }
        val n = muestra.length()
        Log.i(TAG, "correct $aciertos/$n (Python $aciertosPython/$n), same as Python $iguales/$n, different decisions $decisiones, ${ms / n} ms per photo")
        assertTrue("decisions different from Python: $decisiones", decisiones == 0)
        assertTrue("correct $aciertos/$n, Python $aciertosPython/$n", aciertos >= aciertosPython - 0.01 * n)
        assertTrue("same as Python $iguales/$n", iguales >= 0.97 * n)
    }

    @Test
    fun todasLasClasesTienenFicha() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val flores = cargarFlores(ctx).map { it.clave }.toSet()
        val sinFicha = Clasificador(ctx).use { c -> c.etiquetas.filter { it != NO_FLOR && it !in flores } }
        assertTrue("labels without a card: $sinFicha", sinFicha.isEmpty())
    }

    companion object {
        const val TAG = "FloridexTest"
    }
}
