package dev.josefelipe.trotinete.servico

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import dev.josefelipe.trotinete.dados.Diario
import dev.josefelipe.trotinete.dados.Ponto
import dev.josefelipe.trotinete.dados.ViagemGps
import kotlinx.coroutines.flow.update

/** Viagem em curso, para o ecrã. */
data class Gravacao(val inicio: Long, val km: Double, val wh: Double, val kmh: Float?, val manual: Boolean)

/**
 * Grava viagens: começa quando a trotinete anda (ou à mão), junta GPS e a energia que a trotinete
 * reporta, e termina ao fim de 90 s parada, ao bloquear ou ao perder a ligação.
 */
class Gravador(private val context: Context, private val diario: Diario) {

    private companion object {
        const val TAG = "Trotinete"
        const val PARADA_PARA_TERMINAR_MS = 90_000L
        const val MINIMO_PARA_GUARDAR_KM = 0.1
    }

    private val lm = context.getSystemService(LocationManager::class.java)
    private var inicio = 0L
    private var manual = false
    private var pontos = mutableListOf<Ponto>()
    private var km = 0.0
    private var wh = 0.0
    private var ultimaAmostra = 0L
    private var paradaDesde: Long? = null
    private var bateriaInicio: Int? = null
    private var viagemTrotineteInicio: Float? = null
    private var ultimaLocalizacao: Location? = null
    private var kmhAgora: Float? = null
    private var modo: Long? = null

    val aGravar get() = inicio != 0L

    private val ouvinte = LocationListener { l -> novaLocalizacao(l) }

    private fun temGps() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** A trotinete a andar: diz que está em andamento ou gasta bem mais do que parada (~57 W). */
    private fun andar(e: Estado) = e.bloqueada == false && e.aCarregar != true &&
        ((e.emAndamento != null && e.emAndamento != 0L) || (e.potencia ?: 0f) > 150f)

    /** Chamado a cada leitura dos dados da trotinete. */
    fun amostra(e: Estado, ativo: Boolean) {
        val agora = System.currentTimeMillis()
        if (!aGravar) {
            if (ativo && andar(e)) iniciar(e, manual = false)
            return
        }
        // energia: integra a potência entre leituras (no máximo 10 s, para não inventar em falhas)
        e.potencia?.let { p -> wh += p * (minOf(agora - ultimaAmostra, 10_000L) / 3_600_000.0) }
        ultimaAmostra = agora
        modo = e.modo ?: modo

        when {
            e.bloqueada == true -> terminar(e)
            !manual && !andar(e) && (kmhAgora ?: 0f) < 4f -> {
                if (paradaDesde == null) paradaDesde = agora
                if (agora - paradaDesde!! > PARADA_PARA_TERMINAR_MS) terminar(e)
            }
            else -> paradaDesde = null
        }
        publicar()
    }

    @SuppressLint("MissingPermission")
    fun iniciar(e: Estado, manual: Boolean) {
        if (aGravar) return
        inicio = System.currentTimeMillis()
        this.manual = manual
        pontos = mutableListOf()
        km = 0.0
        wh = 0.0
        ultimaAmostra = inicio
        paradaDesde = null
        bateriaInicio = e.bateria?.toInt()
        viagemTrotineteInicio = e.viagem
        ultimaLocalizacao = null
        kmhAgora = null
        modo = e.modo
        if (temGps()) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 0f, ouvinte, Looper.getMainLooper())
        Log.i(TAG, "Viagem iniciada (${if (manual) "à mão" else "automática"}), GPS=${temGps()}")
        publicar()
    }

    private fun novaLocalizacao(l: Location) {
        if (!aGravar || !l.hasAccuracy() || l.accuracy > 25f) return
        val kmh = if (l.hasSpeed()) l.speed * 3.6f else 0f
        kmhAgora = kmh
        ultimaLocalizacao?.let { a ->
            val d = a.distanceTo(l)
            val s = (l.time - a.time) / 1000.0
            // ignora saltos impossíveis (> 60 km/h) e tremeliques parados (< 3 m)
            if (s > 0 && d / s < 16.7 && d >= 3f) km += d / 1000.0 else if (d < 3f) return
        }
        ultimaLocalizacao = l
        pontos += Ponto(l.time, l.latitude, l.longitude, kmh)
        publicar()
    }

    fun terminar(e: Estado?) {
        if (!aGravar) return
        lm.removeUpdates(ouvinte)
        val fim = System.currentTimeMillis()
        // sem GPS (ou com pouco sinal) usa a distância que a própria trotinete contou
        val kmTrotinete = e?.viagem?.let { f -> viagemTrotineteInicio?.let { i -> (f - i).toDouble() } }?.takeIf { it > 0 }
        val distancia = if (km >= MINIMO_PARA_GUARDAR_KM) km else kmTrotinete ?: km
        val horas = (fim - inicio) / 3_600_000.0
        if (distancia >= MINIMO_PARA_GUARDAR_KM) {
            diario.guardarViagem(
                ViagemGps(
                    inicio, fim, distancia, wh, bateriaInicio, e?.bateria?.toInt(),
                    velMedia = if (horas > 0) distancia / horas else 0.0,
                    velMax = pontos.maxOfOrNull { it.kmh.toDouble() } ?: 0.0,
                    modo = modo, pontos = pontos,
                ),
            )
            Log.i(TAG, "Viagem guardada: %.2f km, %.1f Wh".format(distancia, wh))
        } else {
            Log.i(TAG, "Viagem curta demais, não guardada (%.2f km)".format(distancia))
        }
        inicio = 0L
        Controlo._estado.update { it.copy(gravacao = null, viagensGuardadas = it.viagensGuardadas + 1) }
    }

    private fun publicar() {
        Controlo._estado.update { it.copy(gravacao = Gravacao(inicio, km, wh, kmhAgora, manual)) }
    }
}
