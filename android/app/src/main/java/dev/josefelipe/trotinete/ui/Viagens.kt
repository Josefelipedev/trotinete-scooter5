package dev.josefelipe.trotinete.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.josefelipe.trotinete.dados.Definicoes
import dev.josefelipe.trotinete.dados.Diario
import dev.josefelipe.trotinete.dados.Ponto
import dev.josefelipe.trotinete.dados.ViagemGps
import dev.josefelipe.trotinete.protocolo.Viagem
import dev.josefelipe.trotinete.servico.Comando
import dev.josefelipe.trotinete.servico.Estado
import dev.josefelipe.trotinete.servico.Ligacao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.cos

private val FORMATO_DATA = DateTimeFormatter.ofPattern("EEE, d MMM · HH:mm", Locale("pt", "PT"))

private fun data(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(FORMATO_DATA)

@Composable
fun AbaViagens(e: Estado, defs: Definicoes) {
    val context = LocalContext.current
    val diario = remember { Diario(context) }
    var recarregar by remember { mutableStateOf(0) }
    val viagens by produceState(emptyList<ViagemGps>(), e.viagensGuardadas, recarregar) {
        value = withContext(Dispatchers.IO) { diario.viagens() }
    }

    Gravacao(e, defs)
    if (viagens.isNotEmpty()) Resumo(viagens, defs.precoKwh)

    var aberta by rememberSaveable { mutableStateOf<Long?>(null) }
    viagens.forEach { v ->
        CartaoViagem(
            v, aberta == v.inicio,
            abrir = { aberta = if (aberta == v.inicio) null else v.inicio },
            apagar = {
                diario.apagarViagem(v.inicio)
                recarregar++
            },
        )
    }
    if (viagens.isEmpty()) {
        Cartao(titulo = "As tuas viagens") {
            Text(
                "Ainda não há viagens gravadas. Com a app ligada à trotinete, cada volta grava-se sozinha quando começas a andar (com GPS e consumo).",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    DaTrotinete(e.viagens, e.ligacao == Ligacao.LIGADA)
}

@Composable
private fun Gravacao(e: Estado, defs: Definicoes) {
    val g = e.gravacao
    val ligada = e.ligacao == Ligacao.LIGADA
    Cartao {
        if (g == null) {
            Text(
                if (defs.gravarViagens) "As viagens gravam-se sozinhas quando começas a andar." else "A gravação automática está desligada (Controlo → Bateria e viagens).",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { enviar(Comando.GravarViagem(true)) }, enabled = ligada, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FiberManualRecord, null, tint = Vermelho)
                Spacer(Modifier.width(8.dp))
                Text("Gravar uma viagem agora")
            }
            return@Cartao
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.FiberManualRecord, null, tint = Vermelho, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("A gravar viagem", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${(System.currentTimeMillis() - g.inicio) / 60_000} min", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Numero(g.km.fmt("km", 2), "distância", Modifier.weight(1f))
            Numero(g.kmh.fmt("km/h", 0), "agora (GPS)", Modifier.weight(1f))
            Numero((if (g.km > 0.2) g.wh / g.km else null).fmt("Wh/km", 0), "consumo", Modifier.weight(1f))
        }
        Button(
            onClick = { enviar(Comando.GravarViagem(false)) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
        ) {
            Icon(Icons.Default.Stop, null)
            Spacer(Modifier.width(8.dp))
            Text("Terminar e guardar")
        }
    }
}

@Composable
private fun Resumo(viagens: List<ViagemGps>, precoKwh: Float) {
    val hoje = LocalDate.now()
    fun desde(dias: Long) = hoje.minus(dias, ChronoUnit.DAYS).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Composable
    fun linha(titulo: String, lista: List<ViagemGps>) {
        val km = lista.sumOf { it.km }
        val wh = lista.sumOf { it.wh }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(titulo, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Numero(km.fmt("km"), "${lista.size} viagens", Modifier.weight(1f))
                Numero((if (km > 0.2) wh / km else null).fmt("Wh/km", 0), "consumo", Modifier.weight(1f))
                Numero((wh / 1000 * precoKwh).fmt("€", 2), "eletricidade", Modifier.weight(1f))
            }
        }
    }
    Cartao(titulo = "Resumo") {
        linha("Últimos 7 dias", viagens.filter { it.inicio >= desde(7) })
        HorizontalDivider()
        linha("Últimos 30 dias", viagens.filter { it.inicio >= desde(30) })
    }
}

@Composable
private fun CartaoViagem(v: ViagemGps, aberta: Boolean, abrir: () -> Unit, apagar: () -> Unit) {
    Cartao(modifier = Modifier.clickable(onClick = abrir)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(data(v.inicio), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${v.km.fmt("km")} · ${v.minutos.toInt()} min", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    buildList {
                        add("média ${v.velMedia.fmt("km/h", 0)}")
                        if (v.velMax > 0) add("máx. ${v.velMax.fmt("km/h", 0)}")
                        v.whPorKm?.let { add("${it.fmt("Wh/km", 0)}") }
                    }.joinToString(" · "),
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (v.pontos.size >= 2) Percurso(v.pontos, Modifier.size(72.dp))
        }
        if (!aberta) return@Cartao
        HorizontalDivider()
        if (v.pontos.size >= 2) {
            Percurso(v.pontos, Modifier.fillMaxWidth().height(220.dp))
            Text("Velocidade (GPS)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            GraficoVelocidade(v.pontos, Modifier.fillMaxWidth().height(90.dp))
        } else {
            Text("Sem pontos de GPS nesta viagem (distância contada pela trotinete).", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Linha("Energia gasta", v.wh.fmt("Wh", 0))
        if (v.bateriaInicio != null && v.bateriaFim != null) Linha("Bateria", "${v.bateriaInicio}% → ${v.bateriaFim}%")
        v.modo?.let { Linha("Modo", NOMES_MODO[it] ?: "$it") }
        TextButton(onClick = apagar) { Text("Apagar viagem", color = Vermelho) }
    }
}

/** Desenha o percurso (sem mapa por baixo): projeção simples lat/lon, ajustada à caixa. */
@Composable
private fun Percurso(pontos: List<Ponto>, modifier: Modifier) {
    val cor = Verde
    Canvas(modifier) {
        val lat0 = pontos.map { it.lat }.average()
        val k = cos(Math.toRadians(lat0))
        val xs = pontos.map { it.lon * k }
        val ys = pontos.map { -it.lat }
        val (minX, maxX) = xs.min() to xs.max()
        val (minY, maxY) = ys.min() to ys.max()
        val escala = minOf(size.width / (maxX - minX).coerceAtLeast(1e-9), size.height / (maxY - minY).coerceAtLeast(1e-9)) * 0.9
        val dx = (size.width - (maxX - minX) * escala) / 2
        val dy = (size.height - (maxY - minY) * escala) / 2
        fun p(i: Int) = Offset(((xs[i] - minX) * escala + dx).toFloat(), ((ys[i] - minY) * escala + dy).toFloat())
        val path = Path().apply {
            moveTo(p(0).x, p(0).y)
            for (i in 1 until pontos.size) lineTo(p(i).x, p(i).y)
        }
        drawPath(path, cor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(Azul, 4.dp.toPx(), p(0))
        drawCircle(Vermelho, 4.dp.toPx(), p(pontos.lastIndex))
    }
}

@Composable
private fun GraficoVelocidade(pontos: List<Ponto>, modifier: Modifier) {
    val cor = Ambar
    val grelha = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier) {
        val max = maxOf(30f, pontos.maxOf { it.kmh })
        val t0 = pontos.first().t
        val dt = (pontos.last().t - t0).coerceAtLeast(1)
        // linha dos 25 km/h (limite legal)
        val y25 = size.height * (1 - 25f / max)
        drawLine(grelha, Offset(0f, y25), Offset(size.width, y25), strokeWidth = 1.dp.toPx())
        val path = Path()
        pontos.forEachIndexed { i, p ->
            val x = size.width * (p.t - t0) / dt
            val y = size.height * (1 - p.kmh / max)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, cor, style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun DaTrotinete(viagens: List<Viagem>, ligada: Boolean) {
    Cartao(titulo = "Guardadas na trotinete (últimas 10)") {
        if (viagens.isEmpty()) {
            Text(if (ligada) "A ler da trotinete…" else "Liga-te à trotinete para as ver.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Cartao
        }
        viagens.forEach { v ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text("${v.km.fmt("km")} · ${v.minutos.toInt()} min", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text("média ${v.media.fmt("km/h")}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun Numero(valor: String, legenda: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(valor, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text(legenda, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
