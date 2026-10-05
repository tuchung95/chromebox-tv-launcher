package local.chromebox.tvlauncher

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.UUID

/**
 * Client for the "ATV Voice over BLE" service that Xiaomi Bluetooth voice remotes expose
 * (Xiaomi Bluetooth Remote 2 reports model RC001, 2 Pro reports RC003).
 *
 * Flow: enable notifications, ask for capabilities, then on every mic-button press the remote
 * notifies us, we open the microphone, and it streams IMA ADPCM audio until it stops.
 *
 * All Bluetooth work runs on one background thread. Every [Listener] callback is invoked on
 * that thread, in order, so start / audio / stop never race each other.
 */
@SuppressLint("MissingPermission")
class AtvvRemote(
    private val context: Context,
    private val preferredAddress: () -> String?,
    private val listener: Listener
) {

    interface Listener {
        fun onRemoteState(state: State, name: String?)
        /** The mic button was pressed; audio will follow shortly. */
        fun onMicRequested()
        fun onVoiceStart()
        /** 16 kHz mono PCM. */
        fun onVoiceAudio(pcm: ShortArray)
        fun onVoiceStop()
    }

    enum class State { CONNECTING, READY, NOT_FOUND, NO_BLUETOOTH, NO_PERMISSION, NO_VOICE_SERVICE }

    private val thread = HandlerThread("atvv-remote").apply { start() }
    private val worker = Handler(thread.looper)
    private val bluetooth = context.getSystemService(BluetoothManager::class.java)

    private var running = false
    private var gatt: BluetoothGatt? = null
    private var deviceName: String? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var discoveryStarted = false
    private var retryCount = 0

    // Serialized GATT operations: Android allows only one outstanding request at a time
    private val operations = ArrayDeque<() -> Boolean>()
    private var operationBusy = false
    private val operationTimeout = Runnable { operationBusy = false; nextOperation() }

    // Negotiated audio parameters
    private var version = 0x0100
    private var codec = CODEC_ADPCM_16K
    private var frameSize = DEFAULT_FRAME_SIZE
    private var session: Byte = 0

    // Stream state
    private var micRequested = false
    private var streaming = false
    private val frame = ByteArray(MAX_FRAME_SIZE)
    private var frameFill = 0
    private var pendingSync: IntArray? = null
    private val decoder = ImaAdpcmDecoder()

    fun start() = worker.post {
        if (running) return@post
        running = true
        retryCount = 0
        connect()
    }

    fun stop() = worker.post {
        running = false
        worker.removeCallbacksAndMessages(null)
        teardown()
    }

    fun restart() {
        stop()
        start()
    }

    /** Ends the current voice session, for example once speech has been recognized. */
    fun closeMicrophone() = worker.post {
        if (micRequested || streaming) enqueue { writeTx(micCloseCommand()) }
        endStream()
    }

    fun release() {
        stop()
        worker.post { thread.quitSafely() }
    }

    /** Paired or connected Bluetooth devices, as (name, address), for the remote picker. */
    fun knownDevices(): List<Pair<String, String>> {
        if (!hasPermission()) return emptyList()
        return candidates().map { (it.name ?: it.address) to it.address }
    }

    private fun hasPermission() = Build.VERSION.SDK_INT < 31 ||
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun candidates(): List<BluetoothDevice> {
        val adapter = bluetooth?.adapter ?: return emptyList()
        val found = LinkedHashMap<String, BluetoothDevice>()
        runCatching { adapter.bondedDevices }.getOrNull()?.forEach { found[it.address] = it }
        runCatching { bluetooth.getConnectedDevices(BluetoothProfile.GATT) }.getOrNull()
            ?.forEach { found[it.address] = it }
        return found.values.toList()
    }

    private fun findRemote(): BluetoothDevice? {
        val devices = candidates()
        preferredAddress()?.let { address ->
            return devices.firstOrNull { it.address == address }
                ?: runCatching { bluetooth?.adapter?.getRemoteDevice(address) }.getOrNull()
        }
        return devices.firstOrNull { isXiaomiRemote(it.name) }
    }

    private fun connect() {
        if (!running || gatt != null) return
        val adapter = bluetooth?.adapter
        when {
            adapter == null || !adapter.isEnabled -> { report(State.NO_BLUETOOTH); scheduleRetry(); return }
            !hasPermission() -> { report(State.NO_PERMISSION); return }
        }
        val device = findRemote() ?: run { report(State.NOT_FOUND); scheduleRetry(); return }
        deviceName = device.name ?: device.address
        report(State.CONNECTING)
        discoveryStarted = false
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) scheduleRetry()
    }

    private fun scheduleRetry() {
        if (!running) return
        val delay = minOf(MAX_RETRY_MS, BASE_RETRY_MS shl minOf(retryCount, 4))
        retryCount++
        worker.postDelayed({ if (running && gatt == null) connect() }, delay)
    }

    private fun teardown() {
        endStream()
        operations.clear()
        operationBusy = false
        worker.removeCallbacks(operationTimeout)
        tx = null
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
    }

    private fun report(state: State) = listener.onRemoteState(state, deviceName)

    private fun discoverServices(g: BluetoothGatt) {
        if (discoveryStarted || g != gatt) return
        discoveryStarted = true
        if (!g.discoverServices()) {
            teardown()
            scheduleRetry()
        }
    }

    private fun setUp(g: BluetoothGatt) {
        val service = g.getService(SERVICE)
        val transmit = service?.getCharacteristic(TRANSMIT)
        val control = service?.getCharacteristic(CONTROL)
        val audio = service?.getCharacteristic(AUDIO)
        if (transmit == null || control == null || audio == null) {
            report(State.NO_VOICE_SERVICE)
            return
        }
        tx = transmit
        operations.clear()
        operationBusy = false
        g.getService(DEVICE_INFORMATION)?.getCharacteristic(MODEL_NUMBER)?.let { model ->
            enqueue { g.readCharacteristic(model) }
        }
        enqueue { enableNotifications(g, control) }
        enqueue { enableNotifications(g, audio) }
        enqueue { writeTx(GET_CAPABILITIES) }
    }

    private fun enableNotifications(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic): Boolean {
        if (!g.setCharacteristicNotification(characteristic, true)) return false
        val descriptor = characteristic.getDescriptor(CLIENT_CONFIG) ?: return false
        @Suppress("DEPRECATION")
        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        return g.writeDescriptor(descriptor)
    }

    private fun writeTx(command: ByteArray): Boolean {
        val characteristic = tx ?: return false
        val g = gatt ?: return false
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        @Suppress("DEPRECATION")
        characteristic.value = command
        @Suppress("DEPRECATION")
        return g.writeCharacteristic(characteristic)
    }

    private fun enqueue(operation: () -> Boolean) {
        operations.addLast(operation)
        if (!operationBusy) nextOperation()
    }

    private fun nextOperation() {
        while (operations.isNotEmpty()) {
            val operation = operations.removeFirst()
            if (runCatching(operation).getOrDefault(false)) {
                operationBusy = true
                worker.postDelayed(operationTimeout, OPERATION_TIMEOUT_MS)
                return
            }
        }
        operationBusy = false
    }

    private fun operationDone() {
        worker.removeCallbacks(operationTimeout)
        operationBusy = false
        nextOperation()
    }

    // --- ATVV protocol ---

    private fun micOpenCommand() =
        if (version >= 0x0100) byteArrayOf(0x0C, 0x00) else byteArrayOf(0x0C, 0x00, codec.toByte())

    private fun micCloseCommand() =
        if (version >= 0x0100) byteArrayOf(0x0D, session) else byteArrayOf(0x0D)

    private fun onControl(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        when (bytes[0].toInt() and 0xFF) {
            OP_CAPABILITIES -> {
                parseCapabilities(bytes)
                retryCount = 0
                report(State.READY)
            }
            OP_MIC_BUTTON -> {
                micRequested = true
                listener.onMicRequested()
                enqueue { writeTx(micOpenCommand()) }
            }
            OP_AUDIO_START -> {
                if (bytes.size >= 3) codec = bytes[2].toInt() and 0xFF
                session = if (bytes.size >= 4) bytes[3] else 0.toByte()
                beginStream()
            }
            OP_AUDIO_STOP -> endStream()
            OP_AUDIO_SYNC -> if (bytes.size >= 7) {
                val predictor = (((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)).toShort().toInt()
                pendingSync = intArrayOf(predictor, bytes[6].toInt() and 0xFF)
                // A sync marks a frame boundary; drop any partial frame
                frameFill = 0
            }
        }
    }

    private fun parseCapabilities(bytes: ByteArray) {
        if (bytes.size < 7) return
        fun u8(i: Int) = bytes[i].toInt() and 0xFF
        version = (u8(1) shl 8) or u8(2)
        var codecs = if (version >= 0x0100) u8(3) else u8(4)
        // Some firmware reports the codec mask one byte later
        if (version >= 0x0100 && codecs == 0 && bytes.size >= 9 && u8(4) and 0x03 != 0) codecs = u8(4)
        codec = if (codecs and CODEC_ADPCM_16K != 0) CODEC_ADPCM_16K else CODEC_ADPCM_8K
        val size = (u8(5) shl 8) or u8(6)
        frameSize = if (size in 1..MAX_FRAME_SIZE) size else DEFAULT_FRAME_SIZE
        Log.i(TAG, "capabilities version=$version codec=$codec frame=$frameSize")
    }

    private fun beginStream() {
        frameFill = 0
        pendingSync = null
        decoder.reset()
        if (streaming) return
        streaming = true
        listener.onVoiceStart()
    }

    private fun endStream() {
        micRequested = false
        frameFill = 0
        pendingSync = null
        if (!streaming) return
        streaming = false
        listener.onVoiceStop()
    }

    private fun onAudio(bytes: ByteArray) {
        if (!streaming) {
            if (!micRequested) return
            beginStream()
        }
        var offset = 0
        while (offset < bytes.size) {
            val take = minOf(frameSize - frameFill, bytes.size - offset)
            System.arraycopy(bytes, offset, frame, frameFill, take)
            frameFill += take
            offset += take
            if (frameFill == frameSize) {
                pendingSync?.let { decoder.reset(it[0], it[1]) }
                pendingSync = null
                val pcm = smooth(decoder.decode(frame, 0, frameSize))
                listener.onVoiceAudio(if (codec == CODEC_ADPCM_8K) upsample(pcm) else pcm)
                frameFill = 0
            }
        }
    }

    /** Light 1-2-1 low-pass that removes ADPCM hiss. */
    private fun smooth(input: ShortArray): ShortArray {
        if (input.size < 3) return input
        val out = input.copyOf()
        for (i in 1 until input.size - 1) {
            out[i] = ((input[i - 1] + 2 * input[i] + input[i + 1]) shr 2).toShort()
        }
        return out
    }

    /** 8 kHz to 16 kHz by linear interpolation, for remotes without the 16 kHz codec. */
    private fun upsample(input: ShortArray): ShortArray {
        val out = ShortArray(input.size * 2)
        for (i in input.indices) {
            val next = if (i + 1 < input.size) input[i + 1].toInt() else input[i].toInt()
            out[2 * i] = input[i]
            out[2 * i + 1] = ((input[i] + next) / 2).toShort()
        }
        return out
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            worker.post {
                if (g != gatt) {
                    runCatching { g.close() }
                    return@post
                }
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    // A larger MTU carries whole audio frames per notification
                    if (!g.requestMtu(REQUESTED_MTU)) discoverServices(g)
                    else worker.postDelayed({ discoverServices(g) }, MTU_FALLBACK_MS)
                } else {
                    teardown()
                    report(State.CONNECTING)
                    scheduleRetry()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            worker.post { discoverServices(g) }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            worker.post { if (g == gatt) setUp(g) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            worker.post { operationDone() }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            worker.post { operationDone() }
        }

        @Deprecated("Still the only callback on Android 12 and older")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            @Suppress("DEPRECATION")
            val value = characteristic.value?.copyOf()
            worker.post {
                if (characteristic.uuid == MODEL_NUMBER && value != null) {
                    val model = String(value, Charsets.UTF_8).trim().uppercase()
                    // Old ARN9 firmware packs ADPCM low nibble first
                    decoder.lowNibbleFirst = model.contains("ARN9")
                    Log.i(TAG, "remote model $model")
                }
                operationDone()
            }
        }

        @Deprecated("Still the only callback on Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value?.copyOf() ?: return
            val uuid = characteristic.uuid
            worker.post {
                when (uuid) {
                    CONTROL -> onControl(value)
                    AUDIO -> onAudio(value)
                }
            }
        }
    }

    companion object {
        private const val TAG = "AtvvRemote"

        val SERVICE: UUID = UUID.fromString("ab5e0001-5a21-4f05-bc7d-af01f617b664")
        val TRANSMIT: UUID = UUID.fromString("ab5e0002-5a21-4f05-bc7d-af01f617b664")
        val AUDIO: UUID = UUID.fromString("ab5e0003-5a21-4f05-bc7d-af01f617b664")
        val CONTROL: UUID = UUID.fromString("ab5e0004-5a21-4f05-bc7d-af01f617b664")
        private val CLIENT_CONFIG: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val DEVICE_INFORMATION: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
        private val MODEL_NUMBER: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")

        /** GET_CAPS, protocol 1.0, both ADPCM codecs, all interaction models. */
        private val GET_CAPABILITIES = byteArrayOf(0x0A, 0x01, 0x00, 0x00, 0x03, 0x03)

        private const val OP_AUDIO_STOP = 0x00
        private const val OP_AUDIO_START = 0x04
        private const val OP_MIC_BUTTON = 0x08
        private const val OP_AUDIO_SYNC = 0x0A
        private const val OP_CAPABILITIES = 0x0B

        private const val CODEC_ADPCM_8K = 0x01
        private const val CODEC_ADPCM_16K = 0x02
        private const val DEFAULT_FRAME_SIZE = 120
        private const val MAX_FRAME_SIZE = 512

        private const val REQUESTED_MTU = 247
        private const val MTU_FALLBACK_MS = 1500L
        private const val OPERATION_TIMEOUT_MS = 3000L
        private const val BASE_RETRY_MS = 2000L
        private const val MAX_RETRY_MS = 30000L

        private val REMOTE_NAMES = listOf(
            "mi rc", "xiaomi", "小米", "arn9", "rc001", "rc003", "voice remote"
        )

        fun isXiaomiRemote(name: String?): Boolean {
            val lower = name?.lowercase() ?: return false
            return REMOTE_NAMES.any { lower.contains(it) }
        }
    }
}

