package dev.josefelipe.trotinete.protocolo

/** Tipos MIoT: o código vai nos 4 bits altos do campo tipo/tamanho. */
object Tipo {
    const val BOOL = 0
    const val UINT8 = 1
    const val INT8 = 2
    const val UINT16 = 3
    const val INT16 = 4
    const val UINT32 = 5
    const val INT32 = 6
    const val UINT64 = 7
    const val INT64 = 8
    const val FLOAT = 9
    const val STRING = 10
}

/** [escala]: a Scooter 5 manda vários valores em centésimas (47,67 V chega como 4767). */
data class Prop(val siid: Int, val piid: Int, val tipo: Int, val escala: Float = 1f)

/**
 * Propriedades da Scooter 5 (siid, piid). A tabela foi levantada na 5 Pro/5 Max pelos projetos
 * de referência; a Scooter 5 tem o mesmo chip e o mesmo firmware Bluetooth (2.7.0_0015).
 *
 * Escalas confirmadas na Scooter 5 parada (27/out/2026): voltagem, corrente, potência, autonomia e
 * total vêm em centésimas (47,67 V x 1,19 A = 56,7 W bate certo). As velocidades assumem-se iguais
 * mas ainda não foram vistas em andamento.
 */
object P {
    val MODO = Prop(1, 1, Tipo.UINT8)
    val BATERIA = Prop(1, 2, Tipo.UINT8)
    val VOLTAGEM = Prop(1, 4, Tipo.FLOAT, 0.01f)
    val CORRENTE = Prop(1, 5, Tipo.FLOAT, 0.01f)
    val POTENCIA = Prop(1, 6, Tipo.FLOAT, 0.01f)
    val AUTONOMIA = Prop(1, 7, Tipo.FLOAT, 0.01f)
    val ERRO = Prop(1, 8, Tipo.UINT8)
    val VIAGEM = Prop(1, 9, Tipo.FLOAT, 0.01f)
    val VELOCIDADE_MEDIA = Prop(2, 1, Tipo.FLOAT, 0.01f)
    val BLOQUEADA = Prop(2, 2, Tipo.BOOL)
    val LUZ_TRASEIRA = Prop(2, 4, Tipo.BOOL)
    val TRAVAGEM_REGENERATIVA = Prop(2, 5, Tipo.UINT8)
    val TOTAL = Prop(2, 6, Tipo.FLOAT, 0.01f)
    val EM_ANDAMENTO = Prop(2, 7, Tipo.UINT8)
    val TEMPO_VIAGEM = Prop(2, 8, Tipo.FLOAT)
    val VELOCIDADE_MAXIMA = Prop(2, 9, Tipo.FLOAT, 0.01f)
    val TEMP_BATERIA = Prop(3, 2, Tipo.INT8)
    val TEMP_TROTINETE = Prop(3, 3, Tipo.INT8)
    val A_CARREGAR = Prop(3, 10, Tipo.BOOL)
    val CICLOS = Prop(3, 11, Tipo.UINT8)
    val SAUDE_BATERIA = Prop(3, 12, Tipo.UINT8)
    val FIRMWARE = Prop(4, 5, Tipo.STRING)

    // --- definições (aceitam SET na 5 Pro/5 Max; na Scooter 5 só aparecem as que responderem) ---
    val CRUISE = Prop(2, 3, Tipo.BOOL)
    val ASR = Prop(2, 10, Tipo.BOOL)
    val LUZ_AUTO = Prop(2, 12, Tipo.BOOL)
    val TCS = Prop(2, 13, Tipo.BOOL)
    val DESCIDA_ASSISTIDA = Prop(2, 14, Tipo.BOOL)
    val TRAVAO_RAMPA = Prop(2, 15, Tipo.BOOL)
    val LUZ_AMBIENTE = Prop(2, 16, Tipo.UINT8)
    val ALARME = Prop(3, 4, Tipo.BOOL)

