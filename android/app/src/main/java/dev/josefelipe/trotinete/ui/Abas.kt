package dev.josefelipe.trotinete.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import dev.josefelipe.trotinete.dados.ViagemGps
import dev.josefelipe.trotinete.dados.RegistoBateria
import dev.josefelipe.trotinete.dados.Diario
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.josefelipe.trotinete.dados.Definicoes
import dev.josefelipe.trotinete.protocolo.Formatos
import dev.josefelipe.trotinete.protocolo.P
import dev.josefelipe.trotinete.protocolo.Prop
import dev.josefelipe.trotinete.servico.Comando
import dev.josefelipe.trotinete.servico.Estado
import dev.josefelipe.trotinete.servico.Ligacao

// --- Controlo ---

@Composable
fun AbaControlo(e: Estado, defs: Definicoes, desligar: () -> Unit) {
    val ligada = e.ligacao == Ligacao.LIGADA
    val d = e.definicoes

    @Composable
    fun interruptor(p: Prop, titulo: String, descricao: String?) {
        val v = d[p] as? Boolean ?: return
        Interruptor(titulo, descricao, v, enabled = ligada) { enviar(Comando.Definir(p, if (it) 1 else 0)) }
    }

    Cartao(titulo = "Luzes") {
        val traseira = d[P.LUZ_TRASEIRA] as? Boolean
        interruptor(P.LUZ_TRASEIRA, "Luz traseira sempre acesa", "Acesa enquanto andas; desligada só acende ao travar")
        if (traseira == false) {
            Text("À noite e com pouca visibilidade a luz é obrigatória na via pública.", fontSize = 12.sp, color = Ambar)
        }
        interruptor(P.LUZ_AUTO, "Luz automática", "Acende sozinha quando escurece")
        if (d.keys.none { it in listOf(P.LUZ_TRASEIRA, P.LUZ_AUTO) }) SemDados(ligada)
        Text(
            "A luz da frente liga-se no botão da trotinete (toque curto): a Scooter 5 não aceita esse comando por Bluetooth.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Cartao(titulo = "Condução") {
        e.modo?.let { m ->
            Opcoes("Modo", P.MODOS.map { it to (NOMES_MODO[it] ?: "$it") }, m, ligada) {
                // o serviço só sabe avançar para o seguinte: repete até chegar ao escolhido
                val passos = (P.MODOS.indexOf(it) - P.MODOS.indexOf(m) + P.MODOS.size) % P.MODOS.size
                repeat(passos) { enviar(Comando.ProximoModo) }
            }
        }
        (d[P.TRAVAGEM_REGENERATIVA] as? Long)?.let { v ->
            Opcoes("Travagem regenerativa", listOf(30L to "Fraca", 60L to "Média", 90L to "Forte"), v, ligada) {
                enviar(Comando.Definir(P.TRAVAGEM_REGENERATIVA, it))
            }
        }
        interruptor(P.CRUISE, "Cruise control", "Mantém a velocidade ao fim de uns segundos a acelerar igual")
        interruptor(P.TCS, "Controlo de tração", "Evita que a roda patine ao arrancar")
        interruptor(P.ASR, "Antiderrapagem (ASR)", null)
        interruptor(P.DESCIDA_ASSISTIDA, "Descida assistida", "Trava sozinha em descidas")
        interruptor(P.TRAVAO_RAMPA, "Travão em rampa", "Segura a trotinete parada numa subida")
    }

    Cartao(titulo = "Segurança") {
        BloqueioAutomatico(defs, e)
    }

    Cartao(titulo = "Bateria e viagens") {
        var avisos by remember { mutableStateOf(defs.avisosBateria) }
        var limiarCarga by remember { mutableStateOf(defs.limiarCarga.toLong()) }
        var gravar by remember { mutableStateOf(defs.gravarViagens) }
        var preco by remember { mutableFloatStateOf(defs.precoKwh) }
        Interruptor("Avisos da bateria", "Carga completa, bateria fraca, temperatura", avisos) {
            avisos = it
            defs.avisosBateria = it
        }
        if (avisos) {
            Opcoes("Avisar a carregar quando chegar a", listOf(0L to "Não", 80L to "80%", 90L to "90%", 100L to "100%"), limiarCarga) {
                limiarCarga = it
                defs.limiarCarga = it.toInt()
            }
            Text("Parar nos 80–90% faz a bateria de lítio durar mais anos.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        Interruptor("Gravar viagens com GPS", "Começa sozinha quando andas; precisa da localização", gravar) {
            gravar = it
            defs.gravarViagens = it
        }
        Text("Preço da eletricidade: ${"%.2f".format(preco)} €/kWh", fontSize = 14.sp)
        Slider(value = preco, onValueChange = { preco = it }, onValueChangeFinished = { defs.precoKwh = preco }, valueRange = 0.05f..0.40f, steps = 34)
    }

    Cartao(titulo = "Ligação") {
        ExcecaoBateria()
        OutlinedButton(onClick = desligar, modifier = Modifier.fillMaxWidth()) { Text("Parar a ligação à trotinete") }
    }
}

@Composable
private fun SemDados(ligada: Boolean) {
    Text(
        if (ligada) "A ler da trotinete…" else "Liga-te à trotinete para ver estas opções.",
        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** O Android (e a Motorola em especial) pode parar a app em segundo plano; isto pede para não o fazer. */
@SuppressLint("BatteryLife")
@Composable
private fun ExcecaoBateria() {
    val context = LocalContext.current
    val pm = context.getSystemService(PowerManager::class.java)
    if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
        Text("✓ A app pode ficar ligada em segundo plano", fontSize = 13.sp, color = Verde)
        return
    }
    Text("Para o bloqueio automático funcionar com o ecrã desligado, deixa a app correr em segundo plano.", fontSize = 13.sp)
    OutlinedButton(
        onClick = {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
            )
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Permitir em segundo plano") }
}

@Composable
private fun BloqueioAutomatico(defs: Definicoes, e: Estado) {
    var ativo by remember { mutableStateOf(defs.autoBloqueio) }
    var desbloquear by remember { mutableStateOf(defs.autoDesbloqueio) }
    var limiar by remember { mutableFloatStateOf(defs.limiarDbm.toFloat()) }
    var segundos by remember { mutableFloatStateOf(defs.segundos.toFloat()) }

    Interruptor("Bloquear quando me afasto", "Só bloqueia com a trotinete parada", ativo) {
        ativo = it
        defs.autoBloqueio = it
    }
    if (!ativo) return
    HorizontalDivider()
    val agora = e.rssi
    Text(
        "Sinal agora: ${agora?.let { "$it dBm" } ?: "—"} · bloqueia abaixo de ${limiar.toInt()} dBm",
        fontSize = 13.sp,
        color = if (agora != null && agora < limiar) Ambar else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text("Mais à esquerda = só bloqueia mais longe", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Slider(
        value = limiar, onValueChange = { limiar = it },
        onValueChangeFinished = { defs.limiarDbm = limiar.toInt() },
        valueRange = -100f..-60f, steps = 39,
    )
    Text("Esperar ${segundos.toInt()} s de sinal fraco antes de bloquear", fontSize = 13.sp)
    Slider(
        value = segundos, onValueChange = { segundos = it },
        onValueChangeFinished = { defs.segundos = segundos.toInt() },
        valueRange = 3f..20f, steps = 16,
    )
    Interruptor("Desbloquear quando volto", "Só se foi a app que bloqueou", desbloquear) {
        desbloquear = it
        defs.autoDesbloqueio = it
    }
    if (e.bloqueadaPorAfastamento) Text("🔒 Bloqueada pela app porque te afastaste", color = Verde, fontSize = 13.sp)
}

// --- Detalhes ---

/** Bateria da Scooter 5: 477 Wh (10,2 Ah) segundo a Xiaomi. */
private const val BATERIA_WH = 477.0

@Composable
fun AbaDetalhes(e: Estado) {
    val info = e.info
    val mais = (info[P.MAIS_BATERIA] as? String)?.let(Formatos::maisBateria)
    val context = LocalContext.current
    val diario = remember { Diario(context) }
    val viagens by produceState(emptyList<ViagemGps>(), e.viagensGuardadas) { value = withContext(Dispatchers.IO) { diario.viagens() } }
    val registos by produceState(emptyList<RegistoBateria>(), e.ciclos) { value = withContext(Dispatchers.IO) { diario.registosBateria() } }

    AutonomiaReal(e, mais, viagens)

    Cartao(titulo = "Bateria") {
        Linha("Nível", e.bateria?.let { "$it %" } ?: "—")
        (info[P.BATERIA_MAH] as? Long)?.let { Linha("Carga restante", "$it mAh") }
        Linha("Voltagem", e.voltagem.fmt("V", 2))
        Linha("Corrente", e.corrente.fmt("A", 2))
        Linha("Potência", e.potencia.fmt("W", 0))
        Linha("Temperatura", e.tempBateria?.let { "$it °C" } ?: "—")
        Linha("Saúde", e.saudeBateria?.let { "$it %" } ?: "—", destaque = e.saudeBateria?.let { if (it >= 80) Verde else Ambar })
        Linha("Ciclos de carga", e.ciclos?.toString() ?: "—")
        mais?.let {
            Linha("Energia entregue (vida)", it.energiaKwh.fmt("kWh", 2))
            Linha("Descargas profundas", "${it.descargasProfundas}", destaque = if (it.descargasProfundas > 0) Ambar else null)
        }
        (info[P.MAIS_BATERIA_2] as? String)?.let(Formatos::ultimaTemperaturaExtrema)?.let { Linha("Última temperatura extrema", it) }
        e.aCarregar?.let { Linha("A carregar", if (it) "sim" else "não") }
    }

    if (registos.size >= 2) {
        Cartao(titulo = "Saúde da bateria ao longo do tempo") {
            val a = registos.first()
            val b = registos.last()
            Linha("Desde ${Formatos.data(a.dia.replace("-", ""))}", "${a.saude}% → ${b.saude}%")
            Linha("Ciclos", "${a.ciclos} → ${b.ciclos}")
            val km = b.totalKm - a.totalKm
            if (km > 5) Linha("Consumo neste período", ((b.energiaKwh - a.energiaKwh) * 1000 / km).fmt("Wh/km", 1))
        }
    }

    Cartao(titulo = "Trotinete") {
        Linha("Total percorrido", e.total.fmt("km"))
        Linha("Temperatura", e.tempTrotinete?.let { "$it °C" } ?: "—")
        Linha("Código de erro", e.codigoErro?.let { if (it == 0L) "nenhum" else "$it" } ?: "—", destaque = e.codigoErro?.let { if (it == 0L) null else Vermelho })
        (info[P.PNEUS] as? String)?.let(Formatos::pneus)?.let {
            Linha("Lembrete dos pneus", if (it.ativo) "faltam ${it.diasRestantes} de ${it.intervaloDias} dias" else "desligado")
        }
        (info[P.DATA_PRODUCAO] as? String)?.let { Linha("Produção", Formatos.data(it)) }
        (info[P.DATA_ATIVACAO] as? String)?.let { Linha("Ativação", Formatos.data(it)) }
    }

    Cartao(titulo = "Versões e números de série") {
        Linha("Firmware Bluetooth", e.firmware ?: "—")
        e.firmwareControlador?.let { Linha("Firmware do controlador", it) }
        e.hardware?.let { Linha("Chip Bluetooth", it) }
        (info[P.FIRMWARE_BMS] as? String)?.let { Linha("Firmware da bateria (BMS)", it) }
        (info[P.SN_TROTINETE] as? String)?.let { Linha("N.º de série", it) }
        (info[P.SN_BATERIA] as? String)?.let { Linha("N.º de série da bateria", it) }
    }

    Cartao(titulo = "Diagnóstico") {
        Text(
            "Lê todos os valores que a trotinete responde, em bruto. Serve para confirmar unidades e encontrar dados novos (cerca de 1 minuto).",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { enviar(Comando.Explorar) },
            enabled = e.ligacao == Ligacao.LIGADA && !e.aExplorar,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (e.aExplorar) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (e.aExplorar) "A ler… (${e.diagnostico?.size ?: 0})" else "Ler todos os valores")
        }
        e.diagnostico?.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }
}


/**
 * Porque é que a bateria não chega aos 60 km: compara o consumo real (energia que a bateria já
 * entregou ÷ km feitos) com o da Xiaomi (477 Wh ÷ 60 km ≈ 8 Wh/km, medido em condições ideais).
 */
@Composable
private fun AutonomiaReal(e: Estado, mais: Formatos.MaisBateria?, viagens: List<ViagemGps>) {
    val vida = mais?.energiaKwh?.let { kwh -> e.total?.takeIf { it > 10 }?.let { kwh * 1000 / it } }
    val recentes = viagens.take(10).filter { it.whPorKm != null }
    val recente = if (recentes.sumOf { it.km } >= 2) recentes.sumOf { it.wh } / recentes.sumOf { it.km } else null
    val consumo = recente ?: vida ?: return
    val util = BATERIA_WH * (e.saudeBateria ?: 100) / 100.0
    Cartao(titulo = "Autonomia real") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Numero(e.bateria?.let { (util * it / 100 / consumo).fmt("km", 0) } ?: "—", "com a carga atual", Modifier.weight(1f))
            Numero((util / consumo).fmt("km", 0), "com carga cheia", Modifier.weight(1f))
        }
        vida?.let { Linha("O teu consumo (vida da trotinete)", it.fmt("Wh/km", 1)) }
        recente?.let { Linha("O teu consumo (últimas viagens)", it.fmt("Wh/km", 1)) }
        Linha("Consumo nos \"60 km\" da Xiaomi", (BATERIA_WH / 60).fmt("Wh/km", 1))
        e.autonomia?.let { Linha("A trotinete estima", it.fmt("km", 0)) }
        Text(
            "Os 60 km oficiais são medidos com ~75 kg, a 15 km/h constantes e em plano. O que mais gasta: modo Sport (25 km/h gasta muito mais ar que 15), subidas, peso, pneus vazios, frio e arranques a fundo. Parada e ligada a trotinete gasta ~57 W (≈12% da bateria por hora): desliga-a quando paras.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
