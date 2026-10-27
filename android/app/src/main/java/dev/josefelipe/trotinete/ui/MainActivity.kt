package dev.josefelipe.trotinete.ui

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.josefelipe.trotinete.dados.Configuracao
import dev.josefelipe.trotinete.dados.Definicoes
import dev.josefelipe.trotinete.servico.ServicoTrotinete

class MainActivity : ComponentActivity() {

    private lateinit var defs: Definicoes
    private var ecra by mutableStateOf(Ecra.PAINEL)

    private val permissoes = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.BLUETOOTH_CONNECT] != false) ServicoTrotinete.iniciar(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        defs = Definicoes(this)
        // Depois do login no separador do Chrome: volta à app (CLEAR_TOP fecha o separador por cima dela).
        Configuracao.aoEntrar = {
            runOnUiThread {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }
        importar(intent)
        if (!defs.configurada) ecra = Ecra.AJUSTES
        setContent {
            Tema {
                when (ecra) {
                    Ecra.PAINEL -> Painel(
                        defs = defs,
                        ligar = ::ligar,
                        desligar = { ServicoTrotinete.parar(this) },
                        abrirAjustes = { ecra = Ecra.AJUSTES },
                    )
                    Ecra.AJUSTES -> Ajustes(
                        defs = defs,
                        voltar = if (defs.configurada) ({ ecra = Ecra.PAINEL }) else null,
                        guardado = {
                            ecra = Ecra.PAINEL
                            ServicoTrotinete.parar(this)
                            ligar()
                        },
                    )
                }
            }
        }
        if (defs.configurada) ligar()
    }

    override fun onDestroy() {
        Configuracao.aoEntrar = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (importar(intent)) {
            ServicoTrotinete.parar(this)
            ligar()
        }
    }

    /**
     * Permite configurar a partir do PC por adb, sem escrever a chave no telemóvel:
     *   adb shell am start -n dev.josefelipe.trotinete/.ui.MainActivity --es mac AA:BB:.. --es chave <64 hex>
     * Só na versão de depuração: na pública, outra app podia trocar a configuração por esta porta.
     */
    private fun importar(intent: Intent?): Boolean {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return false
        val mac = intent?.getStringExtra("mac")?.trim()?.uppercase() ?: return false
        val chave = intent.getStringExtra("chave")?.trim()?.lowercase() ?: return false
        if (!Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(mac) || !Regex("[0-9a-f]{64}").matches(chave)) return false
        defs.mac = mac
        defs.ltmk = chave.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        intent.removeExtra("chave")
        ecra = Ecra.PAINEL
        return true
    }

    private fun ligar() {
        val pedir = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        permissoes.launch(pedir.toTypedArray())
    }

    private enum class Ecra { PAINEL, AJUSTES }
}
