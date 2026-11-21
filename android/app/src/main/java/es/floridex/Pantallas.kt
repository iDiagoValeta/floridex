package es.floridex

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private val ESTACION = mapOf("primavera" to "Primavera", "verano" to "Verano", "otoño" to "Otoño", "invierno" to "Invierno")
private val COLOR = linkedMapOf(
    "blanco" to "Blanco", "amarillo" to "Amarillo", "naranja" to "Naranja", "rojo" to "Rojo",
    "rosa" to "Rosa", "morado" to "Morado", "azul" to "Azul", "verde" to "Verde",
)

@Composable
private fun T(
    texto: String,
    modifier: Modifier = Modifier,
    tam: Int = 16,
    color: androidx.compose.ui.graphics.Color = Paleta.g0,
    negrita: Boolean = false,
    lineas: Int = Int.MAX_VALUE,
) = Text(
    texto,
    modifier,
    color = color,
    fontFamily = Pixelify,
    fontSize = tam.sp,
    lineHeight = (tam * 1.4f).sp,
    fontWeight = if (negrita) FontWeight.SemiBold else FontWeight.Normal,
    maxLines = lineas,
    overflow = TextOverflow.Ellipsis,
)

// Pictures -------------------------------------------------------------------------------

/**
 * The picture of a flower. Captured, normal view: a photo as it is, its
 * reference photo or else the user's own. Captured, pixel view: its sprite, or
 * that photo printed in greens. Not captured: its pixel silhouette.
 */
