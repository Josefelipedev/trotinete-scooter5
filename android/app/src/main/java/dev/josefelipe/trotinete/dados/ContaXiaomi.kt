package dev.josefelipe.trotinete.dados

import android.util.Base64
import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class ContaException(msg: String) : Exception(msg)

/** Trotinete associada à conta Xiaomi. */
data class TrotineteNaConta(val servidor: String, val did: String, val modelo: String?, val nome: String?, val mac: String?)

/**
 * Login na conta Xiaomi e pedido da chave Bluetooth (ltmk); é o mesmo de pc/trotinete/cloud.py.
 *
 * O login e a assinatura seguem o Xiaomi-cloud-tokens-extractor de Piotr Machowski (MIT).
 * O login é feito na própria página da Xiaomi (no browser), por isso a app nunca vê a palavra-passe.
 * Estas APIs não são oficiais: a Xiaomi pode mudá-las sem aviso.
 */
class ContaXiaomi {

    companion object {
        val SERVIDORES = listOf("de", "i2", "ru", "sg", "us", "cn", "tw", "in")
        private val IV_PIN = byteArrayOf(
            0x7a, 0xa4.toByte(), 0xc6.toByte(), 0x8c.toByte(), 0x59, 0x0d, 0x40, 0x31,
            0xb9.toByte(), 0x80.toByte(), 0xd9.toByte(), 0x8b.toByte(), 0x41, 0x02, 0x38, 0x00,
        )

        /** RC4 com os primeiros 1024 bytes do fluxo descartados, como a Xiaomi usa. */
        fun rc4(chave: ByteArray, dados: ByteArray): ByteArray {
            val s = IntArray(256) { it }
            var j = 0
            for (i in 0 until 256) {
                j = (j + s[i] + (chave[i % chave.size].toInt() and 0xFF)) and 0xFF
                s[i] = s[j].also { s[j] = s[i] }
            }
            var i = 0
            j = 0
            fun proximo(): Int {
                i = (i + 1) and 0xFF
                j = (j + s[i]) and 0xFF
                s[i] = s[j].also { s[j] = s[i] }
                return s[(s[i] + s[j]) and 0xFF]
            }
            repeat(1024) { proximo() }
            return ByteArray(dados.size) { (dados[it].toInt() xor proximo()).toByte() }
        }

        private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
        private fun deB64(s: String) = Base64.decode(s, Base64.NO_WRAP)
        private fun json(texto: String) = JSONObject(texto.replace("&&&START&&&", ""))
    }

