package es.floridex

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Floridex: a pocket device in leaf-green plastic with a four-green LCD
 * screen, like the handhelds of the 90s. Same palette as the web app.
 */
object Paleta {
    // Screen, darkest to lightest: the classic handheld LCD.
    val g0 = Color(0xFF0F380F)
    val g1 = Color(0xFF306230)
    val g2 = Color(0xFF8BAC0F)
    val g3 = Color(0xFF9BBC0F)

    // Device
    val fondo = Color(0xFF142010)
    val carcasa = Color(0xFF4E8A3A)
    val carcasaLuz = Color(0xFF6AA84F)
    val carcasaSombra = Color(0xFF2F5F22)
    val bisel = Color(0xFF26301F)
    val tecla = Color(0xFF1D2618)
    val boton = Color(0xFFA3284F)
    val botonSombra = Color(0xFF5E1530)
    val textoCarcasa = Color(0xFFE9F2D8)
}

/** Pixelify Sans (OFL), the same pixel type as the web app. */
val Pixelify = FontFamily(
    Font(R.font.pixelify_400, FontWeight.Normal),
    Font(R.font.pixelify_600, FontWeight.SemiBold),
)

/** A box whose corners step in by one device "pixel", like old screen art. */
class Escalon(private val pix: Dp = 3.dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val p = with(density) { pix.toPx() }
        val (w, h) = size
        return Outline.Generic(Path().apply {
            moveTo(0f, p); lineTo(p, p); lineTo(p, 0f)
            lineTo(w - p, 0f); lineTo(w - p, p); lineTo(w, p)
            lineTo(w, h - p); lineTo(w - p, h - p); lineTo(w - p, h)
            lineTo(p, h); lineTo(p, h - p); lineTo(0f, h - p)
            close()
        })
    }
}
