package dev.josefelipe.trotinete.dados

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Dados lidos do QR do autocolante (home.mi.com/do/home.html?...&d=<did>&m=<MAC>&O=...). */
data class QrTrotinete(val mac: String?, val did: String?)

fun lerQrTrotinete(texto: String): QrTrotinete? {
    val t = texto.trim()
    val macSolto = Regex("^([0-9A-Fa-f]{2}[:-]?){5}[0-9A-Fa-f]{2}$")
    if (macSolto.matches(t)) return QrTrotinete(formatarMac(t), null)
    val uri = runCatching { Uri.parse(t) }.getOrNull() ?: return null
    if (uri.host?.endsWith("mi.com") != true) return null
    // o parâmetro O (código secreto de emparelhamento) é ignorado de propósito
    val mac = uri.getQueryParameter("m")?.let { formatarMac(it) }
    val did = uri.getQueryParameter("d")?.takeIf { it.all(Char::isDigit) }
    return if (mac == null && did == null) null else QrTrotinete(mac, did)
}

fun formatarMac(s: String): String? {
    val hex = s.filter { it.isLetterOrDigit() }.uppercase()
    if (hex.length != 12 || !hex.all { it in "0123456789ABCDEF" }) return null
    return hex.chunked(2).joinToString(":")
}

sealed interface Passo {
    data object Inicio : Passo
    data object AIniciar : Passo
    data class AbrirLogin(val url: String) : Passo
    data object AProcurar : Passo
    data class Escolher(val trotinetes: List<TrotineteNaConta>) : Passo
    data class PedirPin(val mac: String, val cifrada: ByteArray) : Passo
    data class Erro(val mensagem: String) : Passo
    data object Concluido : Passo
}

/**
 * Configuração pela conta Xiaomi: login no browser -> encontrar a trotinete -> pedir a chave -> PIN.
 * Vive fora do ecrã para sobreviver à ida ao browser.
 */
object Configuracao {
    private const val TAG = "Trotinete"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _passo = MutableStateFlow<Passo>(Passo.Inicio)
    val passo: StateFlow<Passo> = _passo

    /** Sessão da tentativa que chegou ao fim do login; [escolher] e [usarPin] usam esta. */
    private var conta: ContaXiaomi? = null
    private var trabalho: Job? = null

    fun reiniciar() {
        trabalho?.cancel()
        _passo.value = Passo.Inicio
    }

    private fun falhou(e: Exception) {
        if (e is CancellationException) throw e
        Log.w(TAG, "Configuração falhou", e)
        _passo.value = Passo.Erro(e.message ?: "Erro inesperado")
    }

    /** [qr] vem do autocolante (opcional): com ele vamos direto à trotinete certa. */
    fun entrar(defs: Definicoes, qr: QrTrotinete?) {
        trabalho?.cancel()
        _passo.value = Passo.AIniciar
        trabalho = scope.launch {
            try {
                // cada tentativa usa a sua própria sessão do princípio ao fim
                val c = ContaXiaomi()
                val url = c.iniciarLogin()
                ensureActive()
                _passo.value = Passo.AbrirLogin(url)
                c.esperarLogin { isActive }
                ensureActive()
                conta = c
                _passo.value = Passo.AProcurar
                val lista = c.procurarTrotinetes()
                Log.i(TAG, "Trotinetes na conta: ${lista.map { "${it.modelo} ${it.mac} ${it.servidor}" }}")
                val escolhida = when {
                    lista.isEmpty() -> throw ContaException("Não encontrei nenhuma trotinete nesta conta Xiaomi")
                    qr?.did != null -> lista.firstOrNull { it.did == qr.did }
                        ?: throw ContaException("A trotinete do QR não está nesta conta Xiaomi")
                    qr?.mac != null -> lista.firstOrNull { it.mac == qr.mac } ?: lista.singleOrNull()
                    else -> lista.singleOrNull()
                }
                if (escolhida == null) {
                    _passo.value = Passo.Escolher(lista)
                    return@launch
                }
                obterChave(c, defs, escolhida, qr?.mac)
            } catch (e: Exception) {
                falhou(e)
            }
        }
    }

    fun escolher(defs: Definicoes, t: TrotineteNaConta) {
        val c = conta ?: return
        trabalho = scope.launch {
            try {
                obterChave(c, defs, t, null)
            } catch (e: Exception) {
                falhou(e)
            }
        }
    }

    private fun obterChave(c: ContaXiaomi, defs: Definicoes, t: TrotineteNaConta, macDoQr: String?) {
        val mac = t.mac ?: macDoQr ?: defs.mac
            ?: throw ContaException("A Xiaomi não deu o MAC: lê o QR da trotinete primeiro")
        val (chave, precisaPin) = c.pedirChave(t.servidor, t.did)
        Log.i(TAG, "Chave recebida (${chave.size} bytes, PIN=${precisaPin})")
        if (precisaPin) {
            _passo.value = Passo.PedirPin(mac, chave)
        } else {
            guardar(defs, mac, chave)
        }
    }

    /** A Xiaomi não confirma o PIN: se estiver errado, o login na trotinete é recusado e avisamos aí. */
    fun usarPin(defs: Definicoes, pin: String) {
        val p = _passo.value as? Passo.PedirPin ?: return
        val c = conta ?: return
        ultimoPedidoPin = p
        guardar(defs, p.mac, c.decifrarComPin(p.cifrada, pin))
    }

    /** Se o PIN estava errado: volta a pedir só o PIN, sem repetir o login (enquanto a app estiver aberta). */
    private var ultimoPedidoPin: Passo.PedirPin? = null
    val podeRepetirPin get() = ultimoPedidoPin != null

    fun repetirPin() {
        ultimoPedidoPin?.let { _passo.value = it }
    }

    private fun guardar(defs: Definicoes, mac: String, ltmk: ByteArray) {
        defs.mac = mac
        defs.ltmk = ltmk
        _passo.value = Passo.Concluido
    }
}