    private val cookies = mutableListOf<Cookie>()
    private val http = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                this@ContaXiaomi.cookies += cookies
            }

            override fun loadForRequest(url: HttpUrl) = cookies.filter { it.matches(url) }
        })
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val api = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()

    private val agente: String = run {
        val r = SecureRandom()
        val id = (1..13).map { ('A' + r.nextInt(5)) }.joinToString("")
        val txt = (1..18).map { ('a' + r.nextInt(26)) }.joinToString("")
        "$txt-$id APP/com.xiaomi.mihome APPV/10.5.201"
    }

    private var userId: String? = null
    private var ssecurity: String? = null
    private var serviceToken: String? = null
    private var urlEspera: String? = null
    private var tempoLimite = 300L

    // --- login ---

    /** Pede à Xiaomi um pedido de login. Devolve o URL a abrir no browser para iniciar sessão. */
    fun iniciarLogin(): String {
        val url = "https://account.xiaomi.com/longPolling/loginUrl".toHttpUrl().newBuilder()
            .addQueryParameter("_qrsize", "480")
            .addEncodedQueryParameter("qs", "%3Fsid%3Dxiaomiio%26_json%3Dtrue")
            .addQueryParameter("callback", "https://sts.api.io.mi.com/sts")
            .addQueryParameter("_hasLogo", "false")
            .addQueryParameter("sid", "xiaomiio")
            .addQueryParameter("serviceParam", "")
            .addQueryParameter("_locale", "pt_PT")
            .addQueryParameter("_dc", System.currentTimeMillis().toString())
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val d = json(r.body?.string() ?: "")
            if (!r.isSuccessful || !d.has("loginUrl")) throw ContaException("A Xiaomi não aceitou o pedido de login (${r.code})")
            urlEspera = d.getString("lp")
            tempoLimite = d.optLong("timeout", 300)
            return d.getString("loginUrl")
        }
    }

    /**
     * Espera (bloqueia) até o login ser confirmado no browser; depois obtém o serviceToken.
     * [continuar] é consultado a cada volta, para parar quando a tentativa é cancelada.
     */
    fun esperarLogin(continuar: () -> Boolean) {
        val lp = urlEspera ?: throw ContaException("Login não iniciado")
        val espera = http.newBuilder().readTimeout(10, TimeUnit.SECONDS).build()
        val inicio = System.currentTimeMillis()
        var dados: JSONObject? = null
        while (dados == null) {
            if (!continuar()) throw ContaException("Login cancelado")
            if (System.currentTimeMillis() - inicio > tempoLimite * 1000) throw ContaException("O tempo para iniciar sessão acabou; tenta de novo")
            try {
                espera.newCall(Request.Builder().url(lp).build()).execute().use { r ->
                    if (r.isSuccessful) dados = json(r.body?.string() ?: "")
                }
            } catch (e: SocketTimeoutException) {
                // ainda à espera do login: volta a perguntar
            }
        }
        val d = dados!!
        userId = d.get("userId").toString()
        ssecurity = d.getString("ssecurity")
        http.newCall(Request.Builder().url(d.getString("location")).build()).execute().use { it.body?.close() }
        serviceToken = cookies.lastOrNull { it.name == "serviceToken" }?.value
            ?: throw ContaException("Não consegui concluir o login (sem serviceToken)")
    }

    // --- pedidos encriptados ---

    private fun assinatura(caminho: String, nonceAssinado: String, params: Map<String, String>): String {
        val partes = listOf("POST", caminho) + params.map { "${it.key}=${it.value}" } + nonceAssinado
        return b64(MessageDigest.getInstance("SHA-1").digest(partes.joinToString("&").toByteArray()))
    }

    private fun chamar(servidor: String, caminho: String, dados: JSONObject): JSONObject? {
        val ss = ssecurity ?: throw ContaException("Sem sessão")
        val bytes = ByteArray(12)
        SecureRandom().nextBytes(bytes)
        val minutos = (System.currentTimeMillis() / 60_000).toInt()
        for (k in 0 until 4) bytes[8 + k] = (minutos ushr (8 * (3 - k))).toByte()
        val nonce = b64(bytes)
        val nonceAssinado = b64(MessageDigest.getInstance("SHA-256").digest(deB64(ss) + deB64(nonce)))
        val chaveRc4 = deB64(nonceAssinado)

        val params = linkedMapOf("data" to dados.toString())
        params["rc4_hash__"] = assinatura(caminho, nonceAssinado, params)
        val cifrados = LinkedHashMap(params.mapValues { b64(rc4(chaveRc4, it.value.toByteArray())) })
        cifrados["signature"] = assinatura(caminho, nonceAssinado, cifrados)
        cifrados["ssecurity"] = ss
        cifrados["_nonce"] = nonce

        val base = "https://${if (servidor == "cn") "" else "$servidor."}api.io.mi.com/app$caminho".toHttpUrl().newBuilder()
        cifrados.forEach { (k, v) -> base.addQueryParameter(k, v) }
        val pedido = Request.Builder()
            .url(base.build())
            .post(ByteArray(0).toRequestBody())
            .header("Accept-Encoding", "identity")
            .header("User-Agent", agente)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("x-xiaomi-protocal-flag-cli", "PROTOCAL-HTTP2")
            .header("MIOT-ENCRYPT-ALGORITHM", "ENCRYPT-RC4")
            .header("Cookie", "userId=$userId; yetAnotherServiceToken=$serviceToken; serviceToken=$serviceToken; locale=en_GB; channel=MI_APP_STORE")
            .build()
        return try {
            // sem o CookieJar: o OkHttp substituiria o nosso cabeçalho Cookie pelos cookies do login
            api.newCall(pedido).execute().use { r ->
                Log.d("Trotinete", "API $servidor$caminho -> HTTP ${r.code}")
                if (!r.isSuccessful) return null
                JSONObject(String(rc4(chaveRc4, deB64(r.body?.string() ?: return null))))
            }
        } catch (e: IOException) {
            null
        } catch (e: org.json.JSONException) {
            null
        }
    }

    // --- trotinetes e chave ---

    fun procurarTrotinetes(): List<TrotineteNaConta> {
        for (servidor in SERVIDORES) {
            val casas = chamar(servidor, "/v2/homeroom/gethome", JSONObject().apply {
                put("fg", true); put("fetch_share", true); put("fetch_share_dev", true); put("limit", 300); put("app_ver", 7)
            }) ?: continue
            Log.d("Trotinete", "gethome $servidor: code=${casas.optInt("code", -1)} casas=${casas.optJSONObject("result")?.optJSONArray("homelist")?.length()}")
            if (casas.optInt("code", -1) != 0) continue
            val lista = casas.optJSONObject("result")?.optJSONArray("homelist") ?: continue
            val achadas = mutableListOf<TrotineteNaConta>()
            for (i in 0 until lista.length()) {
                val casa = lista.getJSONObject(i)
                val disp = chamar(servidor, "/v2/home/home_device_list", JSONObject().apply {
                    put("home_owner", casa.opt("uid") ?: userId); put("home_id", casa.get("id"))
                    put("limit", 200); put("get_split_device", true); put("support_smart_home", true)
                })?.optJSONObject("result")?.optJSONArray("device_info") ?: continue
                for (k in 0 until disp.length()) {
                    val d = disp.getJSONObject(k)
                    val modelo = d.optString("model")
                    if ("scooter" in modelo) {
                        achadas += TrotineteNaConta(servidor, d.get("did").toString(), modelo, d.optString("name"), d.optString("mac").uppercase().ifEmpty { null })
                    }
                }
            }
            if (achadas.isNotEmpty()) return achadas
        }
        return emptyList()
    }

    /** Devolve (chave em bruto, precisa de PIN). Se precisar de PIN, decifra-se com [decifrarComPin]. */
    fun pedirChave(servidor: String, did: String): Pair<ByteArray, Boolean> {
        val r = chamar(servidor, "/share/askbluetoothkey", JSONObject().apply {
            put("type", "own"); put("did", did); put("keyid", 0)
        }) ?: throw ContaException("A Xiaomi não respondeu ao pedido da chave")
        if (r.optInt("code", -1) != 0) throw ContaException("A Xiaomi recusou o pedido da chave (${r.optString("message")})")
        val res = r.optJSONObject("result") ?: throw ContaException("Resposta sem chave")
        val chave = res.optString("key").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return when (res.optInt("encrypt_type", 0)) {
            0 -> chave.also { if (it.size != 32) throw ContaException("A chave tem ${it.size} bytes") } to false
            1 -> chave to true
            else -> throw ContaException("Tipo de chave desconhecido")
        }
    }

    fun decifrarComPin(cifrada: ByteArray, pin: String): ByteArray {
        val k = MessageDigest.getInstance("MD5").digest(pin.toByteArray())
        return Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(IV_PIN))
            doFinal(cifrada)
        }
    }
}