@Composable
fun Figura(estado: Estado, flor: Flor, tam: Dp, modifier: Modifier = Modifier, revelar: Boolean = false) {
    val context = LocalContext.current
    val cap = estado.capturas[flor.clave]
    val pixel = estado.vistaPixel
    // `revelar`: the candidates to pick from show the flower itself, captured
    // or not, or the choice would be between silhouettes.
    val visible = cap != null || (revelar && (flor.foto != null || flor.sprite != null))
    val etiqueta = when {
        !visible -> "silueta-${flor.clave}"
        pixel -> "pixel-${flor.clave}"
        else -> "foto-${flor.clave}"
    }
    Box(modifier.size(tam).testTag(etiqueta)) {
        when {
            visible && !pixel -> {
                val foto = remember(flor.clave, cap?.foto) { flor.foto?.let { Imagenes.asset(context, it) } ?: cap?.let { Imagenes.archivo(it.foto, 480) } }
                if (foto != null) Image(foto.asImageBitmap(), null, Modifier.fillMaxSize().border(2.dp, Paleta.g0), contentScale = ContentScale.Crop)
            }
            visible -> {
                val arte = remember(flor.clave, cap?.foto) { flor.sprite?.let { Imagenes.asset(context, it) } ?: cap?.let { Imagenes.archivoPixel(it.foto, 48, true) } }
                if (arte != null) Image(arte.asImageBitmap(), null, Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
            }
            else -> {
                val sprite = remember(flor.clave) { flor.sprite?.let { Imagenes.asset(context, it) } }
                if (sprite != null) {
                    Image(sprite.asImageBitmap(), null, Modifier.fillMaxSize(), colorFilter = ColorFilter.tint(Paleta.g1), filterQuality = FilterQuality.None)
                } else {
                    FlorGenerica(Modifier.fillMaxSize())
                }
            }
        }
    }
}

// Generic flower for entries without a sprite yet, 16 x 16: the same art as the web app.
private val FLOR = listOf(
    "0000001111000000", "0000014444100000", "0001114444111000", "0014441441444100",
    "0144444114444410", "0144441221444410", "0014412332144100", "0001123333211000",
    "0014412332144100", "0144441221444410", "0144444114444410", "0014441111444100",
    "0001110220111000", "0000001221000000", "0000012221000000", "0000001110000000",
)

@Composable
private fun FlorGenerica(modifier: Modifier) {
    Canvas(modifier) {
        val p = size.minDimension / 16f
        for (y in 0 until 16) for (x in 0 until 16) {
            if (FLOR[y][x] != '0') drawRect(Paleta.g1, Offset(x * p, y * p), Size(p, p))
        }
    }
}

// List ----------------------------------------------------------------------------------

@Composable
fun ListaPantalla(estado: Estado) {
    val visibles = estado.visibles
    val lista = rememberLazyListState()
    LaunchedEffect(estado.cursor, visibles.size) {
        if (visibles.isEmpty()) return@LaunchedEffect
        val primera = lista.firstVisibleItemIndex
        val ultima = lista.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: primera
        if (estado.cursor < primera || estado.cursor >= ultima) lista.scrollToItem((estado.cursor - 2).coerceAtLeast(0))
    }
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row {
                T("VISTAS ", tam = 14)
                T(numero(estado.vistasTotal), Modifier.testTag("vistas"), tam = 14, negrita = true)
            }
            Row {
                T("CAPTURADAS ", tam = 14)
                T(numero(estado.capturas.size), Modifier.testTag("capturadas"), tam = 14, negrita = true)
                T("/${numero(estado.entradas.size)}", Modifier.testTag("total"), tam = 14)
            }
        }
        // Progress in whole blocks, like an old screen.
        val bloques = if (estado.entradas.isEmpty()) 0 else Math.ceil(estado.capturas.size * 40.0 / estado.entradas.size).toInt()
        Canvas(Modifier.fillMaxWidth().height(10.dp).border(2.dp, Paleta.g0)) {
            val w = (size.width - 4.dp.toPx()) / 40f
            for (i in 0 until bloques) drawRect(Paleta.g0, Offset(2.dp.toPx() + i * w, 2.dp.toPx()), Size(w - 1f, size.height - 4.dp.toPx()))
        }
        visibles.getOrNull(estado.cursor)?.let { f ->
            val e = estado.estadoDe(f.clave)
            Row(
                Modifier.fillMaxWidth().background(Paleta.g2).border(3.dp, Paleta.g0).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Figura(estado, f, 56.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    T("N.º ${numero(f.numero)}", tam = 14)
                    T(if (e == EstadoFlor.SINVER) "?????" else f.nombre, tam = 17, negrita = true, lineas = 1)
                }
            }
        }
        Busqueda(estado)
        if (estado.verFiltros) PanelFiltros(estado)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val n = visibles.size
            T(if (n == 0) "Ninguna entrada coincide." else "$n ${if (n == 1) "entrada" else "entradas"}", Modifier.testTag("recuento"), tam = 13)
            if (estado.filtros.activos) TeclaLcd("Quitar filtros", Modifier.testTag("quitar-filtros"), alto = 28.dp) { estado.cambiarFiltros(Filtros()) }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Paleta.g0))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("lista"), state = lista) {
            items(visibles.size, key = { visibles[it].clave }) { i ->
                Fila(estado, visibles[i], i == estado.cursor) { estado.abrirEntrada(visibles[i].clave) }
            }
        }
    }
}