/** Standard IMA ADPCM decoder, 4 bits per sample. */
class ImaAdpcmDecoder {
    var lowNibbleFirst = false
    private var predictor = 0
    private var index = 0

    fun reset(predictor: Int = 0, index: Int = 0) {
        this.predictor = predictor.coerceIn(-32768, 32767)
        this.index = index.coerceIn(0, STEPS.size - 1)
    }

    fun decode(data: ByteArray, offset: Int, length: Int): ShortArray {
        val out = ShortArray(length * 2)
        var o = 0
        for (i in offset until offset + length) {
            val b = data[i].toInt() and 0xFF
            val high = b shr 4
            val low = b and 0x0F
            out[o++] = decodeNibble(if (lowNibbleFirst) low else high)
            out[o++] = decodeNibble(if (lowNibbleFirst) high else low)
        }
        return out
    }

    private fun decodeNibble(nibble: Int): Short {
        val step = STEPS[index]
        var diff = step shr 3
        if (nibble and 4 != 0) diff += step
        if (nibble and 2 != 0) diff += step shr 1
        if (nibble and 1 != 0) diff += step shr 2
        predictor = (if (nibble and 8 != 0) predictor - diff else predictor + diff).coerceIn(-32768, 32767)
        index = (index + INDEX_ADJUST[nibble and 7]).coerceIn(0, STEPS.size - 1)
        return predictor.toShort()
    }

    companion object {
        private val INDEX_ADJUST = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)
        val STEPS = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
            50, 55, 60, 66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230,
            253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963,
            1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327,
            3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487,
            12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
        )
    }
}
