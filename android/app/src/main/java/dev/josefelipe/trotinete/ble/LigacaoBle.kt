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

class BleException(msg: String) : Exception(msg)

/** Uma notificação recebida: id curto da característica (ex.: 0x0016) e os bytes. */
class Notificacao(val id: Int, val dados: ByteArray)

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
    private suspend fun <T> operacao(arranque: (BluetoothGatt) -> Boolean): T? = mutex.withLock {
        val g = gatt ?: throw BleException("Sem ligação")
        val d = CompletableDeferred<Any?>()
        pendente = d
        try {
            if (!arranque(g)) return@withLock null
            @Suppress("UNCHECKED_CAST")
            withTimeoutOrNull(TIMEOUT_OP) { d.await() } as T?
        } finally {
            pendente = null
        }
    }

    /** Liga-se, negocia o MTU, descobre os serviços e ativa as notificações das [notificar]. */
    suspend fun ligar(notificar: List<Int>) {
        ligado = CompletableDeferred()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val ok = withTimeoutOrNull(20_000) { ligado.await() } ?: false
        if (!ok) {
            fechar()
            throw BleException("Não consegui ligar à trotinete (está ligada e perto?)")
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

    /** Força do sinal em dBm (ex.: -60 perto, -90 longe), ou null se falhar. */
    suspend fun rssi(): Int? = operacao { it.readRemoteRssi() }

    fun fechar() {
        _ativa.value = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        filas.values.toSet().forEach { f -> while (f.tryReceive().isSuccess) Unit }
    }
}