@Composable
private fun Fila(estado: Estado, f: Flor, actual: Boolean, onClick: () -> Unit) {
    val e = estado.estadoDe(f.clave)
    var parpadeo by remember { mutableStateOf(true) }
    LaunchedEffect(actual) {
        while (actual) {
            delay(400)
            parpadeo = !parpadeo
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .background(if (actual) Paleta.g2 else Paleta.g3)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .testTag("fila-${f.clave}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(if (actual && parpadeo) "▶" else " ", Modifier.width(16.dp), tam = 12)
        T(numero(f.numero), Modifier.width(40.dp), tam = 16, color = if (e == EstadoFlor.SINVER) Paleta.g1 else Paleta.g0)
        Figura(estado, f, 32.dp)
        Spacer(Modifier.width(10.dp))
        T(if (e == EstadoFlor.SINVER) "?????" else f.nombre, Modifier.weight(1f), tam = 16, color = if (e == EstadoFlor.SINVER) Paleta.g1 else Paleta.g0, lineas = 1)
        if (e == EstadoFlor.CAPTURADA) MarcaCapturada()
    }
}

/** Captured mark: a tiny pixel flower. */
@Composable
private fun MarcaCapturada() {
    Canvas(Modifier.size(15.dp)) {
        val p = size.width / 5f
        for ((x, y) in listOf(2 to 0, 0 to 2, 4 to 2, 2 to 4, 1 to 1, 2 to 1, 3 to 1, 1 to 2, 3 to 2, 1 to 3, 2 to 3, 3 to 3)) {
            drawRect(Paleta.g0, Offset(x * p, y * p), Size(p, p))
        }
        drawRect(Paleta.g3, Offset(2 * p, 2 * p), Size(p, p))
    }
}

@Composable
private fun Busqueda(estado: Estado) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            estado.filtros.busqueda,
            { estado.cambiarFiltros(estado.filtros.copy(busqueda = it)) },
            Modifier.weight(1f).height(40.dp).border(3.dp, Paleta.g0).padding(horizontal = 10.dp).testTag("buscador"),
            singleLine = true,
            textStyle = TextStyle(fontFamily = Pixelify, fontSize = 16.sp, color = Paleta.g0),
            cursorBrush = SolidColor(Paleta.g0),
            decorationBox = { campo ->
                Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                    if (estado.filtros.busqueda.isEmpty()) T("Buscar nombre o número", tam = 15, color = Paleta.g1, lineas = 1)
                    campo()
                }
            },
        )
        TeclaLcd("Filtros", Modifier.testTag("ver-filtros"), invertida = estado.verFiltros) {
            estado.verFiltros = !estado.verFiltros
            estado.sonido.cursor()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelFiltros(estado: Estado) {
    val f = estado.filtros
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Selector("Estado", "f-estado", listOf(EstadoFlor.CAPTURADA to "Capturadas", EstadoFlor.VISTA to "Vistas", EstadoFlor.SINVER to "Sin ver"), f.estado) {
            estado.cambiarFiltros(f.copy(estado = it))
        }
        Selector("Familia", "f-familia", estado.familias.map { (k, n) -> k to "$k ($n)" }, f.familia) { estado.cambiarFiltros(f.copy(familia = it)) }
        Selector("Grupo", "f-grupo", listOf("jardin" to "De jardín", "silvestre" to "Silvestre"), f.grupo) { estado.cambiarFiltros(f.copy(grupo = it)) }
        Selector("Estación", "f-estacion", ESTACION.toList(), f.estacion) { estado.cambiarFiltros(f.copy(estacion = it)) }
        Selector("Color", "f-color", COLOR.toList(), f.color) { estado.cambiarFiltros(f.copy(color = it)) }
    }
}

@Composable
private fun <V> Selector(titulo: String, etiqueta: String, opciones: List<Pair<V, String>>, valor: V?, cambiar: (V?) -> Unit) {
    var abierto by remember { mutableStateOf(false) }
    Box {
        TeclaLcd(
            "${opciones.firstOrNull { it.first == valor }?.second ?: titulo} ▾",
            Modifier.testTag(etiqueta),
            invertida = valor != null,
            alto = 36.dp,
        ) { abierto = true }
        DropdownMenu(abierto, { abierto = false }, Modifier.background(Paleta.g3).border(3.dp, Paleta.g0)) {
            (listOf<Pair<V?, String>>(null to "Todas") + opciones).forEach { (v, texto) ->
                DropdownMenuItem(
                    text = { T(texto, tam = 15) },
                    onClick = {
                        abierto = false
                        cambiar(v)
                    },
                    modifier = Modifier.testTag("$etiqueta-${v ?: "todas"}"),
                )
            }
        }
    }
}

