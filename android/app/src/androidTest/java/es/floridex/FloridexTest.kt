package es.floridex

import android.graphics.BitmapFactory
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Floridex flow on the device, from an empty collection: everything starts
 * unseen; a clear photo captures its flower and opens its entry; the capture
 * is on disk; when unsure, the candidates become seen and the user picks one;
 * filters, search and the pad; an unseen entry gives clues, not the name; and
 * releasing a capture leaves the flower seen.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class FloridexTest {
    @get:Rule
    val regla = createAndroidComposeRule<MainActivity>()

    private val estado get() = regla.activity.estado
    private fun texto(tag: String) = regla.onNodeWithTag(tag, useUnmergedTree = true)
    private fun esperarTexto(tag: String, prefijo: String) =
        regla.waitUntil(20_000) { regla.onAllNodes(hasTestTag(tag).and(hasText(prefijo, substring = true).or(hasAnyDescendant(hasText(prefijo, substring = true)))), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun capturarVerFiltrarYSoltar() {
        val inst = InstrumentationRegistry.getInstrumentation()
        regla.waitUntil(10_000) { estado.entradas.isNotEmpty() }
        // The test starts in the normal view; the phone's own choice is put back at the end.
        val vistaPrevia = estado.vistaPixel
        if (vistaPrevia) regla.runOnUiThread { estado.alternarVista() }
        val total = estado.entradas.size
        texto("capturadas").assertTextEquals("000")
        texto("vistas").assertTextEquals("000")
        assertEquals(total, estado.entradas.count { estado.estadoDe(it.clave) == EstadoFlor.SINVER })

        // A clear photo captures its flower.
        val muestra = Muestra.indice()
        val m = (0 until muestra.length()).map { muestra.getJSONObject(it) }.first { it.getString("clave") == it.getString("python") && it.getString("clave") in estado.porClave }
        val a = estado.porClave.getValue(m.getString("clave"))
        val foto = inst.context.assets.open("muestra/${m.getString("archivo")}").use { BitmapFactory.decodeStream(it) }
        regla.runOnUiThread { estado.analizar(foto) }
        regla.waitUntil(60_000) { estado.dialogo?.let { !it.escribiendo } == true }
        assertTrue(estado.dialogo!!.texto, estado.dialogo!!.texto.startsWith("¡Capturada! ${a.nombre}"))
        regla.onNodeWithTag("boton-a").performClick()
        texto("entrada-nombre").assertTextEquals(a.nombre)
        texto("sello").assertTextEquals("¡NUEVA!")
        // The reference photo's author, whenever there is a photo.
        if (a.credito != null) texto("credito").assertTextEquals("Foto: ${a.credito!!.autoria}, iNaturalist")
        else regla.onNodeWithTag("credito").assertDoesNotExist()
        regla.onNodeWithTag("pagina-foto").performClick()
        esperarTexto("hallazgo", "Capturada el ")
        regla.onNodeWithTag("tu-foto").assertExists()
        // Pixel view: the photo in greens, then the sprite in the list; back to normal.
        regla.onNodeWithTag("vista").performClick()
        regla.onNodeWithTag("tu-foto-pixel").assertExists()
        regla.onNodeWithTag("boton-b").performClick()
        regla.onAllNodesWithTag("pixel-${a.clave}", useUnmergedTree = true).onFirst().assertExists()
        regla.onNodeWithTag("vista").performClick()
        regla.onAllNodesWithTag("foto-${a.clave}", useUnmergedTree = true).onFirst().assertExists()
        texto("capturadas").assertTextEquals("001")
        // On disk, for the next launch.
        regla.waitUntil(5_000) { a.clave in Progreso(inst.targetContext).leer().first }

        // Unsure: two candidates, both seen; the user picks the first.
        val (b, c) = estado.entradas.filter { it.clave != a.clave }.drop(5).take(2)
        val archivo = File(inst.targetContext.cacheDir, "prueba.jpg").also { f -> f.outputStream().use { foto.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) } }
        regla.runOnUiThread { estado.resolver(listOf(Candidato(b.clave, 0.31f), Candidato(c.clave, 0.22f), Candidato(NO_FLOR, 0.12f)), archivo) }
        regla.waitUntil(10_000) { estado.dialogo?.let { !it.escribiendo } == true }
        assertEquals("No estoy seguro. ¿Cuál de estas es?", estado.dialogo!!.texto)
        assertEquals(3, estado.dialogo!!.opciones.size)
        texto("vistas").assertTextEquals("003")
        regla.onNodeWithTag("opcion-${b.clave}").performClick()
        regla.waitUntil(10_000) { estado.dialogo?.texto?.startsWith("¡Capturada! ${b.nombre}") == true && !estado.dialogo!!.escribiendo }
        regla.onNodeWithTag("boton-a").performClick()
        regla.onNodeWithTag("pagina-foto").performClick()
        esperarTexto("hallazgo", "elegida por ti")
        regla.onNodeWithTag("boton-b").performClick()
        texto("capturadas").assertTextEquals("002")
        assertEquals(EstadoFlor.VISTA, estado.estadoDe(c.clave))

        // Filters and search.
        regla.onNodeWithTag("ver-filtros").performClick()
        regla.onNodeWithTag("f-estado").performClick()
        regla.onNodeWithTag("f-estado-CAPTURADA").performClick()
        texto("recuento").assertTextEquals("2 entradas")
        regla.onNodeWithTag("quitar-filtros").performClick()
        texto("recuento").assertTextEquals("$total entradas")
        regla.onNodeWithTag("buscador").performTextInput(a.nombre.uppercase())
        regla.waitUntil(5_000) { estado.visibles.any { it.clave == a.clave } && estado.visibles.size < total }
        regla.onNodeWithTag("buscador").performTextClearance()
        val sinVer = estado.entradas.first { estado.estadoDe(it.clave) == EstadoFlor.SINVER }
        regla.onNodeWithTag("buscador").performTextInput(sinVer.nombre)
        regla.waitUntil(5_000) { estado.visibles.none { it.clave == sinVer.clave } }
        regla.onNodeWithTag("buscador").performTextClearance()
        regla.onNodeWithTag("ver-filtros").performClick()

        // The pad moves the cursor.
        regla.runOnUiThread { while (estado.cursor > 0) estado.moverCursor(-1) }
        regla.onNodeWithTag("cruz-abajo").performClick()
        regla.onNodeWithTag("cruz-abajo").performClick()
        regla.onNodeWithTag("cruz-arriba").performClick()
        assertEquals(1, estado.cursor)

        // An unseen entry: clues, no name.
        regla.onNodeWithTag("lista").performScrollToNode(hasTestTag("fila-${sinVer.clave}"))
        regla.onNodeWithTag("fila-${sinVer.clave}").performClick()
        texto("entrada-nombre").assertTextEquals("?????")
        regla.onNodeWithTag("pistas").assertExists()
        regla.onNodeWithTag("boton-b").performClick()

        // Releasing a capture leaves the flower seen.
        regla.onNodeWithTag("lista").performScrollToNode(hasTestTag("fila-${a.clave}"))
        regla.onNodeWithTag("fila-${a.clave}").performClick()
        regla.onNodeWithTag("pagina-foto").performClick()
        // Below a tall photo the button is off screen: scroll to it, as a person would.
        regla.onNodeWithTag("olvidar").performScrollTo().performClick()
        // It asks first: the photo cannot be brought back.
        regla.waitUntil(5_000) { estado.dialogo?.let { !it.escribiendo } == true }
        assertTrue(estado.dialogo!!.texto, estado.dialogo!!.texto.startsWith("¿Quitar ${a.nombre} del Floridex?"))
        assertEquals(EstadoFlor.CAPTURADA, estado.estadoDe(a.clave))
        regla.onNodeWithTag("opcion-si").performClick()
        regla.waitUntil(5_000) { estado.estadoDe(a.clave) == EstadoFlor.VISTA }
        regla.onNodeWithTag("boton-b").performClick()
        texto("capturadas").assertTextEquals("001")
        texto("vistas").assertTextEquals("003")
        // Progress of a flower the model no longer lists was kept, photo included.
        val (enDisco, vistasEnDisco) = Progreso(inst.targetContext).leer()
        assertTrue(enDisco.keys.toString(), AJENA in enDisco && enDisco.getValue(AJENA).foto.exists())
        assertTrue(vistasEnDisco.keys.toString(), AJENA in vistasEnDisco)
        assertTrue(AJENA !in estado.capturas)
        if (vistaPrevia) regla.runOnUiThread { estado.alternarVista() }
    }

    companion object {
        private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
        private val dir get() = File(ctx.filesDir, "floridex")
        private val respaldo get() = File(ctx.filesDir, "floridex-antes-de-la-prueba")

        /** Starts from an empty collection, keeping the phone's own one aside; runs before the activity is launched. */
        /** A label no model ships: stands for a flower renamed or merged by a later model. */
        const val AJENA = "flor que ya no existe"

        @BeforeClass
        @JvmStatic
        fun apartar() {
            if (dir.exists() && !respaldo.exists()) dir.renameTo(respaldo)
            dir.deleteRecursively()
            // Its capture and its photo must survive this whole session of the app.
            dir.mkdirs()
            File(dir, "ajena.jpg").writeBytes(byteArrayOf(1, 2, 3))
            File(dir, "progreso.json").writeText(
                """{"capturas":[{"clave":"$AJENA","fecha":1,"foto":"ajena.jpg","coincidencia":0.9}],"vistas":[{"clave":"$AJENA","fecha":1}]}""",
            )
        }

        /** Puts the phone's own collection back. */
        @AfterClass
        @JvmStatic
        fun devolver() {
            dir.deleteRecursively()
            if (respaldo.exists()) respaldo.renameTo(dir)
        }
    }
}
