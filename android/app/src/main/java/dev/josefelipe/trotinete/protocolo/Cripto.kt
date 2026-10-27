package dev.josefelipe.trotinete.protocolo

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A criptografia do login e das propriedades da Scooter 5:
 * ECDH P-256 -> HKDF-SHA256(partilhado || ltmk) -> AES-128-CCM com etiqueta de 4 bytes.
 *
 * O AES-CCM é implementado aqui (RFC 3610) em vez de usar o fornecedor do Android para
 * correr igual nos testes da JVM; os testes comparam-no com a biblioteca `cryptography` do Python.
 */
object Cripto {

    private const val CURVA = "secp256r1"

    fun novoParDeChaves(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(CURVA)) }.generateKeyPair()

    /** Chave pública em bruto como vai no Bluetooth: X || Y, 64 bytes, sem o prefixo 0x04. */
    fun publicaEmBruto(par: KeyPair): ByteArray {
        val w = (par.public as ECPublicKey).w
        return w.affineX.paraBytes(32) + w.affineY.paraBytes(32)
    }

    fun ecdh(privada: PrivateKey, remotaXY: ByteArray): ByteArray {
        require(remotaXY.size == 64) { "chave pública com ${remotaXY.size} bytes; esperava 64" }
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec(CURVA)) }
        val spec = ECPublicKeySpec(
            ECPoint(BigInteger(1, remotaXY.copyOfRange(0, 32)), BigInteger(1, remotaXY.copyOfRange(32, 64))),
            params.getParameterSpec(ECParameterSpec::class.java),
        )
        val remota = KeyFactory.getInstance("EC").generatePublic(spec)
        return KeyAgreement.getInstance("ECDH").run {
            init(privada)
            doPhase(remota, true)
            generateSecret()
        }
    }

    private fun BigInteger.paraBytes(n: Int): ByteArray {
        val b = toByteArray()
        return when {
            b.size == n -> b
            b.size > n -> b.copyOfRange(b.size - n, b.size)
            else -> ByteArray(n - b.size) + b
        }
    }

    fun hmacSha256(chave: ByteArray, dados: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(chave, "HmacSHA256"))
            doFinal(dados)
        }

    /** HKDF-SHA256 (RFC 5869). */
    fun hkdf(ikm: ByteArray, sal: ByteArray, info: ByteArray, tamanho: Int): ByteArray {
        val prk = hmacSha256(sal, ikm)
        val saida = ByteArray(tamanho)
        var t = ByteArray(0)
        var pos = 0
        var i = 1
        while (pos < tamanho) {
            t = hmacSha256(prk, t + info + byteArrayOf(i.toByte()))
            val n = minOf(t.size, tamanho - pos)
            System.arraycopy(t, 0, saida, pos, n)
            pos += n
            i++
        }
        return saida
    }

    fun crc32(dados: ByteArray): Long = CRC32().apply { update(dados) }.value

    // --- AES-128-CCM, nonce de 12 bytes (L = 3), sem dados adicionais ---

    private fun aes(chave: ByteArray): Cipher =
        Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(chave, "AES")) }

    private fun blocoContador(nonce: ByteArray, i: Int): ByteArray {
        val l = 15 - nonce.size
        val a = ByteArray(16)
        a[0] = (l - 1).toByte()
        System.arraycopy(nonce, 0, a, 1, nonce.size)
        for (k in 0 until l) a[15 - k] = (i ushr (8 * k)).toByte()
        return a
    }

    private fun cbcMac(c: Cipher, nonce: ByteArray, msg: ByteArray, etiqueta: Int): ByteArray {
        val l = 15 - nonce.size
        val b0 = ByteArray(16)
        b0[0] = (((etiqueta - 2) / 2) shl 3 or (l - 1)).toByte()
        System.arraycopy(nonce, 0, b0, 1, nonce.size)
        for (k in 0 until l) b0[15 - k] = (msg.size ushr (8 * k)).toByte()
        var x = c.doFinal(b0)
        var pos = 0
        while (pos < msg.size) {
            val bloco = ByteArray(16)
            System.arraycopy(msg, pos, bloco, 0, minOf(16, msg.size - pos))
            for (k in 0 until 16) bloco[k] = (bloco[k].toInt() xor x[k].toInt()).toByte()
            x = c.doFinal(bloco)
            pos += 16
        }
        return x.copyOf(etiqueta)
    }

    private fun ctr(c: Cipher, nonce: ByteArray, dados: ByteArray): ByteArray {
        val saida = ByteArray(dados.size)
        var pos = 0
        var i = 1
        while (pos < dados.size) {
            val s = c.doFinal(blocoContador(nonce, i))
            for (k in 0 until minOf(16, dados.size - pos)) saida[pos + k] = (dados[pos + k].toInt() xor s[k].toInt()).toByte()
            pos += 16
            i++
        }
        return saida
    }

    /** Devolve cifrado || etiqueta. */
    fun ccmCifrar(chave: ByteArray, nonce: ByteArray, texto: ByteArray, etiqueta: Int = 4): ByteArray {
        val c = aes(chave)
        val t = cbcMac(c, nonce, texto, etiqueta)
        val s0 = c.doFinal(blocoContador(nonce, 0))
        val u = ByteArray(etiqueta) { (t[it].toInt() xor s0[it].toInt()).toByte() }
        return ctr(c, nonce, texto) + u
    }

    /** Devolve o texto, ou null se a etiqueta não bater certo (chave errada ou dados corrompidos). */
    fun ccmDecifrar(chave: ByteArray, nonce: ByteArray, cifrado: ByteArray, etiqueta: Int = 4): ByteArray? {
        if (cifrado.size < etiqueta) return null
        val c = aes(chave)
        val texto = ctr(c, nonce, cifrado.copyOfRange(0, cifrado.size - etiqueta))
        val t = cbcMac(c, nonce, texto, etiqueta)
        val s0 = c.doFinal(blocoContador(nonce, 0))
        var diferenca = 0
        for (k in 0 until etiqueta) {
            diferenca = diferenca or ((t[k].toInt() xor s0[k].toInt()) xor cifrado[cifrado.size - etiqueta + k].toInt())
        }
        return if (diferenca and 0xFF == 0) texto else null
    }
}