// Entry ---------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntradaPantalla(estado: Estado, p: Pantalla.Entrada) {
    val f = estado.porClave[p.clave] ?: return
    val e = estado.estadoDe(f.clave)
    val cap = estado.capturas[f.clave]
    val desplazamiento = rememberScrollState()
    var ultimo by remember { mutableStateOf(estado.desplazar) }
    LaunchedEffect(estado.desplazar) {
        val paso = estado.desplazar - ultimo
        ultimo = estado.desplazar
        if (paso != 0) desplazamiento.animateScrollTo((desplazamiento.value + paso * 240).coerceAtLeast(0))
    }
    LaunchedEffect(estado.pagina, p.clave) { desplazamiento.scrollTo(0) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(Paleta.g0).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T(
                "‹ Lista", tam = 15, color = Paleta.g3,
                modifier = Modifier.border(3.dp, Paleta.g3).clickable(role = Role.Button) { estado.volverALista() }.padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            T("N.º ${numero(f.numero)}", Modifier.testTag("entrada-numero"), tam = 20, color = Paleta.g3, negrita = true)
        }
        if (e == EstadoFlor.CAPTURADA) {
            Row(Modifier.fillMaxWidth().background(Paleta.g0).padding(bottom = 3.dp)) {
                Pagina.entries.forEach { pg ->
                    val actual = pg == estado.pagina
                    Box(
                        Modifier.weight(1f).height(36.dp).padding(end = if (pg != Pagina.FOTO) 2.dp else 0.dp)
                            .background(if (actual) Paleta.g3 else Paleta.g2)
                            .clickable(role = Role.Tab) { estado.verPagina(pg) }
                            .testTag("pagina-${pg.name.lowercase()}"),
                        contentAlignment = Alignment.Center,
                    ) {
                        T((if (actual) "▸" else "") + pg.titulo.uppercase(), tam = 14, negrita = actual)
                    }
                }
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(desplazamiento).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            p.sello?.let {
                T(
                    if (it == Sello.NUEVA) "¡NUEVA!" else "YA LA TENÍAS", Modifier.background(Paleta.g0).padding(horizontal = 10.dp, vertical = 4.dp).testTag("sello"),
                    tam = 15, color = Paleta.g3, negrita = true,
                )
            }
            when (if (e == EstadoFlor.CAPTURADA) estado.pagina else Pagina.DATOS) {
                Pagina.DATOS -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.border(3.dp, Paleta.g0).background(Paleta.g2).padding(8.dp).drawBehind {
                                // A pedestal under the flower.
                                drawOval(Paleta.g1, Offset(size.width * 0.15f, size.height * 0.78f), Size(size.width * 0.7f, size.height * 0.16f))
                            },
                        ) { Figura(estado, f, 120.dp) }
                        Spacer(Modifier.width(14.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            T(if (e == EstadoFlor.SINVER) "?????" else f.nombre, Modifier.testTag("entrada-nombre"), tam = 26, negrita = true)
                            if (e == EstadoFlor.CAPTURADA) {
                                T(f.cientifico, tam = 14, color = Paleta.g1)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf(if (f.grupo == "jardin") "JARDÍN" else "SILVESTRE", COLOR.getValue(f.color).uppercase()).forEach {
                                        T(it, Modifier.background(Paleta.g1).padding(horizontal = 8.dp, vertical = 1.dp), tam = 12, color = Paleta.g3)
                                    }
                                }
                            }
                        }
                    }
                    // The picture is the reference photo or drawn from it (sprite, silhouette): credit its author.
                    f.credito?.let { c ->
                        val context = LocalContext.current
                        Text(
                            "Foto: ${c.autoria}, iNaturalist",
                            Modifier
                                .clickable(role = Role.Button) {
                                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(c.fuente))) }
                                }
                                .testTag("credito"),
                            color = Paleta.g1,
                            fontFamily = Pixelify,
                            fontSize = 12.sp,
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                        )
                    }
                    when (e) {
                        EstadoFlor.SINVER -> Nota("Aún no la has visto. Estas pistas te ayudarán a encontrarla.")
                        EstadoFlor.VISTA -> Nota("La has visto, pero aún no la has capturado. Fotografíala bien para leer su entrada.")
                        else -> {}
                    }
                    if (e == EstadoFlor.CAPTURADA) {
                        Datos(listOf("Familia" to f.familia, "Florece en" to (f.estaciones.mapNotNull { ESTACION[it] }.joinToString(", ").ifEmpty { f.floracion })))
                        T(f.descripcion, Modifier.fillMaxWidth().background(Paleta.g0).padding(12.dp).testTag("entrada-descripcion"), tam = 16, color = Paleta.g3)
                        f.toxicidad?.let { T("¡Cuidado! $it", Modifier.fillMaxWidth().border(3.dp, Paleta.g0).background(Paleta.g2).padding(12.dp), tam = 15, negrita = true) }
                    } else {
                        Datos(listOf("Familia" to f.familia, "Grupo" to (if (f.grupo == "jardin") "De jardín" else "Silvestre"), "Florece" to f.floracion), Modifier.testTag("pistas"))
                    }
                }
                Pagina.CAMPO -> Datos(listOf("Dónde vive" to f.habitat, "Cuándo florece" to f.floracion, "Curiosidad" to f.curiosidad), Modifier.testTag("campo"))
                Pagina.SABER -> Datos(
                    listOf(
                        "Otros nombres" to f.otrosNombres.takeIf { it.isNotEmpty() }?.joinToString(", "),
                        "Cuidados" to f.cuidados,
                        "En el lenguaje de las flores" to f.simbolismo,
                        "De dónde viene el nombre" to f.etimologia,
                        "Usos" to f.usos,
                    ),
                    Modifier.testTag("saber"),
                )
                Pagina.FOTO -> if (cap != null) {
                    val pixel = estado.vistaPixel
                    val foto = remember(cap.foto, pixel) { if (pixel) Imagenes.archivoPixel(cap.foto, 128, false) else Imagenes.archivo(cap.foto) }
                    foto?.let {
                        Image(
                            it.asImageBitmap(), "Tu foto de ${f.nombre}" + if (pixel) ", en píxel" else "",
                            Modifier.fillMaxWidth().widthIn(max = 480.dp).border(3.dp, Paleta.g0).testTag(if (pixel) "tu-foto-pixel" else "tu-foto"),
                            contentScale = ContentScale.FillWidth,
                            filterQuality = if (pixel) FilterQuality.None else FilterQuality.Low,
                        )
                    }
                    val fecha = DateFormat.getDateInstance(DateFormat.LONG, Locale.forLanguageTag("es-ES")).format(Date(cap.fecha))
                    Nota(
                        "Capturada el $fecha, " + (cap.coincidencia?.let { "con un ${porcentaje(it)} de coincidencia." } ?: "elegida por ti entre las candidatas."),
                        Modifier.testTag("hallazgo"),
                    )
                    TeclaLcd("Quitar del Floridex", Modifier.fillMaxWidth().testTag("olvidar")) { estado.pedirSoltar(f.clave) }
                }
            }
        }
    }
}

