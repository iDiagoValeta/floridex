package es.floridex

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Regression check: a reading must end however the user leaves its answer.
 * Bug reported on a phone: after a "no flower" answer the still photo stayed
 * on screen and no new photo could be taken until the app was closed. For
 * each way out (the «Cámara» pill, button B, «‹ Lista» and back, a new
 * «Foto»), the live camera must be back and a new shot or photo must be read.
 */
@RunWith(AndroidJUnit4::class)
class LecturaTest {
    @get:Rule
    val regla = createAndroidComposeRule<MainActivity>()

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val estado get() = regla.activity.estado

    private fun foto(dataset: String, flor: Boolean): Bitmap {
        val muestra = Muestra.indice()
        val m = (0 until muestra.length()).map { muestra.getJSONObject(it) }
            .first { it.getString("dataset") == dataset && (it.getString("python") != NO_FLOR) == flor && it.getDouble("p") >= 0.9 }
        return inst.context.assets.open("muestra/${m.getString("archivo")}").use { BitmapFactory.decodeStream(it) }
    }

    private fun esperarRespuesta() = regla.waitUntil(60_000) { estado.dialogo?.let { !it.escribiendo } == true }

    /** On the camera screen with the camera on and the shutter ready. */
    private fun camaraLista() {
        if (estado.pantalla != Pantalla.Camara) regla.onNodeWithTag("ir-camara").performClick()
        regla.waitForIdle()
        if (regla.onAllNodes(hasTestTag("encender")).fetchSemanticsNodes().isNotEmpty()) regla.onNodeWithTag("encender").performClick()
        regla.waitUntil(15_000) { regla.onAllNodes(hasTestTag("disparar").and(isEnabled())).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun cadaSalidaDejaLaCamaraLista() {
        inst.uiAutomation.grantRuntimePermission(inst.targetContext.packageName, android.Manifest.permission.CAMERA)
        regla.waitUntil(10_000) { estado.entradas.isNotEmpty() }
        val sinFlor = foto("no_flor", false)
        val flor = foto("oxford102", true)
        val fallos = mutableListOf<String>()
        for (salida in listOf("pastilla Cámara", "botón B", "Lista y vuelta", "nueva Foto", "Cámara durante el análisis")) {
            camaraLista()
            regla.runOnUiThread { estado.analizar(sinFlor) }
            if (salida == "Cámara durante el análisis") {
                // Leaving while the photo is still being read: its answer must never show up.
                regla.onNodeWithTag("ir-camara").performClick()
                Thread.sleep(2500)
                regla.waitForIdle()
                if (estado.dialogo != null) fallos += "$salida: the abandoned reading still answered"
            } else {
                esperarRespuesta()
                assertTrue(estado.dialogo!!.texto, estado.dialogo!!.texto.startsWith("No veo ninguna flor"))
            }
            when (salida) {
                "pastilla Cámara" -> regla.onNodeWithTag("ir-camara").performClick()
                "botón B" -> regla.onNodeWithTag("boton-b").performClick()
                "Lista y vuelta" -> {
                    regla.onNodeWithTag("boton-b").performClick()
                    regla.onNodeWithTag("c-volver").performClick()
                    regla.onNodeWithTag("ir-camara").performClick()
                }
                "nueva Foto" -> regla.runOnUiThread { estado.analizar(flor) }
            }
            regla.waitForIdle()
            if (salida == "nueva Foto") {
                val leida = runCatching { esperarRespuesta() }.isSuccess
                if (!leida) fallos += "$salida: the new photo was not read"
            } else {
                if (estado.fotoFija != null) fallos += "$salida: the still photo is still on screen"
                val lista = runCatching { camaraLista() }.isSuccess
                if (!lista) {
                    fallos += "$salida: the shutter is not ready"
                } else {
                    regla.onNodeWithTag("disparar").assertIsEnabled().performClick()
                    if (runCatching { esperarRespuesta() }.isFailure) fallos += "$salida: the new shot was not read"
                }
            }
            // Leave the answer as a person would; a stuck screen is reset for the next round.
            if (estado.dialogo != null) regla.onNodeWithTag("boton-b").performClick()
            regla.waitForIdle()
        }
        assertEquals(fallos.joinToString("\n"), 0, fallos.size)
        assertNull(estado.dialogo)
    }

    companion object {
        private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
        private val dir get() = File(ctx.filesDir, "floridex")
        private val respaldo get() = File(ctx.filesDir, "floridex-antes-de-la-lectura")

        /** Captures land in the collection: keep the phone's own one aside meanwhile. */
        @BeforeClass
        @JvmStatic
        fun apartar() {
            if (dir.exists() && !respaldo.exists()) dir.renameTo(respaldo)
            dir.deleteRecursively()
        }

        @AfterClass
        @JvmStatic
        fun devolver() {
            dir.deleteRecursively()
            if (respaldo.exists()) respaldo.renameTo(dir)
        }
    }
}
