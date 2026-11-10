package dev.josefelipe.trotinete.servico

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.josefelipe.trotinete.R
import dev.josefelipe.trotinete.ble.DisconnectReason
import dev.josefelipe.trotinete.ble.LigacaoBle
import dev.josefelipe.trotinete.ble.OutOfRangeException
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

/** The scooter ended the connection itself: it was switched off with the button, not lost out of range. */
private class SwitchedOffException(msg: String) : TrotineteException(msg)

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
        /** A "moving" reading younger than this is trusted by the auto-lock without re-reading. */
        private const val FRESH_READ_MS = 4_000L
        /** A failed "moving"/"locked" read keeps the previous value for up to this long. */
        private const val STALE_READ_MS = 6_000L

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
    private var ultimoAvisoVelocidade = 0L
    private var mudouParaEconomico = false
    private var chaveRecusada = 0
    private var ciclo: Job? = null

    /** Steps from the phone's step detector; walking while the scooter is parked means you are leaving it. */
    private val steps = StepWindow()
    private val stepListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = steps.step(System.currentTimeMillis())
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun listenForSteps() {
        val sensors = getSystemService(SensorManager::class.java)
        val detector = sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: return
        val allowed = ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        sensors.unregisterListener(stepListener)
        if (allowed) sensors.registerListener(stepListener, detector, SensorManager.SENSOR_DELAY_NORMAL)
    }

    /** Screen on and keyguard dismissed: the owner is holding the phone, not just carrying it. */
    private fun phoneUnlocked(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive && !getSystemService(KeyguardManager::class.java).isDeviceLocked

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
        listenForSteps() // again on every start: the permission may have been granted since the last one
        if (ciclo?.isActive != true) ciclo = lifecycleScope.launch { cicloDeLigacao() }
        return START_STICKY
    }

    override fun onDestroy() {
        getSystemService(SensorManager::class.java).unregisterListener(stepListener)
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

    /**
     * A direct connection is fast when the scooter is nearby but gives up after 20 s. Once it fails we
     * switch to a passive one: the Bluetooth stack waits for the scooter at almost no battery cost and
     * connects the moment it appears. So there is no retry loop to tune and no radio time burnt while
     * the scooter sits at home and you are at work; and when you walk back to it, the link comes up
     * within seconds instead of after a 30 s pause.
     */
    private suspend fun cicloDeLigacao() {
        var passive = false
        while (true) {
            val mac = defs.mac
            val ltmk = defs.ltmk
            if (mac == null || ltmk == null) {
                Controlo._estado.update { it.copy(ligacao = Ligacao.SEM_LIGACAO, aviso = "Falta configurar a trotinete") }
                stopSelf()
                return
            }
            val adaptador = getSystemService(BluetoothManager::class.java).adapter
            if (adaptador?.isEnabled != true) {
                Controlo._estado.update { it.copy(ligacao = Ligacao.SEM_LIGACAO, aviso = getString(R.string.connection_bluetooth_off), rssi = null) }
                atualizarNotificacao(getString(R.string.connection_bluetooth_off))
                delay(5_000)
                continue
            }
            if (passive) {
                Controlo._estado.update { it.copy(ligacao = Ligacao.OUT_OF_RANGE, aviso = null) }
                atualizarNotificacao(getString(R.string.connection_out_of_range))
            } else {
                Controlo._estado.update { it.copy(ligacao = Ligacao.A_LIGAR, aviso = null) }
                atualizarNotificacao("A ligar…")
            }
            val t = Trotinete(LigacaoBle(this, adaptador.getRemoteDevice(mac)))
            var ligou = false
            var switchedOff = false
            try {
                t.ligar(passive)
                ligou = true
                passive = false
                t.login(ltmk)
                chaveRecusada = 0
                Controlo._estado.update { it.copy(ligacao = Ligacao.LIGADA, aviso = null) }
                sessao(t)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfRangeException) {
                // the passive wait is simply re-armed below; nothing happened that the user needs to know
            } catch (e: Exception) {
                android.util.Log.w("Trotinete", "Ligação terminou", e)
                // Chave recusada várias vezes seguidas = PIN errado: insistir só faz a trotinete bloquear
                // as ligações durante uns minutos. Para e espera que o PIN seja corrigido nos Ajustes.
                if ("PIN errado" in (e.message ?: "") && ++chaveRecusada >= 3) {
                    Controlo._estado.update {
                        it.copy(ligacao = Ligacao.PARADA, rssi = null, aviso = "A trotinete recusou a chave: o PIN da trotinete deve estar errado.")
                    }
                    stopSelf()
                    return
                }
                switchedOff = e is SwitchedOffException
                val estava = Controlo._estado.value
                Controlo._estado.update { it.copy(ligacao = Ligacao.SEM_LIGACAO, aviso = e.message, rssi = null) }
                atualizarNotificacao("Sem ligação · a tentar de novo")
                if (estava.ligacao == Ligacao.LIGADA && estava.bloqueada == false && defs.autoBloqueio) {
                    // Switched off with the button is most likely the owner; a lost link is the worrying one.
                    if (switchedOff) alerta(getString(R.string.alert_switched_off_unlocked))
                    else alerta("Perdi a ligação e a trotinete ficou DESBLOQUEADA. Se não a desligaste, volta para perto dela.")
                }
            } finally {
                gravador.terminar(Controlo.estado.value)
                t.fechar()
            }
            // Reached it and lost the link (or only the login failed): it is probably still around, so
            // try a direct connection right away. Switched off, or never reached: let the stack wait.
            if (ligou && !switchedOff) {
                delay(1_500)
            } else {
                passive = true
                delay(1_000)
            }
        }
    }

    /** Corre enquanto a ligação estiver de pé; qualquer falha Bluetooth sai daqui e o ciclo volta a ligar. */
    private suspend fun sessao(t: Trotinete) = coroutineScope {
        while (Controlo.comandos.tryReceive().isSuccess) Unit // comandos de quando não havia ligação já não valem
        launch {
            t.ativa.first { !it }
            // A clean termination from the scooter means it was switched off; a timeout means we lost it.
            throw if (t.disconnectReason == DisconnectReason.SWITCHED_OFF) {
                SwitchedOffException(getString(R.string.connection_switched_off))
            } else {
                TrotineteException(getString(R.string.connection_link_lost))
            }
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
            verificarVelocidade(Controlo.estado.value)
            modoEconomico(t, Controlo.estado.value)
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
        val now = System.currentTimeMillis()
        // Seeing the scooter unlocked (by hand, or by anyone) ends "locked by the app": from here on a
        // lock is somebody's decision and the app must not undo it when you come back.
        if (bloqueada == false && defs.lockedByProximity) defs.lockedByProximity = false
        val lockedByUs = defs.lockedByProximity
        Controlo._estado.update {
            // One failed read of "moving" or "locked" must not disarm the auto-lock for a whole cycle:
            // keep the last good value for a few seconds instead of dropping to null. The auto-lock's
            // own safety check still demands a fresh reading before sending the order.
            val movement = emAndamento ?: it.emAndamento.takeIf { _ -> now - it.movementReadAt < STALE_READ_MS }
            val lock = bloqueada ?: it.bloqueada.takeIf { _ -> now - it.lockReadAt < STALE_READ_MS }
            it.copy(
                bateria = bateria, voltagem = voltagem, corrente = corrente, potencia = potencia,
                autonomia = autonomia, viagem = viagem, bloqueada = lock, emAndamento = movement,
                modo = modo, aCarregar = aCarregar,
                movementReadAt = if (emAndamento != null) now else it.movementReadAt,
                lockReadAt = if (bloqueada != null) now else it.lockReadAt,
                bloqueadaPorAfastamento = lockedByUs && lock == true,
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

    /** Vibra e apita se a velocidade GPS passar do limite escolhido (no máximo de 15 em 15 s). */
    private fun verificarVelocidade(e: Estado) {
        val limite = defs.avisoVelocidade
        val kmh = e.gravacao?.kmh ?: return
        if (limite <= 0 || kmh < limite + 0.5f) return
        val agora = System.currentTimeMillis()
        if (agora - ultimoAvisoVelocidade < 15_000) return
        ultimoAvisoVelocidade = agora
        Log.i(TAG, "Aviso de velocidade: %.0f km/h (limite %d)".format(kmh, limite))
        val vibrador = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        vibrador.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
        runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90).apply {
                startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
                Handler(Looper.getMainLooper()).postDelayed({ release() }, 600)
            }
        }
    }

    /**
     * Bateria fraca em Sport: passa para Drive para chegar mais longe. Só com a trotinete PARADA,
     * para a velocidade máxima nunca baixar de repente a meio do caminho.
     */
    private suspend fun modoEconomico(t: Trotinete, e: Estado) {
        val limiar = defs.modoEconomico
        val b = e.bateria?.toInt() ?: return
        if (b >= limiar + 10) mudouParaEconomico = false
        if (limiar <= 0 || b > limiar || e.modo != 3L || !e.parada || e.aCarregar == true || mudouParaEconomico) return
        mudouParaEconomico = true
        t.set(P.MODO, 2)
        Controlo._estado.update { it.copy(modo = ler(t, P.MODO).l()) }
        Log.i(TAG, "Modo económico: bateria a $b%, Sport -> Drive")
        alerta("Bateria a $b%: passei de Sport para Drive para chegares mais longe.")
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
                    defs.lockedByProximity = false // a lock or unlock by hand is never undone by the app
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
     * Watches the signal to lock BEFORE the link drops (afterwards there is no way to send the order)
     * and to unlock when the owner comes back. One RSSI reading every 0.8 s goes into
     * [ProximityWatcher], which holds all the rules; this method only gathers the inputs and carries
     * out the decisions. A fresh watcher per connection: a new connection means the owner was away.
     */
    private suspend fun vigiarSinal(t: Trotinete) {
        val watcher = ProximityWatcher {
            ProximityWatcher.Settings(
                autoLock = defs.autoBloqueio,
                autoUnlock = defs.autoDesbloqueio,
                manualThresholdDbm = defs.limiarDbm,
                adaptiveThreshold = defs.adaptiveThreshold,
                learnedBaselineDbm = defs.learnedBaselineDbm,
                weakSeconds = defs.segundos,
                unlockRequiresPhoneUnlocked = defs.unlockRequiresPhoneUnlocked,
            )
        }
        while (true) {
            delay(800)
            val e = Controlo.estado.value
            val now = System.currentTimeMillis()
            val sample = ProximityWatcher.Sample(
                now = now,
                rssi = t.rssi(),
                locked = e.bloqueada,
                lockedByUs = e.bloqueadaPorAfastamento,
                stopped = e.parada,
                riding = (e.emAndamento ?: 0L) != 0L || kotlin.math.abs(e.potencia ?: 0f) >= 150f,
                charging = e.aCarregar == true,
                recentSteps = steps.countSince(now, ProximityWatcher.STEP_WINDOW_MS),
                phoneUnlocked = phoneUnlocked(),
            )
            val decision = watcher.onSample(sample)
            Controlo._estado.update { it.copy(rssi = watcher.smoothedRssi ?: it.rssi, lockThresholdDbm = watcher.lockThresholdDbm) }
            watcher.learnedBaseline?.let { if (it != defs.learnedBaselineDbm) defs.learnedBaselineDbm = it }

            when (decision) {
                is ProximityWatcher.Decision.Lock -> {
                    Log.i(TAG, "AUTO away (${decision.reason}, rssi=${watcher.smoothedRssi ?: "?"} dBm, threshold=${watcher.lockThresholdDbm}) -> locking")
                    autoBloquear(t, quiet = decision.quiet)
                }
                is ProximityWatcher.Decision.Unlock -> try {
                    t.set(P.BLOQUEADA, 0)
                    defs.lockedByProximity = false
                    Controlo._estado.update { it.copy(bloqueada = false, bloqueadaPorAfastamento = false) }
                    Log.i(TAG, "AUTO back (${decision.reason}, rssi=${watcher.smoothedRssi} dBm) -> unlocked")
                    alerta("Bem-vindo de volta: desbloqueei a trotinete.")
                } catch (ex: TrotineteException) {
                    Controlo._estado.update { it.copy(aviso = ex.message) }
                }
                ProximityWatcher.Decision.None -> Unit
            }
        }
    }

    /** [quiet]: lock without a notification (charging at home: locking is right, being told every time is noise). */
    private suspend fun autoBloquear(t: Trotinete, quiet: Boolean) {
        // Safety: never lock a moving scooter. Only an explicit "not moving" lets the order go out.
        // The cycle's own readings are at most a few seconds old; when they are that fresh we trust
        // them and send the lock at once. This matters because the auto-lock fires exactly when the
        // link is dying, and the two extra reads done here before (up to 8 s each) were often what
        // let the connection drop before the order left. Only with stale readings do we take one
        // quick fresh one (single attempt, 1.5 s); no answer, or anything other than 0, aborts.
        val e = Controlo.estado.value
        val fresh = e.emAndamento != null && System.currentTimeMillis() - e.movementReadAt < FRESH_READ_MS
        val moving = if (fresh) {
            e.emAndamento != 0L || (e.potencia != null && kotlin.math.abs(e.potencia) >= 150f)
        } else {
            try { t.get(P.EM_ANDAMENTO, quick = true).l() != 0L } catch (ex: TrotineteException) { true }
        }
        if (moving) {
            Log.i(TAG, "AUTO lock skipped: scooter not confirmed stopped (fresh=$fresh)")
            return
        }
        try {
            t.set(P.BLOQUEADA, 1)
            defs.lockedByProximity = true
            Controlo._estado.update { it.copy(bloqueada = true, bloqueadaPorAfastamento = true) }
            Log.i(TAG, "AUTO bloqueada com sucesso")
            if (!quiet) alerta("Afastaste-te e bloqueei a trotinete. 🔒")
        } catch (e: TrotineteException) {
            Log.w(TAG, "AUTO falhou o bloqueio: ${e.message}")
            alerta("Afastaste-te mas NÃO consegui bloquear a trotinete: ${e.message}")
        }
    }
}
