package dev.josefelipe.trotinete.servico

import dev.josefelipe.trotinete.protocolo.Prop
import dev.josefelipe.trotinete.protocolo.Viagem
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Ligacao { PARADA, A_LIGAR, LIGADA, SEM_LIGACAO }

data class Estado(
    val ligacao: Ligacao = Ligacao.PARADA,
    val aviso: String? = null,
    val rssi: Int? = null,
    val bateria: Long? = null,
    val voltagem: Float? = null,
    val corrente: Float? = null,
    val potencia: Float? = null,
    val autonomia: Float? = null,
    val viagem: Float? = null,
    val total: Float? = null,
    val velocidadeMedia: Float? = null,
    val velocidadeMaxima: Float? = null,
    val tempoViagem: Float? = null,
    val bloqueada: Boolean? = null,
    val emAndamento: Long? = null,
    val modo: Long? = null,
    val tempBateria: Long? = null,
    val tempTrotinete: Long? = null,
    val aCarregar: Boolean? = null,
    val ciclos: Long? = null,
    val saudeBateria: Long? = null,
    val codigoErro: Long? = null,
    val firmware: String? = null,
    val firmwareControlador: String? = null,
    val hardware: String? = null,
    /** Definições que a trotinete aceitou ler (as que não existem neste modelo ficam de fora). */
    val definicoes: Map<Prop, Any> = emptyMap(),
    /** Informação lida uma vez por ligação (números de série, datas, bateria). */
    val info: Map<Prop, Any> = emptyMap(),
    val viagens: List<Viagem> = emptyList(),
    /** Resultado de "ler todos os valores" (diagnóstico); null = ainda não pedido. */
    val diagnostico: List<String>? = null,
    val aExplorar: Boolean = false,
    /** Viagem a ser gravada agora (null = nenhuma). */
    val gravacao: Gravacao? = null,
    /** Muda sempre que se guarda uma viagem, para o ecrã recarregar a lista. */
    val viagensGuardadas: Int = 0,
    /** true se fomos nós a bloquear por afastamento (só então se desbloqueia sozinha). */
    val bloqueadaPorAfastamento: Boolean = false,
) {
    /**
     * Parada com segurança para bloquear: a trotinete diz que não está em andamento e gasta pouco.
     * Parada e ligada gasta ~57 W (luzes e controlador); a andar gasta centenas de watts.
     */
    val parada get() = emAndamento == 0L && (potencia == null || kotlin.math.abs(potencia) < 150f)
}

sealed interface Comando {
    data class Bloquear(val bloquear: Boolean) : Comando
    data object ProximoModo : Comando
    data object Encontrar : Comando
    data class Definir(val prop: Prop, val valor: Long) : Comando
    data object Explorar : Comando
    data class GravarViagem(val gravar: Boolean) : Comando

    /** Só por adb (ver Depuracao): "get s p" | "set s p tipo valor" | "explorar". */
    data class Depurar(val texto: String) : Comando
}

/** Ponte entre o serviço (que fala com a trotinete) e o ecrã. */
object Controlo {
    internal val _estado = MutableStateFlow(Estado())
    val estado: StateFlow<Estado> = _estado
    val comandos = Channel<Comando>(Channel.UNLIMITED)
}