    // --- informação ---
    val BATERIA_MAH = Prop(1, 3, Tipo.UINT16)
    val PNEUS = Prop(3, 7, Tipo.STRING)
    val DATA_ATIVACAO = Prop(3, 8, Tipo.STRING)
    val DATA_PRODUCAO = Prop(4, 1, Tipo.STRING)
    val SN_BATERIA = Prop(4, 2, Tipo.STRING)
    val FIRMWARE_BMS = Prop(4, 3, Tipo.STRING)
    val SN_TROTINETE = Prop(4, 4, Tipo.STRING)
    val MAIS_BATERIA = Prop(4, 7, Tipo.STRING)
    val MAIS_BATERIA_2 = Prop(4, 8, Tipo.STRING)

    /** Histórico de viagens guardado na trotinete: 5 blocos de registos. */
    val HISTORICO = (1..5).map { Prop(6, it, Tipo.STRING) }

    // (2,16) luz ambiente e (3,4) alarme foram removidos: testados na Scooter 5 (27/out/2026) e inertes
    // (a luz ambiente não tem peça; o alarme ignora a escrita).
    val DEFINICOES = listOf(LUZ_TRASEIRA, LUZ_AUTO, TRAVAGEM_REGENERATIVA, CRUISE, TCS, ASR, DESCIDA_ASSISTIDA, TRAVAO_RAMPA)
    val INFO = listOf(BATERIA_MAH, PNEUS, DATA_ATIVACAO, DATA_PRODUCAO, SN_BATERIA, FIRMWARE_BMS, SN_TROTINETE, MAIS_BATERIA, MAIS_BATERIA_2)

    /** Só escrita: faz a trotinete apitar e piscar. Um GET a esta propriedade falha sempre. */
    val ENCONTRAR = Prop(4, 10, Tipo.BOOL)

    /**
     * Nunca ler nem escrever: (3,6) é o código secreto de emparelhamento, (4,6) repõe as
     * definições de fábrica, (4,10) e (3,9) são ações (só escrita).
     */
    val PROIBIDAS = setOf(3 to 6, 4 to 6, 4 to 10, 3 to 9)

    /** Valores aceites para MODO: Walk, Drive, Sport (presets da Xiaomi, dentro do limite legal). */
    val MODOS = listOf(11L, 2L, 3L)
}

/**
 * Uma viagem do histórico da trotinete. [quartoCampo] seria a velocidade máxima na 5 Pro, mas na
 * Scooter 5 vem muitas vezes abaixo da média (27/out/2026), por isso não sabemos o que é e não se mostra.
 */
data class Viagem(val minutos: Double, val km: Double, val media: Double, val quartoCampo: Double)

/** Descodificadores dos campos em texto (formatos levantados pelos projetos de referência). */
object Formatos {
    /**
     * Cada registo tem 16 dígitos: [duração min x10][distância km x10][média km/h x10][? x10].
     * Confirmado na Scooter 5: a média bate com distância/tempo (1,8 km em 5 min -> 21,4 km/h).
     */
    fun viagens(blocos: List<String>): List<Viagem> = blocos.flatMap { bloco ->
        bloco.trim().chunked(16).filter { it.length == 16 && it.all(Char::isDigit) }.mapNotNull { r ->
            val v = r.chunked(4).map { it.toInt() / 10.0 }
            if (v.all { it == 0.0 }) null else Viagem(v[0], v[1], v[2], v[3])
        }
    }

    data class MaisBateria(val energiaKwh: Double, val descargasProfundas: Long)

    /**
     * Hex: [energia entregue Wh: 6][capacidade?: 4][descargas profundas: 4][...].
     * Na Scooter 5 (27/out/2026): 0x01117e = 70 014 Wh em 140 ciclos ≈ 500 Wh/ciclo, bate certo com a
     * bateria. O 2.º campo (0x05a6 = 1446) não bate com nenhuma capacidade plausível, por isso não se usa.
     */
    fun maisBateria(raw: String): MaisBateria? {
        val s = raw.trim().lowercase()
        if (s.length < 14 || !s.all { it in "0123456789abcdef" }) return null
        return MaisBateria(s.substring(0, 6).toLong(16) / 1000.0, s.substring(10, 14).toLong(16))
    }

    /** "20250815" -> 15/08/2025 e "250322" -> 22/03/2025 (ativação e produção). */
    fun data(raw: String): String {
        val s = raw.trim()
        return when {
            s.length == 8 && s.all(Char::isDigit) -> "${s.substring(6, 8)}/${s.substring(4, 6)}/${s.substring(0, 4)}"
            s.length == 6 && s.all(Char::isDigit) -> "${s.substring(4, 6)}/${s.substring(2, 4)}/20${s.substring(0, 2)}"
            else -> s
        }
    }

