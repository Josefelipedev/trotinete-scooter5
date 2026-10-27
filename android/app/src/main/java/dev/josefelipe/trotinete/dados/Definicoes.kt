package dev.josefelipe.trotinete.dados

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Definições da app. A chave da trotinete é guardada cifrada com uma chave do Android Keystore. */
class Definicoes(context: Context) {

    private val prefs = context.getSharedPreferences("trotinete", Context.MODE_PRIVATE)

    var mac: String?
        get() = prefs.getString("mac", null)
        set(v) = prefs.edit().putString("mac", v).apply()

    var ltmk: ByteArray?
        get() = prefs.getString("ltmk", null)?.let { decifrar(it) }
        set(v) = prefs.edit().putString("ltmk", v?.let { cifrar(it) }).apply()

    val configurada get() = mac != null && ltmk != null

    var autoBloqueio: Boolean
        get() = prefs.getBoolean("auto_bloqueio", false)
        set(v) = prefs.edit().putBoolean("auto_bloqueio", v).apply()

    var autoDesbloqueio: Boolean
        get() = prefs.getBoolean("auto_desbloqueio", false)
        set(v) = prefs.edit().putBoolean("auto_desbloqueio", v).apply()

    /** Sinal (dBm) abaixo do qual se considera que te afastaste (afinado em casa do Jose a 27/out/2026). */
    var limiarDbm: Int
        get() = prefs.getInt("limiar", -78)
        set(v) = prefs.edit().putInt("limiar", v).apply()

    /** Avisar quando a carga chega a esta percentagem (0 = não avisar). Parar nos 80-90% poupa a bateria. */
    var limiarCarga: Int
        get() = prefs.getInt("limiar_carga", 90)
        set(v) = prefs.edit().putInt("limiar_carga", v).apply()

    var avisosBateria: Boolean
        get() = prefs.getBoolean("avisos_bateria", true)
        set(v) = prefs.edit().putBoolean("avisos_bateria", v).apply()

    var gravarViagens: Boolean
        get() = prefs.getBoolean("gravar_viagens", true)
        set(v) = prefs.edit().putBoolean("gravar_viagens", v).apply()

    /** Preço da eletricidade (€/kWh), para o custo das viagens. */
    var precoKwh: Float
        get() = prefs.getFloat("preco_kwh", 0.20f)
        set(v) = prefs.edit().putFloat("preco_kwh", v).apply()

    /** Segundos seguidos de sinal fraco antes de bloquear. */
    var segundos: Int
        get() = prefs.getInt("segundos", 6)
        set(v) = prefs.edit().putInt("segundos", v).apply()

    private fun chaveKeystore(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun cifrar(dados: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, chaveKeystore()) }
        return Base64.encodeToString(c.iv + c.doFinal(dados), Base64.NO_WRAP)
    }

    private fun decifrar(texto: String): ByteArray? = runCatching {
        val b = Base64.decode(texto, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, chaveKeystore(), GCMParameterSpec(128, b, 0, 12))
        }
        c.doFinal(b, 12, b.size - 12)
    }.getOrNull()

    private companion object {
        const val ALIAS = "trotinete_ltmk"
    }
}
