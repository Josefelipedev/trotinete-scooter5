package dev.josefelipe.trotinete.servico

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Testes a partir do PC, por cabo:
 *   adb shell am broadcast -n dev.josefelipe.trotinete/.servico.Depuracao --es cmd "get 2 4"
 * Os resultados vão para o logcat (etiqueta Trotinete). Protegido no manifesto pela permissão
 * DUMP, que só o adb (shell) tem: nenhuma app do telemóvel consegue mandar estes comandos.
 */
class Depuracao : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("cmd")?.let { Controlo.comandos.trySend(Comando.Depurar(it)) }
    }
}
