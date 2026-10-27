package dev.josefelipe.trotinete.protocolo

import dev.josefelipe.trotinete.ble.LigacaoBle
import dev.josefelipe.trotinete.ble.Notificacao
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class TrotineteException(msg: String) : Exception(msg)

/**
 * Protocolo da Scooter 5 por cima de [LigacaoBle]; é o mesmo de pc/trotinete/protocolo.py,
 * que tem o resumo do formato dos pacotes.
 */
class Trotinete(private val ble: LigacaoBle) {

    private companion object {
        const val CONTROLO = 0x0010
        const val LOGIN = 0x0016
        const val SPEC_ENVIO = 0x001A
        const val SPEC_RESPOSTA = 0x001B
        const val INFO_MCU = 0x001C

        const val CTR = 0
        const val ACK = 1
        const val SINGLE = 2
        const val SINGLE_ACK = 3
        const val MNG = 4
        const val MNG_ACK = 5

        const val FRAME_SPEC = 18
        val SAL = "smartcfg-login-salt".toByteArray()
        val INFO = "smartcfg-login-info".toByteArray()
        val NONCE_LOGIN = ByteArray(12) { (0x10 + it).toByte() }
    }

    private var dmtu = 242
    private lateinit var chaveApp: ByteArray
    private lateinit var ivApp: ByteArray
    private lateinit var chaveDev: ByteArray
    private lateinit var ivDev: ByteArray
    private var contador = 0
    private var tid = 1
    private val pedidos = Mutex()

    suspend fun ligar() {
        ble.partilharFila(SPEC_ENVIO, SPEC_RESPOSTA)
        ble.ligar(listOf(CONTROLO, LOGIN, SPEC_ENVIO, SPEC_RESPOSTA, INFO_MCU))
    }