    /** Hex: [ano][mês][dia][hora][min][seg] do último evento de temperatura extrema + [tempo de carga s: 4]. */
    fun ultimaTemperaturaExtrema(raw: String): String? {
        val s = raw.trim().lowercase()
        if (s.length < 12 || !s.all { it in "0123456789abcdef" }) return null
        val p = s.substring(0, 12).chunked(2).map { it.toInt(16) }
        if (p[0] == 0 && p[1] == 0 && p[2] == 0) return "nunca"
        return "%02d/%02d/20%02d %02d:%02d".format(p[2], p[1], p[0], p[3], p[4])
    }

    data class Pneus(val ativo: Boolean, val intervaloDias: Int, val diasRestantes: Int)

    /** Decimal: [estado: 1 ('2' = desligado)][intervalo dias: 3][dias restantes: 3]. */
    fun pneus(raw: String): Pneus? {
        val s = raw.trim()
        if (s.length < 7 || !s.all(Char::isDigit)) return null
        return Pneus(s[0] != '2', s.substring(1, 4).toInt(), s.substring(4, 7).toInt())
    }
}

object Frames {
    private fun cabecalho(corpo: ByteArray, tid: Int, op: Int): ByteArray {
        val tamanho = (6 + corpo.size) or 0x2000
        return byteArrayOf(
            tamanho.toByte(), (tamanho ushr 8).toByte(),
            tid.toByte(), (tid ushr 8).toByte(),
            op.toByte(), 1,
        ) + corpo
    }

    fun get(p: Prop, tid: Int): ByteArray =
        cabecalho(byteArrayOf(p.siid.toByte(), p.piid.toByte(), (p.piid ushr 8).toByte(), 0, 0), tid, op = 2)

    fun set(p: Prop, valor: ByteArray, tid: Int): ByteArray {
        val tl = (p.tipo shl 12) or valor.size
        val corpo = byteArrayOf(p.siid.toByte(), p.piid.toByte(), (p.piid ushr 8).toByte(), tl.toByte(), (tl ushr 8).toByte())
        return cabecalho(corpo + valor, tid, op = 0)
    }

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    /** Estado da resposta: 0 = OK. -1 se a resposta vier incompleta. */
    fun estado(resposta: ByteArray): Int = if (resposta.size < 11) -1 else u16(resposta, 9)

    /** Valor de uma resposta a GET, já descodificado pelo tipo que a trotinete indica. */
    fun valor(resposta: ByteArray): Any? {
        if (estado(resposta) != 0 || resposta.size < 13) return null
        val tl = u16(resposta, 11)
        val n = tl and 0x0FFF
        if (resposta.size < 13 + n) return null
        return descodificar(tl ushr 12, resposta.copyOfRange(13, 13 + n))
    }

    fun descodificar(tipo: Int, raw: ByteArray): Any? {
        if (raw.isEmpty()) return null
        if (tipo == Tipo.STRING) return String(raw, Charsets.UTF_8)
        var v = 0L
        for (i in raw.indices.reversed()) v = (v shl 8) or (raw[i].toLong() and 0xFF)
        return when (tipo) {
            Tipo.FLOAT -> java.lang.Float.intBitsToFloat(v.toInt())
            Tipo.BOOL -> v != 0L
            Tipo.INT8, Tipo.INT16, Tipo.INT32, Tipo.INT64 -> {
                val bits = raw.size * 8
                if (bits < 64 && v and (1L shl (bits - 1)) != 0L) v - (1L shl bits) else v
            }
            else -> v
        }
    }

    fun codificar(tipo: Int, valor: Long): ByteArray {
        val n = when (tipo) {
            Tipo.BOOL, Tipo.UINT8, Tipo.INT8 -> 1
            Tipo.UINT16, Tipo.INT16 -> 2
            Tipo.UINT32, Tipo.INT32 -> 4
            else -> 8
        }
        return ByteArray(n) { (valor ushr (8 * it)).toByte() }
    }
}
