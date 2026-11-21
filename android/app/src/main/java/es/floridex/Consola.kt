package es.floridex

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The handheld: shell, screen, pad, A and B, and two pill buttons. */
@Composable
fun Consola(estado: Estado, pedirFoto: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Paleta.carcasa)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Cabeza(estado)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(Escalon())
                .background(Paleta.bisel)
                .padding(8.dp),
        ) {
            Pantalla(estado)
        }
        Mandos(estado, pedirFoto)
    }
}

@Composable
private fun Cabeza(estado: Estado) {
    var sonido by remember { mutableStateOf(estado.sonido.activo) }
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(Paleta.g3))
        Spacer(Modifier.width(12.dp))
        Text(
            "FLORIDEX",
            fontFamily = Pixelify,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            letterSpacing = 2.sp,
            color = Paleta.textoCarcasa,
            modifier = Modifier.weight(1f),
        )
        Pastilla(if (estado.vistaPixel) "Vista píxel" else "Vista normal", Modifier.testTag("vista"), activa = estado.vistaPixel) { estado.alternarVista() }
        Spacer(Modifier.width(8.dp))
        Pastilla(if (sonido) "Sonido" else "Silencio", Modifier.testTag("sonido")) {
            sonido = estado.sonido.alternar()
            estado.sonido.cursor()
        }
    }
}

/** The LCD: the current screen, the text box over it and the capture flash. */
@Composable
private fun Pantalla(estado: Estado) {
    val destello = remember { Animatable(0f) }
    LaunchedEffect(estado.destello) {
        if (estado.destello == 0) return@LaunchedEffect
        repeat(2) {
            destello.snapTo(1f)
            kotlinx.coroutines.delay(250)
            destello.snapTo(0f)
            kotlinx.coroutines.delay(250)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .clip(Escalon())
            .background(Paleta.g3)
            .drawWithContent {
                drawContent()
                // The capture flash inverts the screen for a moment.
                if (destello.value > 0f) drawRect(Color.White, blendMode = BlendMode.Difference)
            },
    ) {
        when (val p = estado.pantalla) {
            Pantalla.Lista -> ListaPantalla(estado)
            is Pantalla.Entrada -> EntradaPantalla(estado, p)
            Pantalla.Camara -> CamaraPantalla(estado)
        }
        estado.dialogo?.let { CuadroDeTexto(estado, it, Modifier.align(Alignment.BottomCenter)) }
    }
}

@Composable
private fun Mandos(estado: Estado, pedirFoto: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cruceta(estado)
        Column(Modifier.weight(1f).padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Pastilla("Cámara", Modifier.testTag("ir-camara")) { estado.abrirCamara() }
            Pastilla("Foto", Modifier.testTag("foto")) { pedirFoto() }
        }
        Box(Modifier.size(width = 120.dp, height = 110.dp).rotate(-20f)) {
            BotonRedondo("B", "Botón B, volver", Modifier.align(Alignment.BottomStart).testTag("boton-b")) { estado.tecla(Tecla.B) }
            BotonRedondo("A", "Botón A, aceptar", Modifier.align(Alignment.TopEnd).testTag("boton-a")) { estado.tecla(Tecla.A) }
        }
    }
}

@Composable
private fun Cruceta(estado: Estado) {
    val celda = 42.dp
    Box(Modifier.size(celda * 3)) {
        Box(Modifier.offset(celda, celda).size(celda).background(Paleta.tecla))
        for ((t, x, y, nombre) in listOf(
            Quad(Tecla.ARRIBA, 1, 0, "Arriba"),
            Quad(Tecla.IZQUIERDA, 0, 1, "Izquierda"),
            Quad(Tecla.DERECHA, 2, 1, "Derecha"),
            Quad(Tecla.ABAJO, 1, 2, "Abajo"),
        )) {
            BrazoCruceta(t, nombre, Modifier.offset(celda * x, celda * y).size(celda).testTag("cruz-${nombre.lowercase()}")) { estado.tecla(t) }
        }
    }
}

private data class Quad(val tecla: Tecla, val x: Int, val y: Int, val nombre: String)

@Composable
private fun BrazoCruceta(tecla: Tecla, nombre: String, modifier: Modifier, onClick: () -> Unit) {
    val toque = remember { MutableInteractionSource() }
    val pulsado by toque.collectIsPressedAsState()
    Canvas(
        modifier
            .background(if (pulsado) Color.Black else Paleta.tecla)
            .clickable(toque, null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = nombre },
    ) {
        // A small pixel arrow pointing outwards.
        val c = center
        val r = size.minDimension * 0.16f
        val (dx, dy) = when (tecla) {
            Tecla.ARRIBA -> 0f to -1f
            Tecla.ABAJO -> 0f to 1f
            Tecla.IZQUIERDA -> -1f to 0f
            else -> 1f to 0f
        }
        val punta = Offset(c.x + dx * r, c.y + dy * r)
        val base1 = Offset(c.x - dx * r * 0.4f - dy * r, c.y - dy * r * 0.4f - dx * r)
        val base2 = Offset(c.x - dx * r * 0.4f + dy * r, c.y - dy * r * 0.4f + dx * r)
        drawPath(Path().apply { moveTo(punta.x, punta.y); lineTo(base1.x, base1.y); lineTo(base2.x, base2.y); close() }, Paleta.carcasa)
    }
}

@Composable
private fun BotonRedondo(letra: String, descripcion: String, modifier: Modifier, onClick: () -> Unit) {
    val toque = remember { MutableInteractionSource() }
    val pulsado by toque.collectIsPressedAsState()
    Box(
        modifier
            .size(56.dp)
            .offset(y = if (pulsado) 3.dp else 0.dp)
            .clip(CircleShape)
            .background(Paleta.botonSombra)
            .padding(bottom = if (pulsado) 0.dp else 3.dp)
            .clip(CircleShape)
            .background(Paleta.boton)
            .clickable(toque, null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = descripcion },
        contentAlignment = Alignment.Center,
    ) {
        Text(letra, fontFamily = Pixelify, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = Paleta.textoCarcasa)
    }
}

@Composable
fun Pastilla(texto: String, modifier: Modifier = Modifier, activa: Boolean = false, onClick: () -> Unit) {
    Text(
        texto.uppercase(),
        fontFamily = Pixelify,
        fontSize = 13.sp,
        maxLines = 1,
        letterSpacing = 1.sp,
        color = if (activa) Paleta.g3 else Paleta.textoCarcasa,
        modifier = modifier
            .clip(Escalon())
            .background(if (activa) Paleta.g0 else Paleta.tecla)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** A key drawn on the screen itself, in the LCD greens. */
@Composable
fun TeclaLcd(texto: String, modifier: Modifier = Modifier, activa: Boolean = true, invertida: Boolean = false, alto: Dp = 40.dp, onClick: () -> Unit) {
    Box(
        modifier
            .clip(Escalon())
            .background(if (invertida) Paleta.g0 else Paleta.g2)
            .border(3.dp, Paleta.g0)
            .clickable(enabled = activa, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp)
            .height(alto),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            texto,
            fontFamily = Pixelify,
            fontSize = 15.sp,
            color = (if (invertida) Paleta.g3 else Paleta.g0).copy(alpha = if (activa) 1f else 0.5f),
        )
    }
}
