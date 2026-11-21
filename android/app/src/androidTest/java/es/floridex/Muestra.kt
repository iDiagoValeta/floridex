package es.floridex

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.fail

/**
 * The held-out test photos (assets/muestra.json and assets/muestra/). They come
 * from the training datasets and are not in the repo; generate them with
 *
 *   uv run --with onnxruntime --with pillow --with numpy python shared/muestra.py <dir>
 */
object Muestra {
    fun indice(): JSONArray {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val texto = runCatching { assets.open("muestra.json").bufferedReader().use { it.readText() } }.getOrElse {
            fail("Missing the test sample (androidTest/assets/muestra.json). Generate it with: uv run --with onnxruntime --with pillow --with numpy python shared/muestra.py <dir>")
            error("unreachable")
        }
        return JSONArray(texto)
    }
}
