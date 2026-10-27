package dev.josefelipe.trotinete.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private val PT = Locale("pt", "PT")

/** Números à portuguesa: 47,7 V. */
fun Number?.fmt(unidade: String = "", casas: Int = 1): String =
    if (this == null) "—" else "%.${casas}f".format(PT, this.toDouble()) + if (unidade.isEmpty()) "" else " $unidade"

fun corBateria(nivel: Float): Color = when {
    nivel < 0.15f -> Vermelho
    nivel < 0.35f -> Ambar
    else -> Verde
}

/** Superfície de vidro: translúcida, cantos largos e um fio de luz na borda. */
fun Modifier.vidro(cantos: Dp = 24.dp, cor: Color = Vidro): Modifier = this
    .clip(RoundedCornerShape(cantos))
    .background(cor)
    .border(1.dp, Brush.verticalGradient(listOf(VidroBorda, Color.Transparent)), RoundedCornerShape(cantos))

@Composable
fun Cartao(modifier: Modifier = Modifier, titulo: String? = null, conteudo: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().vidro().padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        titulo?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        conteudo()
    }
}

@Composable
fun Linha(nome: String, valor: String, destaque: Color? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(nome, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), fontSize = 15.sp)
        Text(valor, fontWeight = FontWeight.Medium, color = destaque ?: MaterialTheme.colorScheme.onSurface, fontSize = 15.sp)
    }
}

/** Mosaico com ícone, valor grande e legenda. */
@Composable
fun Mosaico(icone: ImageVector, valor: String, legenda: String, modifier: Modifier = Modifier, cor: Color = Verde) {
    Column(modifier.vidro(20.dp).padding(14.dp)) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(cor.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icone, null, tint = cor, modifier = Modifier.size(19.dp)) }
        Spacer(Modifier.height(12.dp))
        Text(valor, style = MaterialTheme.typography.headlineSmall, fontSize = 21.sp)
        Text(legenda, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Medidor circular (arco de 270°) com o conteúdo ao centro, ao estilo de um painel de carro:
 * marcações à volta, arco em gradiente e um brilho por trás do arco.
 */
@Composable
fun Medidor(fracao: Float, cor: Color, tamanho: Dp = 260.dp, cor2: Color = cor, centro: @Composable () -> Unit) {
    val animada by animateFloatAsState(fracao.coerceIn(0f, 1f), tween(900), label = "medidor")
    val pista = Color.White.copy(alpha = 0.06f)
    val marca = Color.White.copy(alpha = 0.18f)
    Box(Modifier.size(tamanho), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(tamanho)) {
            val traco = 14.dp.toPx()
            val margem = 22.dp.toPx()
            val lado = size.minDimension - traco - margem * 2
            val canto = Offset((size.width - lado) / 2, (size.height - lado) / 2)
            val area = Size(lado, lado)
            val c = center

            // Marcações: 28 traços, os de 7 em 7 mais compridos.
            val raioFora = size.minDimension / 2 - 2.dp.toPx()
            for (i in 0..27) {
                val ang = Math.toRadians(135.0 + 270.0 * i / 27)
                val longo = i % 7 == 0
                val dentro = raioFora - (if (longo) 10.dp else 5.dp).toPx()
                val acesa = i / 27f <= animada && animada > 0f
                drawLine(
                    color = if (acesa) cor.copy(alpha = if (longo) 0.9f else 0.55f) else marca,
                    start = Offset(c.x + dentro * cos(ang).toFloat(), c.y + dentro * sin(ang).toFloat()),
                    end = Offset(c.x + raioFora * cos(ang).toFloat(), c.y + raioFora * sin(ang).toFloat()),
                    strokeWidth = (if (longo) 2.5.dp else 1.5.dp).toPx(),
                    cap = StrokeCap.Round,
                )
            }

            drawArc(pista, 135f, 270f, false, canto, area, style = Stroke(traco, cap = StrokeCap.Round))
            if (animada > 0f) {
                // O sweep começa às 3 h; o arco vai das 135° (cor2) até às 45° (cor), passando pelos 0°.
                val aos0 = lerp(cor2, cor, 225f / 270f)
                val gradiente = Brush.sweepGradient(0f to aos0, 0.125f to cor, 0.375f to cor2, 1f to aos0, center = c)
                // Brilho: o mesmo arco, mais largo e quase transparente.
                drawArc(cor.copy(alpha = 0.16f), 135f, 270f * animada, false, canto, area, style = Stroke(traco * 2.4f, cap = StrokeCap.Round))
                drawArc(gradiente, 135f, 270f * animada, false, canto, area, style = Stroke(traco, cap = StrokeCap.Round))
            }
        }
        centro()
    }
}

@Composable
fun Interruptor(titulo: String, descricao: String?, ligado: Boolean, enabled: Boolean = true, mudar: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(titulo, fontSize = 15.sp)
            descricao?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = ligado, onCheckedChange = mudar, enabled = enabled)
    }
}

/** Escolha entre opções fixas (ex.: travagem 30/60/90). */
@Composable
fun Opcoes(titulo: String, opcoes: List<Pair<Long, String>>, atual: Long?, enabled: Boolean = true, escolher: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(titulo, fontSize = 15.sp)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            opcoes.forEachIndexed { i, (valor, nome) ->
                SegmentedButton(
                    selected = valor == atual,
                    onClick = { escolher(valor) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(i, opcoes.size),
                ) { Text(nome, fontSize = 13.sp) }
            }
        }
    }
}