@Composable
private fun Nota(texto: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(Paleta.g2)) {
        Box(Modifier.width(3.dp).heightIn(min = 40.dp).background(Paleta.g0))
        T(texto, Modifier.padding(10.dp), tam = 15)
    }
}

@Composable
private fun Datos(pares: List<Pair<String, String?>>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().border(3.dp, Paleta.g0).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        pares.forEach { (titulo, valor) ->
            if (valor != null) Column {
                T(titulo.uppercase(), tam = 12, color = Paleta.g1)
                T(valor, tam = 16)
            }
        }
    }
}

// Camera --------------------------------------------------------------------------------

@Composable
fun CamaraPantalla(estado: Estado) {
    val context = LocalContext.current
    var permiso by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var encendida by remember { mutableStateOf(permiso) }
    var denegado by remember { mutableStateOf(false) }
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permiso = ok
        encendida = ok
        denegado = !ok
    }
    var frontal by remember { mutableStateOf(false) }
    val captura = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var disparando by remember { mutableStateOf(false) }

    fun disparar() {
        if (!encendida || disparando || estado.analizando || estado.fotoFija != null) return
        disparando = true
        estado.sonido.disparo()
        captura.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val foto = enderezar(image, frontal)
                image.close()
                disparando = false
                estado.analizar(foto)
            }

            override fun onError(e: ImageCaptureException) {
                disparando = false
                estado.aviso = "No he podido hacer la foto. Inténtalo otra vez."
            }
        })
    }
    var ultimoDisparo by remember { mutableStateOf(estado.disparar) }
    LaunchedEffect(estado.disparar) {
        if (estado.disparar != ultimoDisparo) disparar()
        ultimoDisparo = estado.disparar
    }

    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).fillMaxWidth().border(3.dp, Paleta.g0).background(Paleta.g0)) {
            val fija = estado.fotoFija
            when {
                fija != null -> {
                    // The photo, as it is or in pixel; while the model reads it, a scan line sweeps down.
                    val pixel = estado.vistaPixel
                    val ver = remember(fija, pixel) { if (pixel) fotoPixel(fija, 128) else fija }
                    Image(ver.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = if (pixel) FilterQuality.None else FilterQuality.Low)
                    if (estado.analizando) LineaDeLectura()
                }
                encendida -> Camara(frontal, captura, estado.vistaPixel)
                else -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    T(
                        if (denegado) "No tengo permiso para usar la cámara. Actívalo en los ajustes o pulsa «Foto»." else "Enciende la cámara o sube una foto de una flor.",
                        tam = 16, color = Paleta.g3,
                    )
                    TeclaLcd("Encender cámara", Modifier.testTag("encender")) {
                        if (permiso) encendida = true else pedir.launch(Manifest.permission.CAMERA)
                    }
                }
            }
            val aviso = if (estado.analizando) "Analizando…" else estado.aviso
            aviso?.let {
                T(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp).background(Paleta.g0).padding(8.dp), tam = 15, color = Paleta.g3)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TeclaLcd("‹ Lista", Modifier.testTag("c-volver"), activa = !estado.analizando) { estado.volverALista() }
            Spacer(Modifier.weight(1f))
            // Ready to shoot only on the live camera, never over a still photo.
            val listo = encendida && !estado.analizando && !disparando && estado.fotoFija == null
            TeclaLcd("CAPTURAR", Modifier.testTag("disparar"), activa = listo, invertida = true, alto = 48.dp) { disparar() }
            Spacer(Modifier.weight(1f))
            TeclaLcd("Girar", activa = encendida && !estado.analizando) { frontal = !frontal }
        }
    }
}

