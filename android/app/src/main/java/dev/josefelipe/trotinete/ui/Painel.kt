package dev.josefelipe.trotinete.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.josefelipe.trotinete.dados.Definicoes
import dev.josefelipe.trotinete.protocolo.P
import dev.josefelipe.trotinete.servico.Comando
import dev.josefelipe.trotinete.servico.Controlo
import dev.josefelipe.trotinete.servico.Estado
import dev.josefelipe.trotinete.servico.Ligacao

val NOMES_MODO = mapOf(11L to "Walk", 2L to "Drive", 3L to "Sport")

private enum class Aba(val titulo: String, val icone: ImageVector) {
    INICIO("Início", Icons.Default.Home),
    CONTROLO("Controlo", Icons.Default.Tune),
    VIAGENS("Viagens", Icons.Default.Route),
    DETALHES("Detalhes", Icons.Default.Info),
}

fun enviar(c: Comando) {
    Controlo.comandos.trySend(c)
}

@Composable
fun Painel(defs: Definicoes, ligar: () -> Unit, desligar: () -> Unit, abrirAjustes: () -> Unit) {
    val e by Controlo.estado.collectAsStateWithLifecycle()
    var aba by rememberSaveable { mutableStateOf(Aba.INICIO) }

    FundoPainel {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Cabecalho(e, ligar, abrirAjustes)
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                e.aviso?.let { Aviso(it) }
                when (aba) {
                    Aba.INICIO -> AbaInicio(e)
                    Aba.CONTROLO -> AbaControlo(e, defs, desligar)
                    Aba.VIAGENS -> AbaViagens(e, defs)
                    Aba.DETALHES -> AbaDetalhes(e)
                }
                // Espaço para a barra flutuante não tapar o último cartão.
                Spacer(Modifier.navigationBarsPadding().height(96.dp))
            }
        }
        BarraNavegacao(
            aba,
            { aba = it },
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun Cabecalho(e: Estado, ligar: () -> Unit, abrirAjustes: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Scooter 5", style = MaterialTheme.typography.titleLarge)
            EstadoLigacao(e)
        }
        if (e.ligacao == Ligacao.PARADA) {
            TextButton(onClick = ligar) { Text("Ligar") }
        }
        Box(
            Modifier.size(44.dp).vidro(22.dp).clickable(onClick = abrirAjustes),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Settings, "Ajustes", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Barra de navegação flutuante: a aba ativa estica e mostra o nome. */
@Composable
private fun BarraNavegacao(atual: Aba, escolher: (Aba) -> Unit, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().vidro(30.dp, cor = Color(0xFF10141B)).padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Aba.entries.forEach { a ->
            val ativa = a == atual
            val fundo by animateColorAsState(if (ativa) Verde.copy(alpha = 0.16f) else Color.Transparent, label = "aba")
            val cor by animateColorAsState(if (ativa) Verde else MaterialTheme.colorScheme.onSurfaceVariant, label = "abaCor")
            Row(
                Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(fundo)
                    .clickable { escolher(a) }
                    .animateContentSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(a.icone, a.titulo, tint = cor, modifier = Modifier.size(22.dp))
                if (ativa) {
                    Spacer(Modifier.width(8.dp))
                    Text(a.titulo, color = cor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun EstadoLigacao(e: Estado) {
    val (texto, cor) = when (e.ligacao) {
        Ligacao.LIGADA -> "Ligada" + (e.rssi?.let { " · ${barras(it)}" } ?: "") to Verde
        Ligacao.A_LIGAR -> "A ligar…" to Ambar
        Ligacao.SEM_LIGACAO -> "Sem ligação · a tentar" to Vermelho
        Ligacao.PARADA -> "Desligada" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    // O ponto pulsa enquanto procura a trotinete.
    val pulso = rememberInfiniteTransition(label = "pulso")
    val alfa by pulso.animateFloat(1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alfa")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .alpha(if (e.ligacao == Ligacao.A_LIGAR || e.ligacao == Ligacao.SEM_LIGACAO) alfa else 1f)
                .clip(CircleShape)
                .background(cor),
        )
        Spacer(Modifier.width(6.dp))
        Text(texto, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun barras(rssi: Int) = when {
    rssi > -65 -> "sinal forte"
    rssi > -80 -> "sinal bom"
    rssi > -90 -> "sinal fraco"
    else -> "sinal muito fraco"
}

@Composable
private fun Aviso(texto: String) {
    Box(
        Modifier.fillMaxWidth().vidro(16.dp, cor = Ambar.copy(alpha = 0.10f)).padding(14.dp),
    ) { Text(texto, color = Ambar, fontSize = 14.sp) }
}

// --- Início ---

/** Limite legal (UE) da trotinete: o medidor de velocidade vai de 0 a 25 km/h. */
private const val KMH_MAXIMO = 25f

@Composable
private fun AbaInicio(e: Estado) {
    val ligada = e.ligacao == Ligacao.LIGADA
    val nivel = (e.bateria ?: 0L) / 100f
    // A andar, o medidor mostra a velocidade (GPS da gravação); parada, mostra a bateria.
    val kmh = e.gravacao?.kmh?.takeIf { (e.emAndamento ?: 0L) != 0L }

    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.Center) {
        if (kmh != null) {
            Medidor(kmh / KMH_MAXIMO, cor = Ciano, cor2 = Verde) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%.0f".format(kmh), style = MaterialTheme.typography.displayLarge)
                    Text("KM/H", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text("${e.bateria ?: "—"}% · ${e.autonomia.fmt("km", 0)}", fontSize = 13.sp, color = corBateria(nivel))
                }
            }
        } else {
            Medidor(nivel, cor = corBateria(nivel), cor2 = corBateria(nivel).copy(alpha = 0.55f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(e.bateria?.toString() ?: "—", style = MaterialTheme.typography.displayLarge)
                        Text("%", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 12.dp, start = 2.dp))
                    }
                    Text("${e.autonomia.fmt("km", 0)} de autonomia", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (e.aCarregar == true) {
                        Spacer(Modifier.height(6.dp))
                        Text("⚡ A carregar", color = Verde, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        Etiqueta(NOMES_MODO[e.modo]?.let { "Modo $it" } ?: "Modo —", Azul)
        when (e.bloqueada) {
            true -> Etiqueta(if (e.bloqueadaPorAfastamento) "Bloqueada (afastamento)" else "Bloqueada", Vermelho)
            false -> Etiqueta("Desbloqueada", Verde)
            null -> Unit
        }
    }

    BotaoBloquear(e.bloqueada, enabled = ligada && e.bloqueada != null)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val luz = e.definicoes[P.LUZ_TRASEIRA] as? Boolean
        AcaoRapida(Icons.Default.Lightbulb, "Luz traseira", ativo = luz == true, enabled = ligada && luz != null, Modifier.weight(1f)) {
            enviar(Comando.Definir(P.LUZ_TRASEIRA, if (luz == true) 0 else 1))
        }
        AcaoRapida(Icons.Default.Speed, NOMES_MODO[e.modo] ?: "Modo", ativo = false, enabled = ligada && e.modo != null, Modifier.weight(1f)) {
            enviar(Comando.ProximoModo)
        }
        AcaoRapida(Icons.Default.VolumeUp, "Encontrar", ativo = false, enabled = ligada, Modifier.weight(1f)) { enviar(Comando.Encontrar) }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Mosaico(Icons.Default.Bolt, e.potencia.fmt("W", 0), "Potência agora", Modifier.weight(1f), cor = Ambar)
        Mosaico(Icons.Default.ElectricBolt, e.voltagem.fmt("V"), "Voltagem", Modifier.weight(1f), cor = Azul)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Mosaico(Icons.Default.Route, e.viagem.fmt("km"), "Esta viagem", Modifier.weight(1f))
        Mosaico(Icons.Default.Straighten, e.total.fmt("km", 0), "Total percorrido", Modifier.weight(1f), cor = Roxo)
    }
}

/** Botão grande de bloquear: muda de cor e de ícone com animação. */
@Composable
private fun BotaoBloquear(bloqueada: Boolean?, enabled: Boolean) {
    val fechada = bloqueada == true
    val cor by animateColorAsState(
        when {
            !enabled -> Color.White.copy(alpha = 0.06f)
            fechada -> Verde
            else -> Vermelho
        },
        tween(400),
        label = "bloquear",
    )
    val texto = if (enabled) Color(0xFF07090D) else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(34.dp))
            .background(Brush.horizontalGradient(listOf(cor, cor.copy(alpha = if (enabled) 0.8f else 0.06f))))
            .clickable(enabled = enabled) { enviar(Comando.Bloquear(!fechada)) }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = if (enabled) 0.18f else 0f)),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(fechada, label = "icone") { f ->
                Icon(if (f) Icons.Default.LockOpen else Icons.Default.Lock, null, tint = texto, modifier = Modifier.size(26.dp))
            }
        }
        Text(
            if (fechada) "Desbloquear" else "Bloquear",
            color = texto,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f).padding(end = 52.dp),
        )
    }
}

@Composable
private fun Etiqueta(texto: String, cor: Color) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(cor.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(cor))
        Spacer(Modifier.width(6.dp))
        Text(texto, fontSize = 12.sp, color = cor, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AcaoRapida(icone: ImageVector, texto: String, ativo: Boolean, enabled: Boolean, modifier: Modifier, acao: () -> Unit) {
    val cor by animateColorAsState(if (ativo) Ambar else MaterialTheme.colorScheme.onSurface, label = "acao")
    Column(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .vidro(22.dp, cor = if (ativo) Ambar.copy(alpha = 0.14f) else Vidro)
            .clickable(enabled = enabled, onClick = acao)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icone, texto, tint = cor, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(8.dp))
        Text(texto, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
