package es.floridex

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One entry of floridex.json, built by shared/floridex.py (the same file as the web app). */
data class Flor(
    val numero: Int,
    val clave: String,
    val nombre: String,
    val otrosNombres: List<String>,
    val cientifico: String,
    val familia: String,
    val descripcion: String,
    val habitat: String,
    val floracion: String,
    val curiosidad: String,
    val toxicidad: String?,
    val cuidados: String?,
    val simbolismo: String?,
    val etimologia: String?,
    val usos: String?,
    val grupo: String,
    val estaciones: List<String>,
    val color: String,
    /** Reference photo in assets, shown as is once captured. */
    val foto: String?,
    /** Sprite in assets, the source of the silhouette shown until captured. */
    val sprite: String?,
    /** Author and licence of the reference photo, shown wherever it or its sprite appears. */
    val credito: Credito?,
)

data class Credito(val autor: String, val licencia: String, val fuente: String) {
    /** CC0 photos come without an author's name ("no rights reserved"): say so plainly. Same as the web. */
    val autoria get() = if (licencia == "cc0" && !autor.startsWith("(c)")) "dominio público (CC0)" else autor
}

private fun JSONObject.texto(k: String): String? = if (isNull(k)) null else getString(k)
private fun JSONArray.textos() = List(length()) { getString(it) }

fun cargarFlores(context: Context): List<Flor> {
    val a = JSONArray(context.assets.open("floridex.json").bufferedReader().use { it.readText() })
    return List(a.length()) { a.getJSONObject(it) }.map { o ->
        Flor(
            numero = o.getInt("numero"),
            clave = o.getString("key"),
            nombre = o.getString("nombre"),
            otrosNombres = o.getJSONArray("otros_nombres").textos(),
            cientifico = o.getString("cientifico"),
            familia = o.getString("familia"),
            descripcion = o.getString("descripcion"),
            habitat = o.getString("habitat"),
            floracion = o.getString("floracion"),
            curiosidad = o.getString("curiosidad"),
            toxicidad = o.texto("toxicidad"),
            cuidados = o.texto("cuidados"),
            simbolismo = o.texto("simbolismo"),
            etimologia = o.texto("etimologia"),
            usos = o.texto("usos"),
            grupo = o.getString("grupo"),
            estaciones = o.getJSONArray("estaciones").textos(),
            color = o.getString("color"),
            foto = o.texto("foto"),
            sprite = o.texto("sprite"),
            credito = o.optJSONObject("credito")?.let { Credito(it.getString("autor"), it.getString("licencia"), it.getString("fuente")) },
        )
    }
}

/**
 * The model's labels in output order, from its config.json (id2label): the
 * Floridex follows the model it ships, the same file the web reads.
 */
fun etiquetasDelModelo(context: Context): List<String> {
    val id2label = JSONObject(context.assets.open("modelo/config.json").bufferedReader().use { it.readText() }).getJSONObject("id2label")
    return List(id2label.length()) { id2label.getString(it.toString()) }
}

/** A captured flower: the user's photo, kept in the app's private files. */
data class Captura(val clave: String, val fecha: Long, val foto: File, val coincidencia: Float?)

/**
 * The user's progress, on this phone only: captures (with their photos) and
 * flowers seen as candidates. A small JSON file written atomically, so a
 * crash mid-write never loses the collection.
 */
class Progreso(context: Context) {
    private val dir = File(context.filesDir, "floridex").apply { mkdirs() }
    private val archivo = AtomicFile(File(dir, "progreso.json"))

    fun leer(): Pair<Map<String, Captura>, Map<String, Long>> {
        val o = runCatching { JSONObject(String(archivo.readFully())) }.getOrNull() ?: return emptyMap<String, Captura>() to emptyMap()
        val caps = o.optJSONArray("capturas") ?: JSONArray()
        val capturas = List(caps.length()) { caps.getJSONObject(it) }.mapNotNull { c ->
            val foto = File(dir, c.getString("foto"))
            if (!foto.exists()) return@mapNotNull null
            Captura(c.getString("clave"), c.getLong("fecha"), foto, if (c.isNull("coincidencia")) null else c.getDouble("coincidencia").toFloat())
        }.associateBy { it.clave }
        val vs = o.optJSONArray("vistas") ?: JSONArray()
        val vistas = List(vs.length()) { vs.getJSONObject(it) }.associate { it.getString("clave") to it.getLong("fecha") }
        return capturas to vistas
    }