@Composable
private fun BoxScope.LineaDeLectura() {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, tween(ANALISIS_MS.toInt(), easing = LinearEasing)) }
    Canvas(Modifier.matchParentSize()) {
        val y = size.height * t.value
        drawRect(Paleta.g3, Offset(0f, y - 1.5.dp.toPx()), Size(size.width, 3.dp.toPx()))
    }
}

@Composable
private fun Camara(frontal: Boolean, captura: ImageCapture, pixel: Boolean) {
    val context = LocalContext.current
    val ciclo = LocalLifecycleOwner.current
    val vista = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // A TextureView, so the pixel view's effect applies to the preview.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    DisposableEffect(pixel) {
        // The shader needs the view's size: apply it now and on every layout.
        val aplicar = { vista.aplicarEfecto(if (pixel) vista.efectoPixel() else null) }
        val oyente = android.view.View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> aplicar() }
        aplicar()
        vista.addOnLayoutChangeListener(oyente)
        onDispose { vista.removeOnLayoutChangeListener(oyente) }
    }
    DisposableEffect(frontal) {
        val futuro = ProcessCameraProvider.getInstance(context)
        var proveedor: ProcessCameraProvider? = null
        // The provider can arrive after the screen is gone: then it must not bind.
        var vigente = true
        futuro.addListener({
            if (!vigente) return@addListener
            proveedor = futuro.get().also { p ->
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(vista.surfaceProvider) }
                val selector = if (frontal) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                runCatching {
                    p.unbindAll()
                    p.bindToLifecycle(ciclo, selector, preview, captura)
                }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            vigente = false
            proveedor?.unbindAll()
        }
    }
    // The pixel effect is unbounded: clip it to the screen.
    AndroidView({ vista }, Modifier.fillMaxSize().clipToBounds())
}

/**
 * The live pixel view: 128 big pixels across, four greens, ordered dithering,
 * as on the web app. Android 13+ runs it as a shader (AGSL); Android 12 gets a
 * flat green duotone; older versions show the camera as it is.
 */
