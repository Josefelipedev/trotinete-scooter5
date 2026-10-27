package dev.josefelipe.trotinete.servico

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.josefelipe.trotinete.R
import dev.josefelipe.trotinete.ble.LigacaoBle
import dev.josefelipe.trotinete.dados.Definicoes
import dev.josefelipe.trotinete.dados.Diario
import dev.josefelipe.trotinete.protocolo.Formatos
import dev.josefelipe.trotinete.protocolo.P
import dev.josefelipe.trotinete.protocolo.Prop
import dev.josefelipe.trotinete.protocolo.Tipo
import dev.josefelipe.trotinete.protocolo.Trotinete
import dev.josefelipe.trotinete.protocolo.TrotineteException
import dev.josefelipe.trotinete.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Mantém a ligação à trotinete em segundo plano (com notificação fixa, como o Android exige):
 * lê os dados, executa os comandos do ecrã e trata do bloqueio automático por afastamento.
 */
class ServicoTrotinete : LifecycleService() {

    companion object {
        private const val CANAL_LIGACAO = "ligacao"
        private const val CANAL_ALERTAS = "alertas"
        private const val ID_FIXA = 1
        private const val ID_ALERTA = 2
        private const val TAG = "Trotinete"

        fun iniciar(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, ServicoTrotinete::class.java))

