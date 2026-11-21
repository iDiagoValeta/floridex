package es.floridex

import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A hard case from the user's own photos: an orange gerbera that the model
 * reads as sunflower 0.57, calendula 0.22, gerbera 0.11. It must not be
 * captured outright, and the choice must offer the gerbera. The photo is not
 * in the repo; push it first, or the test is skipped:
 *
 *   adb push ml/data/usuario/gerbera_naranja_01.jpg /data/local/tmp/gerbera.jpg
 */
@RunWith(AndroidJUnit4::class)
class CasoGerberaTest {
    @get:Rule
    val regla = createAndroidComposeRule<MainActivity>()

    @Test
    fun ofreceLaGerbera() {
        val inst = InstrumentationRegistry.getInstrumentation()
        // Read through the shell, which can see /data/local/tmp.
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand("cat /data/local/tmp/gerbera.jpg")).use { it.readBytes() }
        assumeTrue("gerbera.jpg not pushed", bytes.isNotEmpty())
        val foto = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val estado = regla.activity.estado
        regla.waitUntil(10_000) { estado.entradas.isNotEmpty() }
        regla.runOnUiThread { estado.analizar(foto) }
        regla.waitUntil(60_000) { estado.dialogo?.let { !it.escribiendo } == true }
        val d = estado.dialogo!!
        assertEquals("No estoy seguro. ¿Cuál de estas es?", d.texto)
        assertTrue(d.opciones.map { it.etiqueta }.toString(), d.opciones.any { it.flor?.clave == "barbeton daisy" })
        regla.onNodeWithTag("opcion-barbeton daisy").assertExists()
        // With -e capturas 1, a screenshot for review next to CapturasFlujo's.
        if (InstrumentationRegistry.getArguments().getString("capturas") != null) {
            regla.waitForIdle()
            Thread.sleep(500)
            val dir = java.io.File(inst.targetContext.getExternalFilesDir(null), "capturas").apply { mkdirs() }
            java.io.File(dir, "android-gerbera-duda.png").outputStream().use { inst.uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        regla.runOnUiThread { estado.elegir(d.opciones.lastIndex) }
    }

    companion object {
        private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
        private val dir get() = java.io.File(ctx.filesDir, "floridex")
        private val respaldo get() = java.io.File(ctx.filesDir, "floridex-antes-de-la-gerbera")

        /** The candidates become seen: keep the phone's own collection aside meanwhile. */
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
