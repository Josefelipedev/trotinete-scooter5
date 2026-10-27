package dev.josefelipe.trotinete.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Verde = Color(0xFF3DDC97)
val Ciano = Color(0xFF34D3E8)
val Vermelho = Color(0xFFFF6B6B)
val Ambar = Color(0xFFFFC857)
val Azul = Color(0xFF7AA2F7)
val Roxo = Color(0xFFBB9AF7)

/** Fundo quase preto do painel; os cartões são vidro por cima dele. */
val Fundo = Color(0xFF07090D)
val Vidro = Color(0x14FFFFFF)
val VidroBorda = Color(0x1FFFFFFF)

private val cores = darkColorScheme(
    primary = Verde,
    onPrimary = Color(0xFF00281A),
    secondary = Ciano,
    background = Fundo,
    onBackground = Color(0xFFE6E8EB),
    surface = Color(0xFF12161D),
    onSurface = Color(0xFFE6E8EB),
    surfaceVariant = Color(0xFF1E2430),
    onSurfaceVariant = Color(0xFF8B95A5),
    error = Vermelho,
)

/** Números com largura fixa (não "dançam" quando o valor muda), como num painel de carro. */
private val numeros = TextStyle(fontFeatureSettings = "tnum")

private val tipos = Typography().run {
    copy(
        displayLarge = displayLarge.merge(numeros).copy(fontWeight = FontWeight.Bold, fontSize = 64.sp, letterSpacing = (-2).sp),
        headlineSmall = headlineSmall.merge(numeros).copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        labelSmall = labelSmall.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun Tema(conteudo: @Composable () -> Unit) = MaterialTheme(colorScheme = cores, typography = tipos, content = conteudo)

/** Fundo com um brilho verde suave no topo, atrás do medidor. */
@Composable
fun FundoPainel(conteudo: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Fundo)
            .background(
                Brush.radialGradient(
                    listOf(Verde.copy(alpha = 0.10f), Ciano.copy(alpha = 0.03f), Color.Transparent),
                    center = Offset(540f, 420f),
                    radius = 1100f,
                ),
            ),
    ) {
        // Sem Surface por baixo, o texto ficava preto: dá-lhe a cor clara do tema.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) { conteudo() }
    }
}