        fun parar(context: Context) = context.stopService(Intent(context, ServicoTrotinete::class.java))
    }

    private lateinit var defs: Definicoes
    private lateinit var gravador: Gravador
    private lateinit var diario: Diario
    private var avisouCarga = false
    private var avisouFraca = false
    private var avisouTemperatura = false
    private var ciclo: Job? = null

    override fun onCreate() {
        super.onCreate()
        defs = Definicoes(this)
        diario = Diario(this)
        gravador = Gravador(this, diario)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CANAL_LIGACAO, "Ligação à trotinete", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CANAL_ALERTAS, "Alertas da trotinete", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // com GPS autorizado, o serviço também pode usar a localização (gravar viagens com o ecrã desligado)
        val gps = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val tipos = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or (if (gps) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        ServiceCompat.startForeground(this, ID_FIXA, notificacaoFixa("A ligar…"), tipos)
        if (ciclo?.isActive != true) ciclo = lifecycleScope.launch { cicloDeLigacao() }
        return START_STICKY
    }

    override fun onDestroy() {
        gravador.terminar(Controlo.estado.value)
        Controlo._estado.value = Estado()
        super.onDestroy()
    }

    // --- notificações ---

    private fun abrirApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notificacaoFixa(texto: String): Notification =
        NotificationCompat.Builder(this, CANAL_LIGACAO)
            .setSmallIcon(R.drawable.ic_trotinete)
            .setContentTitle("Trotinete")
            .setContentText(texto)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(abrirApp())
            .build()

    private fun podeNotificar() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun atualizarNotificacao(texto: String) {
        if (podeNotificar()) getSystemService(NotificationManager::class.java).notify(ID_FIXA, notificacaoFixa(texto))
    }

    private fun alerta(texto: String) {
        if (!podeNotificar()) return
        val n = NotificationCompat.Builder(this, CANAL_ALERTAS)
            .setSmallIcon(R.drawable.ic_trotinete)
            .setContentTitle("Trotinete")
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setAutoCancel(true)
            .setContentIntent(abrirApp())
            .build()
        getSystemService(NotificationManager::class.java).notify(ID_ALERTA, n)
    }

    private fun resumo(e: Estado): String = buildList {
        e.bateria?.let { add("🔋 $it%") }
        e.autonomia?.let { add("%.0f km".format(it)) }
        e.bloqueada?.let { add(if (it) "🔒 Bloqueada" else "🔓 Desbloqueada") }
    }.joinToString(" · ").ifEmpty { "Ligada" }

    // --- ligação ---

    private suspend fun cicloDeLigacao() {
        var espera = 3_000L
        while (true) {
            val mac = defs.mac
            val ltmk = defs.ltmk
            if (mac == null || ltmk == null) {
                Controlo._estado.update { it.copy(ligacao = Ligacao.SEM_LIGACAO, aviso = "Falta configurar a trotinete") }
                stopSelf()
                return
            }
            Controlo._estado.update { it.copy(ligacao = Ligacao.A_LIGAR, aviso = null) }
            atualizarNotificacao("A ligar…")
            val adaptador = getSystemService(BluetoothManager::class.java).adapter
            val t = Trotinete(LigacaoBle(this, adaptador.getRemoteDevice(mac)))
            var ligou = false
            try {
                t.ligar()
                ligou = true
                t.login(ltmk)
                espera = 3_000L
                Controlo._estado.update { it.copy(ligacao = Ligacao.LIGADA, aviso = null) }
                sessao(t)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Trotinete", "Ligação terminou", e)
                val estava = Controlo._estado.value
                Controlo._estado.update { it.copy(ligacao = Ligacao.SEM_LIGACAO, aviso = e.message, rssi = null) }
                atualizarNotificacao("Sem ligação · a tentar de novo")
                if (estava.ligacao == Ligacao.LIGADA && estava.bloqueada == false && defs.autoBloqueio) {
                    alerta("Perdi a ligação e a trotinete ficou DESBLOQUEADA. Se não a desligaste, volta para perto dela.")
                }
            } finally {
                gravador.terminar(Controlo.estado.value)
                t.fechar()
            }
            // se chegou a ligar e só o login falhou, volta já; se nem ligou (longe/desligada), espera cada vez mais
            if (ligou) {
                delay(1_500)
            } else {
                delay(espera)
                espera = (espera * 2).coerceAtMost(30_000L)
            }
        }
    }

    /** Corre enquanto a ligação estiver de pé; qualquer falha Bluetooth sai daqui e o ciclo volta a ligar. */
    private suspend fun sessao(t: Trotinete) = coroutineScope {
        while (Controlo.comandos.tryReceive().isSuccess) Unit // comandos de quando não havia ligação já não valem
        launch {
            t.ativa.first { !it }
            throw TrotineteException("A trotinete desligou-se")
        }
        launch { vigiarSinal(t) }
        launch {
            for (c in Controlo.comandos) executar(t, c)
        }
        Controlo._estado.update {
            it.copy(firmware = ler(t, P.FIRMWARE) as? String, firmwareControlador = t.infoMcu(1), hardware = t.infoMcu(3))
        }
        lerRapidas(t)
        lerLentas(t)
        lerInfo(t)
        var volta = 1
        while (true) {
            lerRapidas(t)
            gravador.amostra(Controlo.estado.value, defs.gravarViagens)
            verificarBateria(Controlo.estado.value)
            if (volta % 15 == 0) lerLentas(t)
            atualizarNotificacao(resumo(Controlo.estado.value))
            volta++
            delay(2_000)
        }
    }

    /** Lê uma propriedade; se a trotinete a recusar devolve null (falhas de Bluetooth continuam a subir). */
    private suspend fun ler(t: Trotinete, p: Prop): Any? = try {
        t.get(p)
    } catch (e: TrotineteException) {
        null
    }

    private fun Any?.l() = (this as? Long)

    /** Lê um valor numérico já na unidade certa (aplica a escala da propriedade). */
    private suspend fun num(t: Trotinete, p: Prop): Float? = when (val v = ler(t, p)) {
        is Float -> v * p.escala
        is Long -> v * p.escala
        else -> null
    }

    private suspend fun lerRapidas(t: Trotinete) {
        val bateria = ler(t, P.BATERIA).l()
        val voltagem = num(t, P.VOLTAGEM)
        val corrente = num(t, P.CORRENTE)
        val potencia = num(t, P.POTENCIA)
        val autonomia = num(t, P.AUTONOMIA)
        val viagem = num(t, P.VIAGEM)
        val bloqueada = ler(t, P.BLOQUEADA) as? Boolean
        val emAndamento = ler(t, P.EM_ANDAMENTO).l()
        val modo = ler(t, P.MODO).l()
        // a Scooter 5 manda 1/0 aqui (não verdadeiro/falso)
        val aCarregar = when (val v = ler(t, P.A_CARREGAR)) {
            is Boolean -> v
            is Long -> v != 0L
            else -> null
        }
        Controlo._estado.update {
            it.copy(
                bateria = bateria, voltagem = voltagem, corrente = corrente, potencia = potencia,
                autonomia = autonomia, viagem = viagem, bloqueada = bloqueada, emAndamento = emAndamento,
                modo = modo, aCarregar = aCarregar,
                // se alguém a desbloqueou à mão, deixa de ser "bloqueada por afastamento"
                bloqueadaPorAfastamento = it.bloqueadaPorAfastamento && bloqueada == true,
            )
        }
    }

    private suspend fun lerLentas(t: Trotinete) {
        val total = num(t, P.TOTAL)
        val media = num(t, P.VELOCIDADE_MEDIA)
        val maxima = num(t, P.VELOCIDADE_MAXIMA)
        val tempo = num(t, P.TEMPO_VIAGEM)
        val tempBat = ler(t, P.TEMP_BATERIA).l()
        val tempTrot = ler(t, P.TEMP_TROTINETE).l()
        val ciclos = ler(t, P.CICLOS).l()
        val saude = ler(t, P.SAUDE_BATERIA).l()
        val erro = ler(t, P.ERRO).l()
        Controlo._estado.update {
            it.copy(
                total = total, velocidadeMedia = media, velocidadeMaxima = maxima, tempoViagem = tempo,
                tempBateria = tempBat, tempTrotinete = tempTrot, ciclos = ciclos, saudeBateria = saude, codigoErro = erro,
            )
        }
        lerDefinicoes(t)
        registarBateria(Controlo.estado.value)
    }

    /** Uma fotografia por dia da saúde da bateria (para ver a evolução ao longo dos meses). */
    private fun registarBateria(e: Estado) {
        val energia = (e.info[P.MAIS_BATERIA] as? String)?.let(Formatos::maisBateria)?.energiaKwh ?: return
        diario.registarBateria(e.saudeBateria?.toInt() ?: return, e.ciclos?.toInt() ?: return, energia, e.total?.toDouble() ?: return)
    }

    private fun verificarBateria(e: Estado) {
        if (!defs.avisosBateria) return
        val b = e.bateria?.toInt() ?: return
        if (e.aCarregar == true) {
            val limiar = defs.limiarCarga
            if (limiar in 1..100 && b >= limiar && !avisouCarga) {
                avisouCarga = true
                alerta(
                    if (limiar >= 100) "Bateria cheia (100%). Podes tirar o carregador."
                    else "Bateria a $b%. Parar a carga nos 80–90% faz a bateria durar mais anos.",
                )
            }
        } else {
            avisouCarga = false
            if (b <= 15 && !avisouFraca) {
                avisouFraca = true
                alerta("Bateria a $b%: restam cerca de ${e.autonomia?.let { "%.0f".format(it) } ?: "?"} km pela trotinete (na prática costuma ser menos).")
            }
        }
        if (b >= 25) avisouFraca = false
        val temp = e.tempBateria
        if (temp != null && (temp >= 50 || temp <= 0) && !avisouTemperatura) {
            avisouTemperatura = true
            alerta("Temperatura da bateria a $temp °C. ${if (temp >= 50) "Deixa-a arrefecer antes de carregar ou andar." else "Com frio a bateria rende menos; não a carregues abaixo de 0 °C."}")
        }
    }

    private suspend fun lerDefinicoes(t: Trotinete) {
        val valores = buildMap { for (p in P.DEFINICOES) ler(t, p)?.let { put(p, it) } }
        Controlo._estado.update { it.copy(definicoes = valores) }
    }

    private suspend fun lerInfo(t: Trotinete) {
        val info = buildMap { for (p in P.INFO) ler(t, p)?.let { put(p, it) } }
        val historico = P.HISTORICO.mapNotNull { ler(t, it) as? String }
        Controlo._estado.update { it.copy(info = info, viagens = Formatos.viagens(historico)) }
    }

    private suspend fun depurar(t: Trotinete, texto: String) {
        val a = texto.trim().split(Regex("\\s+"))
        try {
            when (a.firstOrNull()) {
                "get" -> Log.i(TAG, "DEP get (${a[1]},${a[2]}) = ${t.get(Prop(a[1].toInt(), a[2].toInt(), Tipo.STRING))}")
                "set" -> {
                    val (s, p) = a[1].toInt() to a[2].toInt()
                    require((s to p) !in P.PROIBIDAS) { "propriedade proibida" }
                    t.set(Prop(s, p, a[3].toInt()), a[4].toLong())
                    Log.i(TAG, "DEP set ($s,$p) = ${a[4]} aceite; agora ${ler(t, Prop(s, p, Tipo.STRING))}")
                }
                "explorar" -> {
                    // "explorar" ou "explorar s1 s2 p1 p2" (só leitura)
                    if (a.size == 5) explorar(t, a[1].toInt()..a[2].toInt(), a[3].toInt()..a[4].toInt()) else explorar(t)
                    Controlo.estado.value.diagnostico?.forEach { Log.i(TAG, "DEP $it") }
                }
                else -> Log.w(TAG, "DEP comando desconhecido: $texto")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "DEP '$texto' falhou: ${e.message}")
        }
    }

    /** Lê tudo o que a trotinete responder (siid 1-6, piid 1-20), para verificar valores e unidades. */
    private suspend fun explorar(t: Trotinete, servicos: IntRange = 1..6, props: IntRange = 1..20) {
        Controlo._estado.update { it.copy(aExplorar = true, diagnostico = emptyList()) }
        for (siid in servicos) for (piid in props) {
            if ((siid to piid) in P.PROIBIDAS) continue
            val linha = try {
                val v = t.get(Prop(siid, piid, Tipo.STRING))
                "($siid,$piid) = ${if (v is Float) "%.2f".format(v) else v}"
            } catch (e: TrotineteException) {
                continue // não existe neste modelo
            }
            Controlo._estado.update { it.copy(diagnostico = (it.diagnostico ?: emptyList()) + linha) }
        }
        Controlo._estado.update { it.copy(aExplorar = false) }
    }

    private suspend fun executar(t: Trotinete, c: Comando) {
        try {
            when (c) {
                is Comando.Bloquear -> {
                    if (c.bloquear && ler(t, P.EM_ANDAMENTO).l() != 0L) {
                        Controlo._estado.update { it.copy(aviso = "Não bloqueio: a trotinete parece estar em andamento") }
                        return
                    }
                    t.set(P.BLOQUEADA, if (c.bloquear) 1 else 0)
                    Controlo._estado.update {
                        it.copy(bloqueada = ler(t, P.BLOQUEADA) as? Boolean, bloqueadaPorAfastamento = false, aviso = null)
                    }
                }
                Comando.ProximoModo -> {
                    val atual = Controlo.estado.value.modo
                    val proximo = P.MODOS[(P.MODOS.indexOf(atual) + 1) % P.MODOS.size]
                    t.set(P.MODO, proximo)
                    Controlo._estado.update { it.copy(modo = ler(t, P.MODO).l(), aviso = null) }
                }
                Comando.Encontrar -> t.set(P.ENCONTRAR, 1)
                is Comando.Definir -> {
                    Log.i(TAG, "SET (${c.prop.siid},${c.prop.piid}) = ${c.valor}")
                    t.set(c.prop, c.valor)
                    val novo = ler(t, c.prop)
                    Log.i(TAG, "SET (${c.prop.siid},${c.prop.piid}) aceite; a trotinete diz agora ${novo}")
                    Controlo._estado.update {
                        it.copy(definicoes = if (novo != null) it.definicoes + (c.prop to novo) else it.definicoes, aviso = null)
                    }
                }
                Comando.Explorar -> explorar(t)
                is Comando.GravarViagem ->
                    if (c.gravar) gravador.iniciar(Controlo.estado.value, manual = true) else gravador.terminar(Controlo.estado.value)
                is Comando.Depurar -> depurar(t, c.texto)
            }
        } catch (e: TrotineteException) {
            Log.w(TAG, "Comando $c falhou: ${e.message}")
            Controlo._estado.update { it.copy(aviso = e.message) }
        }
    }

    /**
     * Vigia o sinal para bloquear ANTES de a ligação cair — depois de cair já não há como enviar a ordem.
     * Lê a cada 0,8 s e, com o bloqueio automático ligado e a trotinete parada, bloqueia se:
     *   - o sinal cair a pique para um nível já fraco (afastaste-te depressa), ou
     *   - as leituras de sinal começarem a falhar já com o sinal fraco (a ligação está a morrer), ou
     *   - o sinal ficar abaixo do limiar durante os segundos configurados (afastamento lento).
     */
    private suspend fun vigiarSinal(t: Trotinete) {
        val janela = ArrayDeque<Int>()      // últimas leituras válidas (até 5): suaviza e deixa ver a queda
        var fracoDesde: Long? = null
        var forteDesde: Long? = null
        var ultimaValida: Int? = null       // último sinal suavizado, para decidir se uma falha é "a cair"
        var falhasSeguidas = 0
        var ultimaTentativa = 0L            // evita repetir a ordem de bloquear em cada leitura
        var semBloquearAte = 0L             // depois de desbloquear ao voltar, não volta a bloquear logo
        while (true) {
            delay(800)
            val e = Controlo.estado.value
            val agora = System.currentTimeMillis()
            val limiar = defs.limiarDbm
            val podeBloquear = defs.autoBloqueio && e.bloqueada == false && e.parada && agora >= semBloquearAte
            suspend fun bloquear(motivo: String) {
                if (agora - ultimaTentativa < 4_000) return
                ultimaTentativa = agora
                fracoDesde = null
                Log.i(TAG, "AUTO afastamento ($motivo, sinal=${ultimaValida ?: "?"} dBm) -> a bloquear")
                autoBloquear(t)
            }

            val r = t.rssi()
            if (r == null) {
                // a leitura falhou: se já vínhamos fracos, a ligação está a morrer -> última hipótese de bloquear
                falhasSeguidas++
                if (podeBloquear && falhasSeguidas >= 2 && (ultimaValida ?: 0) < limiar + 12) bloquear("leituras a falhar")
                continue
            }
            falhasSeguidas = 0
            janela.addLast(r)
            if (janela.size > 5) janela.removeFirst()
            val media = janela.takeLast(3).average().toInt()
            ultimaValida = media
            Controlo._estado.update { it.copy(rssi = media) }

            // queda a pique: o sinal mais recente está muito abaixo do pico recente e já está fraco
            val quedaRapida = janela.size >= 2 && janela.max() - r >= 12 && media < limiar + 8

            if (podeBloquear) {
                if (quedaRapida) {
                    bloquear("queda a pique")
                } else if (media < limiar) {
                    val desde = fracoDesde ?: agora
                    fracoDesde = desde
                    if (agora - desde >= defs.segundos * 1_000L) bloquear("sinal fraco ${defs.segundos}s")
                } else {
                    fracoDesde = null
                }
            } else {
                fracoDesde = null
            }

            // desbloqueio automático: só se fomos nós a bloquear, e com o sinal claramente mais forte que o
            // limiar. Era limiar+20, mas com −78 isso pedia −58 e ao pé da trotinete o sinal anda entre
            // −62 e −75 (medido a 27/out): nunca desbloqueava.
            if (defs.autoDesbloqueio && e.bloqueada == true && e.bloqueadaPorAfastamento && media > limiar + 10) {
                if (forteDesde == null) forteDesde = agora
                if (agora - forteDesde >= 2_000) {
                    forteDesde = null
                    try {
                        t.set(P.BLOQUEADA, 0)
                        semBloquearAte = agora + 20_000
                        Controlo._estado.update { it.copy(bloqueada = false, bloqueadaPorAfastamento = false) }
                        Log.i(TAG, "AUTO regresso (sinal=$media dBm) -> desbloqueada")
                        alerta("Bem-vindo de volta: desbloqueei a trotinete.")
                    } catch (ex: TrotineteException) {
                        Controlo._estado.update { it.copy(aviso = ex.message) }
                    }
                }
            } else {
                forteDesde = null
            }
        }
    }

    private suspend fun autoBloquear(t: Trotinete) {
        // Segurança: nunca bloquear em andamento. Confirma com leituras frescas (não as do ciclo, que
        // podem ter 2 s). Só bloqueia se "em andamento" vier EXATAMENTE 0; se vier outra coisa ou a
        // leitura falhar (null), aborta. A potência é defesa a dobrar: a andar gasta centenas de watts.
        if (ler(t, P.EM_ANDAMENTO).l() != 0L) return
        val potencia = num(t, P.POTENCIA)
        if (potencia != null && kotlin.math.abs(potencia) >= 150f) return
        try {
            t.set(P.BLOQUEADA, 1)
            Controlo._estado.update { it.copy(bloqueada = true, bloqueadaPorAfastamento = true) }
            Log.i(TAG, "AUTO bloqueada com sucesso")
            alerta("Afastaste-te e bloqueei a trotinete. 🔒")
        } catch (e: TrotineteException) {
            Log.w(TAG, "AUTO falhou o bloqueio: ${e.message}")
            alerta("Afastaste-te mas NÃO consegui bloquear a trotinete: ${e.message}")
        }
    }
}
