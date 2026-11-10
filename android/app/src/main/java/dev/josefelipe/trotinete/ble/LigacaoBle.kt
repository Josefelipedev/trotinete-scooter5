package dev.josefelipe.trotinete.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

open class BleException(msg: String) : Exception(msg)

/** A passive (autoConnect) wait ended without the scooter showing up. Not an error: it is simply out of range. */
class OutOfRangeException : BleException("Trotinete fora de alcance")

/** Uma notificação recebida: id curto da característica (ex.: 0x0016) e os bytes. */
class Notificacao(val id: Int, val dados: ByteArray)

/**
 * What the Bluetooth stack reported when the link went down. The scooter powering off ends the
 * connection cleanly ([SWITCHED_OFF]); walking out of range makes it time out ([LINK_LOST]).
 */
enum class DisconnectReason { LINK_LOST, SWITCHED_OFF, OTHER }

/**
 * Ligação GATT com coroutines. O Android só aceita uma operação GATT de cada vez por ligação,
 * por isso todas passam por [operacao], que espera pelo callback antes de libertar a próxima.
 *
 * As notificações vão para filas por característica ([fila]); várias características podem
 * partilhar a mesma fila (as respostas de propriedades chegam por 0x001a e 0x001b).
 */
@SuppressLint("MissingPermission") // as permissões são pedidas antes de o serviço arrancar
class LigacaoBle(private val context: Context, private val device: BluetoothDevice) {

    companion object {
        val SERVICO_MI: UUID = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TIMEOUT_OP = 5_000L
        private const val RSSI_TIMEOUT_MS = 1_500L
        private const val DIRECT_TIMEOUT_MS = 20_000L
        private const val PASSIVE_REARM_MS = 15 * 60_000L

        fun idCurto(uuid: UUID): Int = (uuid.mostSignificantBits ushr 32).toInt() and 0xFFFF
    }

    private var gatt: BluetoothGatt? = null
    private val chars = mutableMapOf<Int, BluetoothGattCharacteristic>()
    private val filas = mutableMapOf<Int, Channel<Notificacao>>()
    private val mutex = Mutex()
    private var pendente: CompletableDeferred<Any?>? = null
    private var ligado = CompletableDeferred<Boolean>()

    private val _ativa = MutableStateFlow(false)
    val ativa: StateFlow<Boolean> = _ativa

    var disconnectReason: DisconnectReason? = null
        private set

    fun fila(id: Int): Channel<Notificacao> = filas.getOrPut(id) { Channel(Channel.UNLIMITED) }

    /** Faz [b] partilhar a fila de [a]. Chamar antes de [ligar]. */
    fun partilharFila(a: Int, b: Int) {
        filas[b] = fila(a)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                ligado.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // HCI reasons: 0x08 connection supervision timeout, 0x13 remote user terminated connection
                disconnectReason = when (status) {
                    0x08 -> DisconnectReason.LINK_LOST
                    0x13 -> DisconnectReason.SWITCHED_OFF
                    else -> DisconnectReason.OTHER
                }
                _ativa.value = false
                ligado.complete(false)
                pendente?.completeExceptionally(BleException("Ligação perdida"))
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            pendente?.complete(mtu)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pendente?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendente?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendente?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            pendente?.complete(if (status == BluetoothGatt.GATT_SUCCESS) value else null)
        }

        @Deprecated("Android 12 e anteriores")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            pendente?.complete(if (status == BluetoothGatt.GATT_SUCCESS) c.value else null)
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            pendente?.complete(if (status == BluetoothGatt.GATT_SUCCESS) rssi else null)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val id = idCurto(c.uuid)
            filas[id]?.trySend(Notificacao(id, value))
        }

        @Deprecated("Android 12 e anteriores")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                onCharacteristicChanged(g, c, c.value.clone())
            }
        }
    }

    /** Executa uma operação GATT e espera pelo callback correspondente. */
    private suspend fun <T> operacao(timeoutMs: Long = TIMEOUT_OP, arranque: (BluetoothGatt) -> Boolean): T? = mutex.withLock {
        val g = gatt ?: throw BleException("Sem ligação")
        val d = CompletableDeferred<Any?>()
        pendente = d
        try {
            if (!arranque(g)) return@withLock null
            @Suppress("UNCHECKED_CAST")
            withTimeoutOrNull(timeoutMs) { d.await() } as T?
        } finally {
            pendente = null
        }
    }

    /**
     * Liga-se, negocia o MTU, descobre os serviços e ativa as notificações das [notificar].
     *
     * [passive] = false is a direct connection: fast when the scooter is nearby, gives up after 20 s.
     * [passive] = true hands the job to the Bluetooth stack (autoConnect): it listens for the scooter
     * at almost no battery cost and connects the moment it shows up, however long that takes. We still
     * re-arm it every [PASSIVE_REARM_MS] because some phones silently drop a long-standing autoConnect.
     */
    suspend fun ligar(notificar: List<Int>, passive: Boolean = false) {
        ligado = CompletableDeferred()
        gatt = device.connectGatt(context, passive, callback, BluetoothDevice.TRANSPORT_LE)
        val ok = withTimeoutOrNull(if (passive) PASSIVE_REARM_MS else DIRECT_TIMEOUT_MS) { ligado.await() } ?: false
        if (!ok) {
            fechar()
            throw if (passive) OutOfRangeException() else BleException("Não consegui ligar à trotinete (está ligada e perto?)")
        }
        operacao<Int> { it.requestMtu(247) }
        if (operacao<Boolean> { it.discoverServices() } != true) throw BleException("Falha a descobrir serviços")
        val servico = gatt?.getService(SERVICO_MI) ?: throw BleException("A trotinete não tem o serviço FE95")
        chars.clear()
        servico.characteristics.forEach { chars[idCurto(it.uuid)] = it }
        for (id in notificar) ativarNotificacoes(id)
        // prioridade alta: os pedidos de propriedades respondem mais depressa
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        _ativa.value = true
    }

    private fun char(id: Int) = chars[id] ?: throw BleException("Característica 0x%04x não existe".format(id))

    private suspend fun ativarNotificacoes(id: Int) {
        val c = char(id)
        fila(id)
        gatt?.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD) ?: throw BleException("0x%04x sem descritor de notificações".format(id))
        val ok = operacao<Boolean> { g ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
        }
        if (ok != true) throw BleException("Não consegui ativar notificações em 0x%04x".format(id))
    }

    /** Escrita sem resposta (as características da trotinete só aceitam esta). */
    suspend fun escrever(id: Int, dados: ByteArray) {
        val c = char(id)
        val ok = operacao<Boolean> { g ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, dados, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                c.value = dados
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
        if (ok != true) throw BleException("Escrita em 0x%04x falhou".format(id))
    }

    suspend fun ler(id: Int): ByteArray? {
        val c = char(id)
        return operacao<ByteArray> { it.readCharacteristic(c) }
    }

    /**
     * Força do sinal em dBm (ex.: -60 perto, -90 longe), ou null se falhar.
     * Short timeout on purpose: a late RSSI answer is itself the signal that the link is dying,
     * and the proximity watcher needs to know that within seconds, not after the default 5 s.
     */
    suspend fun rssi(): Int? = operacao(RSSI_TIMEOUT_MS) { it.readRemoteRssi() }

    fun fechar() {
        _ativa.value = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        filas.values.toSet().forEach { f -> while (f.tryReceive().isSuccess) Unit }
    }
}
