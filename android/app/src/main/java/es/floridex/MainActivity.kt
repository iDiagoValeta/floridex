package es.floridex

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.core.content.IntentCompat

class MainActivity : ComponentActivity() {
    val estado: Estado by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) compartida(intent)
        setContent {
            val elegir = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(::abrirImagen) }
            // The system back is button B; on the list with nothing open it leaves the app.
            BackHandler(enabled = estado.dialogo != null || estado.pantalla !is Pantalla.Lista) { estado.tecla(Tecla.B) }
            Box(Modifier.fillMaxSize().background(Paleta.carcasa).safeDrawingPadding()) {
                // «Foto» waits while a photo is being read.
                Consola(estado) { if (!estado.analizando) elegir.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            }
        }
    }

    /** Hardware keyboards and gamepads drive the pad and the buttons. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val t = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> Tecla.ARRIBA
            KeyEvent.KEYCODE_DPAD_DOWN -> Tecla.ABAJO
            KeyEvent.KEYCODE_DPAD_LEFT -> Tecla.IZQUIERDA
            KeyEvent.KEYCODE_DPAD_RIGHT -> Tecla.DERECHA
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> Tecla.A
            KeyEvent.KEYCODE_BUTTON_B -> Tecla.B
            else -> null
        }
        return (t != null && estado.tecla(t)) || super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        compartida(intent)
    }

    /** Photos shared from the gallery or another app ("Identificar con Floridex"). */
    private fun compartida(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(::abrirImagen)
    }

    /** Decodes off the main thread (a large photo would freeze the screen), then reads it. */
    private fun abrirImagen(uri: Uri) {
        lifecycleScope.launch {
            val foto = withContext(Dispatchers.IO) { runCatching { decodificar(uri) }.getOrNull() }
            if (foto == null) {
                estado.aviso = "No he podido abrir esa imagen. Prueba con otra foto."
                return@launch
            }
            estado.analizar(foto)
        }
    }

    /** Decodes with EXIF rotation applied, capped at 1600 px to spare memory. */
    private fun decodificar(uri: Uri): Bitmap {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = minOf(1f, 1600f / maxOf(info.size.width, info.size.height))
                d.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt())
            }
        }
        // Android 8: BitmapFactory ignores EXIF, so the rotation is applied by hand.
        val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, limites) }
        var muestreo = 1
        while (maxOf(limites.outWidth, limites.outHeight) / (muestreo * 2) >= 1600) muestreo *= 2
        val b = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = muestreo }) }
            ?: error("no es una imagen")
        val orientacion = contentResolver.openInputStream(uri).use { ExifInterface(it!!).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        val m = Matrix()
        when (orientacion) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return b
        }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }
}