private const val AGSL_PIXEL = """
uniform shader img;
uniform float2 tam;
float bayer2(float2 a) { a = floor(a); return fract(a.x / 2.0 + a.y * a.y * 0.75); }
float bayer4(float2 a) { return bayer2(0.5 * a) * 0.25 + bayer2(a); }
half4 main(float2 p) {
    float t = tam.x / 128.0;
    float2 celda = floor(p / t);
    half3 c = img.eval((celda + 0.5) * t).rgb;
    float l = smoothstep(0.08, 0.92, dot(c, half3(0.299, 0.587, 0.114)));
    float n = clamp(floor(l * 3.0 + bayer4(celda)), 0.0, 3.0);
    half3 v = n < 0.5 ? half3(0.059, 0.220, 0.059) : n < 1.5 ? half3(0.188, 0.384, 0.188) : n < 2.5 ? half3(0.545, 0.675, 0.059) : half3(0.608, 0.737, 0.059);
    return half4(v, 1.0);
}
"""

private fun PreviewView.efectoPixel(): android.graphics.RenderEffect? = when {
    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU -> {
        val s = android.graphics.RuntimeShader(AGSL_PIXEL)
        s.setFloatUniform("tam", maxOf(1, width).toFloat(), maxOf(1, height).toFloat())
        android.graphics.RenderEffect.createRuntimeShaderEffect(s, "img")
    }
    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S -> {
        val m = FloatArray(20)
        floatArrayOf(0.545f, 0.675f, 0.059f).forEachIndexed { c, v -> m[c * 5] = v * 0.299f; m[c * 5 + 1] = v * 0.587f; m[c * 5 + 2] = v * 0.114f }
        m[18] = 1f
        android.graphics.RenderEffect.createColorFilterEffect(android.graphics.ColorMatrixColorFilter(m))
    }
    else -> null
}

/** RenderEffect exists from Android 12; before, the preview stays as it is. */
private fun PreviewView.aplicarEfecto(e: android.graphics.RenderEffect?) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) setRenderEffect(e)
}

private fun enderezar(image: ImageProxy, espejo: Boolean): Bitmap {
    val b = image.toBitmap()
    val m = Matrix().apply {
        postRotate(image.imageInfo.rotationDegrees.toFloat())
        if (espejo) postScale(-1f, 1f)
    }
    return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
}

// Text box ------------------------------------------------------------------------------

@Composable
fun CuadroDeTexto(estado: Estado, d: Dialogo, modifier: Modifier) {
    LaunchedEffect(d) {
        while (d.letras < d.texto.length) {
            delay(22)
            d.letras++
            if (d.letras % 3 == 0) estado.sonido.letra()
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(6.dp)
            .background(Paleta.g3)
            .border(3.dp, Paleta.g0)
            .padding(3.dp)
            .border(2.dp, Paleta.g0)
            .clickable(enabled = d.opciones.isEmpty() || d.escribiendo) { estado.aceptarDialogo() }
            .padding(14.dp)
            .testTag("dialogo"),
    ) {
        T(d.texto.take(d.letras), Modifier.fillMaxWidth().heightIn(min = 44.dp).testTag("dialogo-texto"), tam = 17)
        if (!d.escribiendo) {
            d.opciones.forEachIndexed { i, o ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .background(if (i == d.elegida) Paleta.g2 else Paleta.g3)
                        .clickable(role = Role.Button) { estado.elegir(i) }
                        .padding(horizontal = 6.dp)
                        .testTag("opcion-" + (o.flor?.clave ?: o.id ?: "ninguna")),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T(if (i == d.elegida) "▶" else " ", Modifier.width(18.dp), tam = 12)
                    o.flor?.let {
                        Figura(estado, it, 44.dp, revelar = true)
                        Spacer(Modifier.width(10.dp))
                    }
                    T(o.etiqueta, Modifier.weight(1f), tam = 16, lineas = 1)
                    o.detalle?.let { T(it, tam = 13, color = Paleta.g1) }
                }
            }
            if (d.opciones.isEmpty()) T("▼", Modifier.align(Alignment.End), tam = 12)
        }
    }
}
