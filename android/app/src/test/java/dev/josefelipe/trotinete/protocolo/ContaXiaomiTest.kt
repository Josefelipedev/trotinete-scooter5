package dev.josefelipe.trotinete.protocolo

import dev.josefelipe.trotinete.dados.ContaXiaomi
import dev.josefelipe.trotinete.dados.formatarMac
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContaXiaomiTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Valor esperado gerado com pc/trotinete/cloud.py (_rc4). */
    @Test
    fun rc4IgualAoPython() {
        val chave = ByteArray(32) { it.toByte() }
        val cifrado = ContaXiaomi.rc4(chave, """{"code":0,"ola":"trotinete"}""".toByteArray())
        assertArrayEquals(hex("cd4a33835496dec16f8dd91106354148a3bdda4c49df43d7e0e337e8"), cifrado)
        assertEquals("""{"code":0,"ola":"trotinete"}""", String(ContaXiaomi.rc4(chave, cifrado)))
    }

    @Test
    fun mac() {
        assertEquals("AA:BB:CC:11:22:33", formatarMac("AABBCC112233"))
        assertEquals("AA:BB:CC:11:22:33", formatarMac("aa:bb:cc:11:22:33"))
        assertNull(formatarMac("AABBCC1122"))
    }
}