    fun escribir(capturas: Map<String, Captura>, vistas: Map<String, Long>) {
        val o = JSONObject()
            .put("capturas", JSONArray().apply {
                capturas.values.forEach { c ->
                    put(JSONObject().put("clave", c.clave).put("fecha", c.fecha).put("foto", c.foto.name).put("coincidencia", c.coincidencia?.toDouble() ?: JSONObject.NULL))
                }
            })
            .put("vistas", JSONArray().apply { vistas.forEach { (k, f) -> put(JSONObject().put("clave", k).put("fecha", f)) } })
        val s = archivo.startWrite()
        try {
            s.write(o.toString().toByteArray())
            archivo.finishWrite(s)
        } catch (e: Exception) {
            archivo.failWrite(s)
            throw e
        }
    }

    /** Copies the photo next to the progress file; the caller then saves the capture. */
    fun guardarFoto(temporal: File): File {
        val destino = File(dir, "${UUID.randomUUID()}.jpg")
        temporal.copyTo(destino, overwrite = true)
        return destino
    }

}

/** Writes a downscaled copy of the photo to the cache, before it is captured. */
fun fotoTemporal(context: Context, bitmap: Bitmap): File {
    val f = File(context.cacheDir, "foto-${UUID.randomUUID()}.jpg")
    val s = minOf(1f, 1200f / maxOf(bitmap.width, bitmap.height))
    val b = if (s < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * s).toInt(), (bitmap.height * s).toInt(), true) else bitmap
    f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }
    return f
}

/** Small decoded images, so scrolling the list does not decode the same file twice. */
object Imagenes {
    private val cache = object : android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun asset(context: Context, ruta: String): Bitmap? = cache.get("a:$ruta") ?: runCatching {
        context.assets.open(ruta).use { BitmapFactory.decodeStream(it) }
    }.getOrNull()?.also { cache.put("a:$ruta", it) }

    /** The user's photo in the pixel view, made once per size. */
    fun archivoPixel(f: File, ancho: Int, cuadrada: Boolean): Bitmap? = cache.get("p:${f.path}:$ancho:$cuadrada")
        ?: archivo(f, 480)?.let { fotoPixel(it, ancho, cuadrada) }?.also { cache.put("p:${f.path}:$ancho:$cuadrada", it) }

    fun archivo(f: File, lado: Int = 1200): Bitmap? = cache.get("f:${f.path}:$lado") ?: runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var muestreo = 1
        while (maxOf(o.outWidth, o.outHeight) / (muestreo * 2) >= lado) muestreo *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = muestreo })
    }.getOrNull()?.also { cache.put("f:${f.path}:$lado", it) }
}

private val VERDES = intArrayOf(0xFF0F380F.toInt(), 0xFF306230.toInt(), 0xFF8BAC0F.toInt(), 0xFF9BBC0F.toInt())
private val BAYER = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)

/**
 * Prints a photo the way a pocket camera of the 90s would: low resolution,
 * four greens, ordered dithering, the same as the web app. `cuadrada` crops
 * the centre square.
 */
fun fotoPixel(origen: Bitmap, ancho: Int, cuadrada: Boolean = false): Bitmap {
    val lado = minOf(origen.width, origen.height)
    val recorte = if (cuadrada) Bitmap.createBitmap(origen, (origen.width - lado) / 2, (origen.height - lado) / 2, lado, lado) else origen
    val alto = maxOf(1, Math.round(ancho.toFloat() * recorte.height / recorte.width))
    val b = Bitmap.createScaledBitmap(recorte, ancho, alto, true)
    val px = IntArray(ancho * alto)
    b.getPixels(px, 0, ancho, 0, 0, ancho, alto)
    val lum = FloatArray(px.size) { i ->
        val c = px[i]
        0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
    }
    val min = lum.min()
    val rango = maxOf(1f, lum.max() - min)
    for (y in 0 until alto) for (x in 0 until ancho) {
        val i = y * ancho + x
        val umbral = (BAYER[(y % 4) * 4 + (x % 4)] + 0.5f) / 16f - 0.5f
        px[i] = VERDES[Math.round((lum[i] - min) / rango * 3f + umbral).coerceIn(0, 3)]
    }
    return Bitmap.createBitmap(px, ancho, alto, Bitmap.Config.ARGB_8888)
}
