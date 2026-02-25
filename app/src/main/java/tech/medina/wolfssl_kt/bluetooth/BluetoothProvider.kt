package tech.medina.wolfssl_kt.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

interface BluetoothProvider {
    val incomingChannel: Channel<ByteArray>
    val outgoingChannel: Channel<ByteArray>
}

class GattBluetoothProvider(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : BluetoothProvider {

    override val incomingChannel = Channel<ByteArray>(Channel.UNLIMITED)
    override val outgoingChannel = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile
    private var gatt: BluetoothGatt? = null

    @Volatile
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    init {
        scope.launch {
            for (data in outgoingChannel) {
                writeToCharacteristic(data)
            }
        }
    }

    fun bind(gatt: BluetoothGatt, writeCharacteristic: BluetoothGattCharacteristic) {
        this.gatt = gatt
        this.writeCharacteristic = writeCharacteristic
    }

    fun unbind() {
        gatt = null
        writeCharacteristic = null
    }

    fun onCharacteristicReceived(value: ByteArray) {
        incomingChannel.trySend(value.copyOf())
    }

    @Suppress("DEPRECATION")
    fun onCharacteristicReceived(characteristic: BluetoothGattCharacteristic) {
        val value = characteristic.value ?: return
        onCharacteristicReceived(value)
    }

    @SuppressLint("MissingPermission")
    private fun writeToCharacteristic(data: ByteArray) {
        val currentGatt = gatt ?: return
        val currentCharacteristic = writeCharacteristic ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = currentGatt.writeCharacteristic(
                currentCharacteristic,
                data,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Failed to queue BLE characteristic write, status=$status")
            }
            return
        }

        @Suppress("DEPRECATION")
        run {
            currentCharacteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            currentCharacteristic.value = data
            val started = currentGatt.writeCharacteristic(currentCharacteristic)
            if (!started) {
                Log.w(TAG, "Failed to start BLE characteristic write")
            }
        }
    }

    private companion object {
        const val TAG = "GattBluetoothProvider"
    }
}
