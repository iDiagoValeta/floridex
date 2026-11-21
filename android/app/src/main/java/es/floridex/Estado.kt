package es.floridex

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * The model is calibrated: its confidence tracks how often it is right. At 0.7
 * or more it captures directly (about 89 % of photos, 95 % right); below, the
 * user picks among the top candidates. Hard look-alikes (an orange gerbera read
 * as sunflower 0.57, calendula 0.22, gerbera 0.11) land here. Same as the web.
 */
const val UMBRAL_DUDA = 0.7f

/** Candidates offered when unsure, the model's top ones. */
const val MAX_CANDIDATAS = 3
const val ANALISIS_MS = 1400L

/** The model's reject class: photos with no flower in them. It never gets an entry. */
const val NO_FLOR = "no flower"

enum class EstadoFlor { CAPTURADA, VISTA, SINVER }
enum class Sello { NUEVA, REPETIDA }
enum class Pagina(val titulo: String) { DATOS("Datos"), CAMPO("Campo"), SABER("Saber"), FOTO("Foto") }
enum class Tecla { ARRIBA, ABAJO, IZQUIERDA, DERECHA, A, B }

sealed interface Pantalla {
    data object Lista : Pantalla
    data class Entrada(val clave: String, val sello: Sello? = null) : Pantalla
    data object Camara : Pantalla
}

/** A choice in the text box. [id] names it for tests when it is not a flower. */
class Opcion(val etiqueta: String, val detalle: String? = null, val flor: Flor? = null, val id: String? = null, val accion: () -> Unit)

/** A message in the text box, typed out letter by letter, maybe with a menu of choices. */
class Dialogo(val texto: String, val opciones: List<Opcion> = emptyList(), val alCerrar: (() -> Unit)? = null) {
    var letras by mutableIntStateOf(0)
    var elegida by mutableIntStateOf(0)
    val escribiendo get() = letras < texto.length
}

data class Filtros(
    val estado: EstadoFlor? = null,
    val familia: String? = null,
    val grupo: String? = null,
    val estacion: String? = null,
    val color: String? = null,
    val busqueda: String = "",
) {
    val activos get() = this != Filtros()
}

fun numero(n: Int) = n.toString().padStart(3, '0')
fun normal(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}"), "").lowercase(Locale.ROOT)
fun porcentaje(p: Float) = if (p >= 0.995f) "más del 99 %" else "${Math.round(p * 100)} %"

class Estado(app: Application) : AndroidViewModel(app) {
    val sonido = Sonido(app)
    private val progreso = Progreso(app)
    private val clasificador = viewModelScope.async(Dispatchers.Default) { Clasificador(app) }

    /** Entries: cards of the flowers the shipped model can recognise, by number. */
    val entradas: List<Flor> = etiquetasDelModelo(app).toSet().let { e -> cargarFlores(app).filter { it.clave in e }.sortedBy { it.numero } }
    val porClave = entradas.associateBy { it.clave }
    val familias = entradas.groupingBy { it.familia }.eachCount().toSortedMap()

    private val prefs = app.getSharedPreferences("floridex", android.content.Context.MODE_PRIVATE)

    /** Pixel view: captured flowers as their sprite, photos in greens. Normal: photos as they are. Kept on the phone. */
    var vistaPixel by mutableStateOf(prefs.getBoolean("vista_pixel", false))
        private set

    fun alternarVista() {
        vistaPixel = !vistaPixel
        prefs.edit().putBoolean("vista_pixel", vistaPixel).apply()
        sonido.cursor()
    }

    val capturas = mutableStateMapOf<String, Captura>()
    val vistas = mutableStateMapOf<String, Long>()

    var pantalla by mutableStateOf<Pantalla>(Pantalla.Lista)
        private set
    var pagina by mutableStateOf(Pagina.DATOS)
        private set
    var dialogo by mutableStateOf<Dialogo?>(null)
        private set
    var filtros by mutableStateOf(Filtros())
    var verFiltros by mutableStateOf(false)
    var cursor by mutableIntStateOf(0)
        private set
    /** Bumped when the pad asks the entry to scroll; the screen animates it. */
    var desplazar by mutableIntStateOf(0)
        private set
    /** Bumped when button A asks the camera to shoot. */
    var disparar by mutableIntStateOf(0)
        private set
    /**
     * A photo being read, from the shot until the user is done with its answer.
     * Its end is one transition, [terminarLectura], that every way out goes
     * through: a new reading, entering or leaving the camera screen and any
     * dismissal of the text box. A still photo can never outlive its reading.
     */
    private class Lectura(val foto: Bitmap) {
        /** The photo as a file, kept until it is captured or let go. */
        var temporal: File? = null
        var trabajo: Job? = null
    }
    private var lectura by mutableStateOf<Lectura?>(null)

