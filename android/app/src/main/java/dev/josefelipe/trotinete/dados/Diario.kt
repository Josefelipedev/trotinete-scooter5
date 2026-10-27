package dev.josefelipe.trotinete.dados

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

data class Ponto(val t: Long, val lat: Double, val lon: Double, val kmh: Float)

/** Uma viagem gravada pela app (GPS + energia medida na trotinete). */
data class ViagemGps(
    val inicio: Long,
    val fim: Long,
    val km: Double,
    /** Energia líquida gasta (Wh), integrando a potência que a trotinete reporta. */
    val wh: Double,
    val bateriaInicio: Int?,
    val bateriaFim: Int?,
    val velMedia: Double,
    val velMax: Double,
    val modo: Long?,
    val pontos: List<Ponto>,
) {
    val minutos get() = (fim - inicio) / 60_000.0
    val whPorKm get() = if (km >= 0.2) wh / km else null
}

/** Fotografia diária da bateria, para acompanhar a saúde ao longo dos meses. */
data class RegistoBateria(val dia: String, val saude: Int, val ciclos: Int, val energiaKwh: Double, val totalKm: Double)

/** Guarda viagens e registos da bateria em ficheiros JSON dentro da app (nada sai do telemóvel). */
class Diario(context: Context) {
    private val pastaViagens = File(context.filesDir, "viagens").apply { mkdirs() }
    private val ficheiroBateria = File(context.filesDir, "bateria.json")

    fun guardarViagem(v: ViagemGps) {
        val pontos = JSONArray()
        v.pontos.forEach { p -> pontos.put(JSONArray().put(p.t).put(p.lat).put(p.lon).put(p.kmh.toDouble())) }
        val j = JSONObject()
            .put("inicio", v.inicio).put("fim", v.fim).put("km", v.km).put("wh", v.wh)
            .put("bateriaInicio", v.bateriaInicio).put("bateriaFim", v.bateriaFim)
            .put("velMedia", v.velMedia).put("velMax", v.velMax).put("modo", v.modo)
            .put("pontos", pontos)
        File(pastaViagens, "${v.inicio}.json").writeText(j.toString())
    }

    fun viagens(): List<ViagemGps> = pastaViagens.listFiles { f -> f.extension == "json" }.orEmpty()
        .mapNotNull { f -> runCatching { ler(JSONObject(f.readText())) }.getOrNull() }
        .sortedByDescending { it.inicio }

    fun apagarViagem(inicio: Long) {
        File(pastaViagens, "$inicio.json").delete()
    }

    private fun ler(j: JSONObject): ViagemGps {
        val a = j.getJSONArray("pontos")
        val pontos = (0 until a.length()).map { i ->
            val p = a.getJSONArray(i)
            Ponto(p.getLong(0), p.getDouble(1), p.getDouble(2), p.getDouble(3).toFloat())
        }
        return ViagemGps(
            j.getLong("inicio"), j.getLong("fim"), j.getDouble("km"), j.getDouble("wh"),
            j.optInt("bateriaInicio", -1).takeIf { it >= 0 }, j.optInt("bateriaFim", -1).takeIf { it >= 0 },
            j.getDouble("velMedia"), j.getDouble("velMax"), j.optLong("modo", -1).takeIf { it >= 0 }, pontos,
        )
    }

    fun registosBateria(): List<RegistoBateria> = runCatching {
        val a = JSONArray(ficheiroBateria.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            RegistoBateria(o.getString("dia"), o.getInt("saude"), o.getInt("ciclos"), o.getDouble("energiaKwh"), o.getDouble("totalKm"))
        }
    }.getOrDefault(emptyList())

    /** Um registo por dia (o último do dia substitui o anterior). */
    fun registarBateria(saude: Int, ciclos: Int, energiaKwh: Double, totalKm: Double) {
        val hoje = LocalDate.now().toString()
        val lista = registosBateria().filter { it.dia != hoje } + RegistoBateria(hoje, saude, ciclos, energiaKwh, totalKm)
        val a = JSONArray()
        lista.sortedBy { it.dia }.forEach {
            a.put(JSONObject().put("dia", it.dia).put("saude", it.saude).put("ciclos", it.ciclos).put("energiaKwh", it.energiaKwh).put("totalKm", it.totalKm))
        }
        ficheiroBateria.writeText(a.toString())
    }
}