    // --- transporte ---

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v ushr 8).toByte())
    private fun eAck(b: ByteArray) = b.size >= 4 && b[0].toInt() == 0 && b[1].toInt() == 0 && b[2].toInt() == ACK
    private fun eControlo(b: ByteArray, tipo: Int) = b.size >= 3 && b[0].toInt() == 0 && b[1].toInt() == 0 && b[2].toInt() == tipo

    private fun Channel<Notificacao>.esvaziar() {
        while (tryReceive().isSuccess) Unit
    }

    private suspend fun Channel<Notificacao>.proxima(prazo: Long): Notificacao? {
        val falta = prazo - System.currentTimeMillis()
        return if (falta <= 0) null else withTimeoutOrNull(falta) { receive() }
    }

    private fun partir(dados: ByteArray, tamanho: Int): List<ByteArray> =
        if (dados.isEmpty()) listOf(ByteArray(0)) else dados.toList().chunked(tamanho).map { it.toByteArray() }

    private fun seqsPedidas(b: ByteArray): List<Int> = (4 until b.size - 1 step 2).map { u16(b, it) }

    /** Envia uma mensagem: CTR -> ACK 01 -> frames -> reenvia o que pedir (05) até ACK 00. */
    private suspend fun enviarMensagem(id: Int, canal: Int, dados: ByteArray, tamanhoFrame: Int): Boolean {
        val frames = partir(dados, tamanhoFrame)
        val fila = ble.fila(id)
        suspend fun enviar(seqs: Iterable<Int>) {
            for (n in seqs) if (n in 1..frames.size) {
                ble.escrever(id, le16(n) + frames[n - 1])
                delay(30)
            }
        }
        ble.escrever(id, byteArrayOf(0, 0, CTR.toByte(), canal.toByte()) + le16(frames.size))
        val prazo = System.currentTimeMillis() + 8_000
        var enviado = false
        while (true) {
            val b = fila.proxima(prazo)?.dados ?: return false
            if (!eAck(b)) continue
            when (b[3].toInt()) {
                0x01 -> if (!enviado) {
                    enviado = true
                    enviar(1..frames.size)
                }
                0x05 -> enviar(seqsPedidas(b))
                0x00 -> return true
            }
        }
    }

    /** Recebe uma mensagem iniciada pela trotinete (SINGLE ou CTR + frames). */
    private suspend fun receberMensagem(id: Int): ByteArray? {
        val fila = ble.fila(id)
        val partes = sortedMapOf<Int, ByteArray>()
        var total: Int? = null
        val prazo = System.currentTimeMillis() + 8_000
        while (true) {
            val b = fila.proxima(prazo)?.dados ?: break
            if (b.size < 3) continue
            val seq = u16(b, 0)
            if (seq == 0 && b[2].toInt() == SINGLE) {
                ble.escrever(id, byteArrayOf(0, 0, SINGLE_ACK.toByte(), 0))
                return b.copyOfRange(4, b.size)
            }
            if (seq == 0 && b[2].toInt() == CTR) {
                total = if (b.size >= 6) u16(b, 4) else b[4].toInt() and 0xFF
                ble.escrever(id, byteArrayOf(0, 0, ACK.toByte(), 1))
            } else if (seq != 0) {
                partes[seq] = b.copyOfRange(2, b.size)
                if (total != null && partes.size >= total) {
                    ble.escrever(id, byteArrayOf(0, 0, ACK.toByte(), 0))
                    break
                }
            }
        }
        return if (partes.isEmpty()) null else partes.values.reduce { a, c -> a + c }
    }

    // --- login ---

    suspend fun login(ltmk: ByteArray) {
        val filaLogin = ble.fila(LOGIN)
        val filaControlo = ble.fila(CONTROLO)
        filaLogin.esvaziar()

        ble.escrever(CONTROLO, byteArrayOf(0xA4.toByte()))
        val mng = filaLogin.proxima(System.currentTimeMillis() + 3_000)?.dados
        if (mng == null || mng.size < 6 || !eControlo(mng, MNG)) {
            throw TrotineteException("A trotinete não respondeu ao handshake")
        }
        dmtu = mng[5].toInt() and 0xFF
        ble.escrever(LOGIN, byteArrayOf(0, 0, MNG_ACK.toByte(), mng[3], mng[4], mng[5]))
        delay(1_000) // a trotinete precisa deste tempo antes do passo seguinte
        filaLogin.esvaziar()

        val par = Cripto.novoParDeChaves()
        ble.escrever(CONTROLO, byteArrayOf(0x20, 0, 0))
        delay(200)
        if (!enviarMensagem(LOGIN, 3, Cripto.publicaEmBruto(par), dmtu - 2)) {
            throw TrotineteException("A trotinete não aceitou a nossa chave pública")
        }
        val pubDev = receberMensagem(LOGIN)?.takeIf { it.size >= 64 }?.copyOf(64)
            ?: throw TrotineteException("Não recebi a chave pública da trotinete")

        val sk = Cripto.hkdf(Cripto.ecdh(par.private, pubDev) + ltmk, SAL, INFO, 64)
        chaveDev = sk.copyOfRange(0, 16)
        chaveApp = sk.copyOfRange(16, 32)
        ivDev = sk.copyOfRange(32, 36)
        ivApp = sk.copyOfRange(36, 40)

        val crc = Cripto.crc32(pubDev)
        val confirmacao = Cripto.ccmCifrar(chaveApp, NONCE_LOGIN, byteArrayOf(crc.toByte(), (crc ushr 8).toByte(), (crc ushr 16).toByte(), (crc ushr 24).toByte()))
        filaControlo.esvaziar()
        if (!enviarMensagem(LOGIN, 5, confirmacao, dmtu - 2)) {
            throw TrotineteException("A trotinete não aceitou a confirmação de login")
        }
        val resultado = filaControlo.proxima(System.currentTimeMillis() + 5_000)?.dados
            ?: throw TrotineteException("Sem resposta ao login")
        if (resultado.isEmpty() || resultado[0] != 0x21.toByte()) {
            throw TrotineteException("Login recusado: a chave não é desta trotinete (PIN errado?)")
        }
        contador = 0
    }

    // --- propriedades ---

    private fun nonce(iv: ByteArray, c: Int) = iv + ByteArray(4) + byteArrayOf(c.toByte(), (c ushr 8).toByte(), (c ushr 16).toByte(), (c ushr 24).toByte())

    private suspend fun pedidoUmaVez(frame: ByteArray): ByteArray? {
        val c = contador++
        val cifrado = le16(c and 0xFFFF) + Cripto.ccmCifrar(chaveApp, nonce(ivApp, c), frame)
        val frames = partir(cifrado, FRAME_SPEC)
        val fila = ble.fila(SPEC_ENVIO) // partilhada com SPEC_RESPOSTA
        fila.esvaziar()
        ble.escrever(SPEC_ENVIO, byteArrayOf(0, 0, CTR.toByte(), 0) + le16(frames.size))

        val prazo = System.currentTimeMillis() + 4_000
        var enviado = false
        var total: Int? = null
        val partes = sortedMapOf<Int, ByteArray>()
        while (true) {
            val n = fila.proxima(prazo) ?: return null
            val b = n.dados
            if (n.id == SPEC_ENVIO && eAck(b)) {
                val seqs = when {
                    b[3].toInt() == 0x01 && !enviado -> { enviado = true; (1..frames.size).toList() }
                    b[3].toInt() == 0x05 -> seqsPedidas(b)
                    else -> emptyList()
                }
                for (s in seqs) if (s in 1..frames.size) {
                    ble.escrever(SPEC_ENVIO, le16(s) + frames[s - 1])
                    delay(30)
                }
            } else if (n.id == SPEC_RESPOSTA && eControlo(b, CTR)) {
                total = if (b.size >= 6) u16(b, 4) else b[4].toInt() and 0xFF
                ble.escrever(SPEC_RESPOSTA, byteArrayOf(0, 0, ACK.toByte(), 1))
            } else if (n.id == SPEC_RESPOSTA && b.size >= 2 && u16(b, 0) != 0) {
                partes[u16(b, 0)] = b.copyOfRange(2, b.size)
                if (total != null && partes.size >= total) {
                    ble.escrever(SPEC_RESPOSTA, byteArrayOf(0, 0, ACK.toByte(), 0))
                    val resposta = partes.values.reduce { a, x -> a + x }
                    if (resposta.size < 2) return null
                    return Cripto.ccmDecifrar(chaveDev, nonce(ivDev, u16(resposta, 0)), resposta.copyOfRange(2, resposta.size))
                }
            }
        }
    }

    /** Um pedido de cada vez; a trotinete às vezes fica calada num pedido e uma 2.ª tentativa resolve. */
    private suspend fun pedido(fazerFrame: (Int) -> ByteArray): ByteArray? = pedidos.withLock {
        repeat(2) {
            val t = tid
            tid = if (tid >= 0xFFFF) 1 else tid + 1
            pedidoUmaVez(fazerFrame(t))?.let { return@withLock it }
        }
        null
    }

    suspend fun get(p: Prop): Any? {
        val r = pedido { Frames.get(p, it) } ?: throw TrotineteException("Sem resposta (${p.siid},${p.piid})")
        val estado = Frames.estado(r)
        if (estado != 0) throw TrotineteException("(${p.siid},${p.piid}) recusado: 0x%04x".format(estado))
        return Frames.valor(r)
    }

    suspend fun set(p: Prop, valor: Long) {
        val r = pedido { Frames.set(p, Frames.codificar(p.tipo, valor), it) }
            ?: throw TrotineteException("Sem resposta ao alterar (${p.siid},${p.piid})")
        val estado = Frames.estado(r)
        if (estado != 0) throw TrotineteException("Alteração recusada pela trotinete: 0x%04x".format(estado))
    }

    suspend fun rssi(): Int? = ble.rssi()

    val ativa get() = ble.ativa

    /**
     * Pedido simples ao canal 0x001c (não precisa de login): [opcode] -> [?][tamanho][dados].
     * 1 = versão do controlador, 3 = hardware.
     */
    suspend fun infoMcu(opcode: Int): String? {
        val fila = ble.fila(INFO_MCU)
        fila.esvaziar()
        ble.escrever(INFO_MCU, byteArrayOf(opcode.toByte()))
        val r = fila.proxima(System.currentTimeMillis() + 2_000)?.dados ?: return null
        if (r.size < 2 || r[0] == 0xFF.toByte()) return null
        val n = minOf(r[1].toInt() and 0xFF, r.size - 2)
        val v = r.copyOfRange(2, 2 + n)
        return if (v.all { it in 32..126 }) String(v, Charsets.US_ASCII) else v.joinToString("") { "%02x".format(it) }
    }

    fun fechar() = ble.fechar()
}