    /** The photo on the camera screen while it is read and its answer is open. */
    val fotoFija: Bitmap? get() = lectura?.foto
    var analizando by mutableStateOf(false)
        private set
    var destello by mutableIntStateOf(0)
        private set
    var aviso by mutableStateOf<String?>(null)

    val visibles by derivedStateOf {
        val f = filtros
        val q = normal(f.busqueda.trim())
        val n = q.toIntOrNull()
        entradas.filter { e ->
            (f.estado == null || estadoDe(e.clave) == f.estado) &&
                (f.familia == null || e.familia == f.familia) &&
                (f.grupo == null || e.grupo == f.grupo) &&
                (f.estacion == null || f.estacion in e.estaciones) &&
                (f.color == null || e.color == f.color) &&
                when {
                    q.isEmpty() -> true
                    n != null -> e.numero == n
                    // Names of flowers never seen are not searchable: no spoilers.
                    estadoDe(e.clave) == EstadoFlor.SINVER -> false
                    else -> normal("${e.nombre} ${e.cientifico} ${e.otrosNombres.joinToString(" ")}").contains(q)
                }
        }
    }

    val vistasTotal by derivedStateOf { (vistas.keys + capturas.keys).size }

    /**
     * Progress for flowers the shipped model does not list (a label renamed or
     * merged by a later model): not shown, but kept on disk with its photo, so
     * a model change never loses a capture.
     */
    private var ajenas: Pair<Map<String, Captura>, Map<String, Long>> = emptyMap<String, Captura>() to emptyMap()

