package es.floridex.emisora

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.core.content.FileProvider
import java.io.File

/**
 * Shares assets/<foto> with Floridex the way a gallery or
 * chat app does: ACTION_SEND with a content:// URI from this app's own
 * FileProvider and a temporary read grant. No activity is named, so the
 * receiver's intent filter decides.
 */
class Compartir : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nombre = intent.getStringExtra("foto") ?: run {
            finish()
            return
        }
        val foto = File(cacheDir, nombre)
        assets.open(nombre).use { i -> foto.outputStream().use { i.copyTo(it) } }
        val uri = FileProvider.getUriForFile(this, "es.floridex.emisora.archivos", foto)
        val envio = Intent(Intent.ACTION_SEND)
            .setType("image/jpeg")
            .setPackage("es.floridex")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        envio.clipData = ClipData.newRawUri("", uri)
        startActivity(envio)
        finish()
    }
}
