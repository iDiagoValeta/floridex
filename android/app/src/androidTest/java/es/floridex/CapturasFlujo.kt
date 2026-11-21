package es.floridex

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Screenshots of the whole Floridex flow, in both views, for review. Runs only
 * when asked:
 *
 *   ./gradlew connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=es.floridex.CapturasFlujo \
 *     -Pandroid.testInstrumentationRunnerArguments.capturas=1
 *   adb pull /sdcard/Android/data/es.floridex/files/capturas
 *
 * The "unsure" step injects fixed candidates, as FloridexTest does.
 */
@RunWith(AndroidJUnit4::class)
class CapturasFlujo {
    @get:Rule
    val regla = createAndroidComposeRule<MainActivity>()

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val estado get() = regla.activity.estado
    private var n = 0
    private val salida by lazy { File(inst.targetContext.getExternalFilesDir(null), "capturas").apply { deleteRecursively(); mkdirs() } }

    private fun foto(nombre: String, espera: Long = 500) {
        regla.waitForIdle()
        Thread.sleep(espera)
        val b = inst.uiAutomation.takeScreenshot()
        File(salida, "android-${(++n).toString().padStart(2, '0')}-$nombre.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun clic(tag: String) = regla.onNodeWithTag(tag).performClick()
    private fun esperarDialogo() = regla.waitUntil(60_000) { estado.dialogo?.let { !it.escribiendo } == true }
    private fun ui(f: () -> Unit) = regla.runOnUiThread(f)

    @Test
    fun recorrido() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("capturas") != null)
        inst.uiAutomation.grantRuntimePermission(inst.targetContext.packageName, android.Manifest.permission.CAMERA)
        regla.waitUntil(10_000) { estado.entradas.isNotEmpty() }
        if (estado.vistaPixel) ui { estado.alternarVista() }

        val muestra = Muestra.indice()
        val fotos = (0 until muestra.length()).map { muestra.getJSONObject(it) }
        fun bien(d: String) = fotos.filter { it.getString("dataset") == d && it.getString("clave") == it.getString("python") }
        fun bitmap(o: JSONObject) = inst.context.assets.open("muestra/${o.getString("archivo")}").use { BitmapFactory.decodeStream(it) }
        val primera = bien("oxford102").first()
        val otras = listOf("swedish", "tf_flowers", "oxford17").flatMap { bien(it).take(2) }
        val sinFlor = bien("no_flor").first()

        foto("inicio-todo-sin-ver", 1500)
        clic("ver-filtros")
        foto("filtros")
        clic("ver-filtros")

        clic("ir-camara")
        foto("camara-en-directo", 3000)
        ui { estado.analizar(bitmap(primera)) }
        foto("analizando", 300)
        esperarDialogo()
        foto("capturada")
        clic("boton-a")
        foto("entrada-datos")
        for (p in listOf("campo", "saber", "foto")) {
            clic("pagina-$p")
            foto("entrada-$p")
        }
        clic("boton-b")

        for (o in otras) {
            ui { estado.analizar(bitmap(o)) }
            esperarDialogo()
            clic("boton-b")
            clic("boton-b")
        }
        ui { estado.analizar(bitmap(otras.first())) }
        esperarDialogo()
        foto("ya-la-tenias")
        clic("boton-b")
        clic("boton-b")

        val capturadas = otras.map { it.getString("clave") } + primera.getString("clave")
        val (a, b) = estado.entradas.filter { it.clave !in capturadas }.drop(8).take(2)
        val archivo = File(inst.targetContext.cacheDir, "capturas.jpg").also { f -> f.outputStream().use { bitmap(otras[1]).compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        ui {
            estado.ir(Pantalla.Camara)
            estado.resolver(listOf(Candidato(a.clave, 0.34f), Candidato(b.clave, 0.21f), Candidato(NO_FLOR, 0.1f)), archivo)
        }
        esperarDialogo()
        foto("duda-cual-es")
        clic("opcion-ninguna")

        ui { estado.analizar(bitmap(sinFlor)) }
        esperarDialogo()
        foto("sin-flor")
        clic("boton-b")
        clic("boton-b")

        foto("lista-con-progreso")
        regla.onNodeWithTag("lista").performScrollToNode(hasTestTag("fila-${a.clave}"))
        clic("fila-${a.clave}")
        foto("entrada-vista")
        clic("boton-b")
        val sinVer = estado.entradas.first { estado.estadoDe(it.clave) == EstadoFlor.SINVER }
        regla.onNodeWithTag("lista").performScrollToNode(hasTestTag("fila-${sinVer.clave}"))
        clic("fila-${sinVer.clave}")
        foto("entrada-sin-ver")
        clic("boton-b")
        clic("ver-filtros")
        clic("f-estado")
        clic("f-estado-CAPTURADA")
        foto("filtro-capturadas")
        clic("quitar-filtros")
        clic("ver-filtros")

        clic("vista")
        foto("pixel-lista")
        val c = estado.entradas.first { estado.estadoDe(it.clave) == EstadoFlor.CAPTURADA }
        regla.onNodeWithTag("lista").performScrollToNode(hasTestTag("fila-${c.clave}"))
        clic("fila-${c.clave}")
        foto("pixel-entrada")
        clic("pagina-foto")
        foto("pixel-tu-foto")
        clic("boton-b")
        clic("ir-camara")
        foto("pixel-camara", 3000)
        clic("boton-b")
        clic("vista")
    }

    companion object {
        private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
        private val dir get() = File(ctx.filesDir, "floridex")
        private val respaldo get() = File(ctx.filesDir, "floridex-antes-de-las-capturas")

        /** Starts from an empty collection, keeping the phone's own one aside. */
        @BeforeClass
        @JvmStatic
        fun apartar() {
            if (InstrumentationRegistry.getArguments().getString("capturas") == null) return
            if (dir.exists() && !respaldo.exists()) dir.renameTo(respaldo)
            dir.deleteRecursively()
        }

        /** Puts the phone's own collection back. */
        @AfterClass
        @JvmStatic
        fun devolver() {
            if (InstrumentationRegistry.getArguments().getString("capturas") == null) return
            dir.deleteRecursively()
            if (respaldo.exists()) respaldo.renameTo(dir)
        }
    }
}