    init {
        val arranque = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) {
            // Photos of readings that never ended (the app was closed meanwhile);
            // only older ones, a photo shared right at launch is already being read.
            app.cacheDir.listFiles { f -> f.name.startsWith("foto-") && f.name.endsWith(".jpg") && f.lastModified() < arranque }?.forEach { it.delete() }
            val (c, v) = progreso.leer()
            ajenas = c.filterKeys { it !in porClave } to v.filterKeys { it !in porClave }
            withContext(Dispatchers.Main) {
                capturas.putAll(c.filterKeys { it in porClave })
                vistas.putAll(v.filterKeys { it in porClave })
            }
        }
    }

    fun estadoDe(clave: String) = when {
        clave in capturas -> EstadoFlor.CAPTURADA
        clave in vistas -> EstadoFlor.VISTA
        else -> EstadoFlor.SINVER
    }

    private suspend fun guardar() {
        val (c, v) = withContext(Dispatchers.Main) { capturas.toMap() to vistas.toMap() }
        withContext(Dispatchers.IO) { progreso.escribir(ajenas.first + c, ajenas.second + v) }
    }

    /** Ends the reading, whatever its state: analysis stopped, still photo gone, its file deleted. */
    private fun terminarLectura() {
        val l = lectura ?: return
        lectura = null
        l.trabajo?.cancel()
        analizando = false
        l.temporal?.let { f -> viewModelScope.launch(Dispatchers.IO) { f.delete() } }
    }

    private fun borrar(f: File) {
        viewModelScope.launch(Dispatchers.IO) { f.delete() }
    }

    // Navigation ---------------------------------------------------------------------
    /**
     * Every change of screen, and every entry to the camera screen, ends the
     * reading and drops the text box: an answer belongs to the photo it was about.
     */
    fun ir(p: Pantalla) {
        if (p != pantalla || p == Pantalla.Camara) {
            dialogo = null
            terminarLectura()
            aviso = null
        }
        pantalla = p
        if (p is Pantalla.Entrada) pagina = Pagina.DATOS
    }

    fun abrirEntrada(clave: String, sello: Sello? = null) {
        if (pantalla !is Pantalla.Entrada) sonido.abrir()
        visibles.indexOfFirst { it.clave == clave }.takeIf { it >= 0 }?.let { cursor = it }
        ir(Pantalla.Entrada(clave, sello))
    }

    fun volverALista() {
        sonido.volver()
        ir(Pantalla.Lista)
    }

    fun abrirCamara() {
        sonido.abrir()
        ir(Pantalla.Camara)
    }

    fun verPagina(p: Pagina) {
        val e = (pantalla as? Pantalla.Entrada) ?: return
        if (estadoDe(e.clave) != EstadoFlor.CAPTURADA) return
        if (p != pagina) sonido.cursor()
        pagina = p
    }

    fun moverCursor(paso: Int) {
        val n = visibles.size
        if (n == 0) return
        val nuevo = (cursor + paso).coerceIn(0, n - 1)
        if (nuevo != cursor) sonido.cursor()
        cursor = nuevo
    }

    fun cambiarFiltros(f: Filtros) {
        filtros = f
        cursor = 0
    }

    /** The device's pad and buttons, the keyboard and the system back button. Returns whether it was used. */
    fun tecla(t: Tecla): Boolean {
        dialogo?.let { d ->
            when (t) {
                Tecla.A -> aceptarDialogo()
                Tecla.ARRIBA, Tecla.ABAJO -> if (d.opciones.isNotEmpty() && !d.escribiendo) {
                    d.elegida = (d.elegida + (if (t == Tecla.ARRIBA) -1 else 1) + d.opciones.size) % d.opciones.size
                    sonido.cursor()
                }
                Tecla.B -> when {
                    d.escribiendo -> d.letras = d.texto.length
                    d.opciones.isNotEmpty() -> elegir(d.opciones.lastIndex)
                    else -> cerrarDialogo()
                }
                else -> return false
            }
            return true
        }
        when (val p = pantalla) {
            Pantalla.Lista -> when (t) {
                Tecla.ARRIBA -> moverCursor(-1)
                Tecla.ABAJO -> moverCursor(1)
                Tecla.IZQUIERDA -> moverCursor(-10)
                Tecla.DERECHA -> moverCursor(10)
                Tecla.A -> visibles.getOrNull(cursor)?.let { abrirEntrada(it.clave) }
                Tecla.B -> return false
            }
            is Pantalla.Entrada -> when (t) {
                Tecla.IZQUIERDA -> verPagina(Pagina.entries[(pagina.ordinal + Pagina.entries.size - 1) % Pagina.entries.size])
                Tecla.DERECHA, Tecla.A -> verPagina(Pagina.entries[(pagina.ordinal + 1) % Pagina.entries.size])
                Tecla.ARRIBA -> desplazar--
                Tecla.ABAJO -> desplazar++
                Tecla.B -> volverALista()
            }
            Pantalla.Camara -> when (t) {
                Tecla.B -> if (!analizando) volverALista()
                Tecla.A -> if (!analizando) disparar++
                else -> return false
            }
        }
        return true
    }

    // Text box -------------------------------------------------------------------------
    fun decir(texto: String, opciones: List<Opcion> = emptyList(), alCerrar: (() -> Unit)? = null) {
        dialogo = Dialogo(texto, opciones, alCerrar)
    }

    fun aceptarDialogo() {
        val d = dialogo ?: return
        when {
            d.escribiendo -> d.letras = d.texto.length
            d.opciones.isNotEmpty() -> elegir(d.elegida)
            else -> {
                sonido.cursor()
                cerrarDialogo()
            }
        }
    }

    fun elegir(i: Int) {
        val d = dialogo ?: return
        dialogo = null
        d.opciones.getOrNull(i)?.accion?.invoke()
        // A choice that asks nothing more ends the reading («Ninguna», or a capture that took the photo).
        if (dialogo == null && !analizando) terminarLectura()
    }

    private fun cerrarDialogo() {
        val d = dialogo
        dialogo = null
        d?.alCerrar?.invoke()
        if (dialogo == null && !analizando) terminarLectura()
    }

    // Identifying and capturing ----------------------------------------------------------
    fun analizar(foto: Bitmap) {
        // One reading at a time: «Foto», the shutter and shares wait for the answer.
        if (analizando) return
        // Ends any previous reading and its answer.
        ir(Pantalla.Camara)
        val l = Lectura(foto)
        lectura = l
        analizando = true
        l.trabajo = viewModelScope.launch {
            try {
                val inicio = System.currentTimeMillis()
                val candidatos = withContext(Dispatchers.Default) { clasificador.await().clasificar(foto) }
                l.temporal = withContext(Dispatchers.IO) { fotoTemporal(getApplication(), foto) }
                delay((ANALISIS_MS - (System.currentTimeMillis() - inicio)).coerceAtLeast(0))
                if (lectura !== l) return@launch
                analizando = false
                resolver(candidatos, l.temporal!!)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (lectura !== l) return@launch
                terminarLectura()
                sonido.error()
                aviso = "No he podido analizar la foto. Inténtalo otra vez."
            }
        }
    }

    /** After a reading: capture when sure, or let the user pick among the candidates. */
    fun resolver(candidatos: List<Candidato>, archivo: File) {
        val mejor = candidatos.first()
        val sinFlor = mejor.clave == NO_FLOR
        if (!sinFlor && mejor.probabilidad >= UMBRAL_DUDA && mejor.clave in porClave) {
            capturar(mejor.clave, archivo, mejor.probabilidad)
            return
        }
        val umbral = if (sinFlor) 0.05f else 0.01f
        val opciones = candidatos.filter { it.clave != NO_FLOR && it.probabilidad >= umbral && it.clave in porClave }.take(MAX_CANDIDATAS)
        // Candidates shown to the user become seen, as in any field guide you leaf through.
        val ahora = System.currentTimeMillis()
        opciones.forEach { if (it.clave !in capturas && it.clave !in vistas) vistas[it.clave] = ahora }
        viewModelScope.launch { guardar() }
        sonido.vista()
        val menu = opciones.map { c ->
            val f = porClave.getValue(c.clave)
            Opcion(f.nombre, porcentaje(c.probabilidad), f) { capturar(f.clave, archivo, null) }
        }.let { if (it.isEmpty()) it else it + Opcion("Ninguna") { terminarLectura() } }
        decir(
            when {
                !sinFlor -> "No estoy seguro. ¿Cuál de estas es?"
                menu.isNotEmpty() -> "No veo ninguna flor clara. Si la hay, ¿es alguna de estas?"
                else -> "No veo ninguna flor. Acércate a una sola flor, con buena luz, y que ocupe el centro."
            },
            menu,
            ::terminarLectura,
        )
    }

    fun capturar(clave: String, archivo: File, coincidencia: Float?) {
        val f = porClave[clave] ?: return
        // The capture takes the photo file over; the reading itself is over.
        lectura?.let { if (it.temporal == archivo) it.temporal = null }
        terminarLectura()
        if (clave in capturas) {
            borrar(archivo)
            sonido.abrir()
            decir("${f.nombre} ya estaba en tu Floridex.") { abrirEntrada(clave, Sello.REPETIDA) }
            return
        }
        viewModelScope.launch {
            val foto = runCatching { withContext(Dispatchers.IO) { progreso.guardarFoto(archivo) } }.getOrElse {
                sonido.error()
                decir("No he podido guardarla: puede que el teléfono no tenga espacio.")
                return@launch
            }
            capturas[clave] = Captura(clave, System.currentTimeMillis(), foto, coincidencia)
            guardar()
            borrar(archivo)
            destello++
            sonido.captura()
            decir("¡Capturada! ${f.nombre} se ha registrado en tu Floridex con el número ${numero(f.numero)}.") {
                abrirEntrada(clave, Sello.NUEVA)
            }
        }
    }

    /** Asks before forgetting a capture: its photo cannot be brought back. */
    fun pedirSoltar(clave: String) {
        val f = porClave[clave] ?: return
        decir(
            "¿Quitar ${f.nombre} del Floridex? Se borrará tu foto y quedará como vista.",
            listOf(Opcion("Sí, quitar", id = "si") { soltar(clave) }, Opcion("No", id = "no") {}),
        )
    }

    /** Forgets a capture and its photo; the flower stays seen. */
    fun soltar(clave: String) {
        val c = capturas.remove(clave) ?: return
        vistas[clave] = System.currentTimeMillis()
        viewModelScope.launch {
            guardar()
            withContext(Dispatchers.IO) { c.foto.delete() }
        }
        ir(Pantalla.Entrada(clave))
    }
}
