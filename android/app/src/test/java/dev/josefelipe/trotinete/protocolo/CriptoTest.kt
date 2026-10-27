package dev.josefelipe.trotinete.protocolo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Os valores esperados foram gerados com a biblioteca `cryptography` do Python (pc/). */
class CriptoTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun range(a: Int, b: Int) = ByteArray(b - a) { (a + it).toByte() }

    private val sk = Cripto.hkdf(
        range(0, 32) + range(100, 132),
        "smartcfg-login-salt".toByteArray(),
        "smartcfg-login-info".toByteArray(),
        64,
    )
    private val appKey = sk.copyOfRange(16, 32)
    private val nonce7 = sk.copyOfRange(36, 40) + ByteArray(4) + byteArrayOf(7, 0, 0, 0)

    @Test
    fun hkdf() {
        assertArrayEquals(
            hex("058097b9be2b7638a498c39edf2bcaa0beee0be6a25c3237a0f674c6dad413ad640c4da15f27c58c2bc5a72fd9ee50daec9efd05fef7aad34d86c0ed2b70f2ad"),
            sk,
        )
    }

    @Test
    fun confirmacaoDeLogin() {
        val crc = Cripto.crc32(range(50, 114))
        val texto = byteArrayOf(crc.toByte(), (crc ushr 8).toByte(), (crc ushr 16).toByte(), (crc ushr 24).toByte())
        assertArrayEquals(hex("6d196e27ab8a01cb"), Cripto.ccmCifrar(appKey, range(0x10, 0x1c), texto))
    }

    @Test
    fun ccmCurtoELongo() {
        assertArrayEquals(hex("f7657dafb93c142d77058671d3f7"), Cripto.ccmCifrar(appKey, nonce7, hex("0b200100020101020000")))
        val longo = Cripto.ccmCifrar(appKey, nonce7, range(0, 37))
        assertArrayEquals(hex("fc447eacbf3813287f0ce934125e18b1ff3fbd14868b61c3fdf59d9dcca0ec65b1e9ede05dc5fc156b"), longo)
        assertArrayEquals(range(0, 37), Cripto.ccmDecifrar(appKey, nonce7, longo))
    }

    @Test
    fun ccmRejeitaDadosAlterados() {
        val c = Cripto.ccmCifrar(appKey, nonce7, range(0, 20))
        c[3] = (c[3].toInt() xor 1).toByte()
        assertNull(Cripto.ccmDecifrar(appKey, nonce7, c))
    }

    @Test
    fun ecdhDosDoisLados() {
        val a = Cripto.novoParDeChaves()
        val b = Cripto.novoParDeChaves()
        assertArrayEquals(Cripto.ecdh(a.private, Cripto.publicaEmBruto(b)), Cripto.ecdh(b.private, Cripto.publicaEmBruto(a)))
    }

    @Test
    fun formatos() {
        val v = Formatos.viagens(listOf("0123004501520289" + "0000000000000000" + "00500010"))
        assertEquals(listOf(Viagem(12.3, 4.5, 15.2, 28.9)), v)
        assertEquals(Formatos.MaisBateria(70.014, 0), Formatos.maisBateria("01117e05a600000303e8"))
        assertEquals("15/08/2025", Formatos.data("20250815"))
        assertEquals("22/03/2025", Formatos.data("250322"))
        assertEquals(Formatos.Pneus(false, 30, 12), Formatos.pneus("2030012"))
        assertEquals("05/03/2026 14:30", Formatos.ultimaTemperaturaExtrema("1a03050e1e00" + "0010"))
        assertEquals("nunca", Formatos.ultimaTemperaturaExtrema("0000000000000000"))
    }

    @Test
    fun frames() {
        assertArrayEquals(hex("0b2001000201010200" + "0000"), Frames.get(P.BATERIA, 1))
        // SET bloqueada=true: siid 2, piid 2, tipo BOOL, 1 byte
        assertArrayEquals(hex("0c20" + "0500" + "0001" + "02" + "0200" + "0100" + "01"), Frames.set(P.BLOQUEADA, byteArrayOf(1), 5))
        // resposta a GET da bateria: 87 %
        val resposta = hex("0d200100030101020000000110" + "57")
        assertEquals(0, Frames.estado(resposta))
        assertEquals(87L, Frames.valor(resposta))
        assertEquals(-5L, Frames.descodificar(Tipo.INT8, byteArrayOf(-5)))
        assertEquals(42.5f, Frames.descodificar(Tipo.FLOAT, byteArrayOf(0, 0, 0x2a, 0x42)))
    }
}
