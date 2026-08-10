package go.examate.hyp.meschat

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * BLE Mesh 传输层 — 参照 bitchat 的 Mesh 设计实现。
 *
 * 职责:
 * 1. BLE 广播(Advertiser) + 扫描(Scanner):发现附近设备
 * 2. GATT Server + Client:双向 GATT 连接,收发消息
 * 3. 消息分片与重组:处理大于 MTU 的消息
 * 4. 连接管理:维护已连接的 peer 列表
 *
 * 消息分片格式(二进制,每片):
 * - msgHash(4B, Int): 消息内容 SHA-256 前 4 字节
 * - totalFragments(2B, Short): 总片数
 * - fragmentIndex(2B, Short): 当前片号(0-indexed)
 * - data(剩余): 当前片数据
 *
 * 上层通过 [Callback] 接收事件,通过 [sendMessage] 发送数据。
 */
class BleMeshTransport(
    private val context: Context,
    private val callback: Callback
) {
    companion object {
        private const val TAG = "BleMeshTransport"
        private val SERVICE_UUID = UUID.fromString("0000b1c0-0000-1000-8000-00805f9b34fb")
        private val MSG_CHAR_UUID = UUID.fromString("0000b1c1-0000-1000-8000-00805f9b34fb")
        private val CCC_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val FRAGMENT_HEADER_SIZE = 8
        private const val DEFAULT_FRAGMENT_DATA_SIZE = 200
        private const val MAX_MTU = 517
        private const val MIN_MTU = 23
    }

    /** 传输层回调(主线程触发) */
    interface Callback {
        fun onPeerConnected(peerId: String, nickname: String)
        fun onPeerDisconnected(peerId: String)
        fun onDataReceived(json: String, fromPeerId: String)
        fun onError(error: String)
    }

    // ═══ 核心字段 ═══
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    /** 本机 Peer ID(8 字符随机 hex) */
    val myPeerId: String = UUID.randomUUID().toString().replace("-", "").take(8)

    /** 连接表:deviceAddress -> GattConnection */
    private val connections = ConcurrentHashMap<String, GattConnection>()

    /** Peer ID -> deviceAddress 映射 */
    private val peerIdToAddress = ConcurrentHashMap<String, String>()

    /** 分片重组缓冲:msgHashHex -> (index -> data) */
    private val reassemblyBuffers = ConcurrentHashMap<String, MutableMap<Int, ByteArray>>()

    /** 分片元数据:msgHashHex -> Triple(totalFragments, fromPeerId, createTimeMs) */
    private val reassemblyMeta = ConcurrentHashMap<String, Triple<Int, String, Long>>()

    /** 分片重组超时时间(毫秒),超时未收齐的分片缓冲将被清理,避免内存泄漏 */
    private val reassemblyTimeoutMs = 30_000L

    /** 已连接过的 device address(避免重复连接) */
    private val connectedAddresses = ConcurrentHashMap.newKeySet<String>()

    /** 已向对端订阅 notification 的 device address 集合(服务端侧使用) */
    private val notifiedAddresses = ConcurrentHashMap.newKeySet<String>()

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    @Volatile private var isRunning = false
    @Volatile private var isAdvertising = false
    @Volatile private var isScanning = false

    /**
     * 单线程发送执行器:BLE GATT 写操作必须严格串行(同一连接上并发 write 会返回 false 或丢数据),
     * 用单线程执行器保证所有出站分片按顺序提交。
     */
    private var sendExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "BleMeshTransport-sender").apply { isDaemon = true }
    }

    /** 单个连接的上下文 */
    private data class GattConnection(
        val gatt: BluetoothGatt?,       // 服务端侧为 null
        val device: BluetoothDevice,
        val address: String,
        var peerId: String? = null,
        var nickname: String? = null,
        var maxFragmentDataSize: Int = DEFAULT_FRAGMENT_DATA_SIZE,
        val isServerSide: Boolean
    )

    // ════════════════════════════════════════
    //  生命周期
    // ════════════════════════════════════════

    /**
     * 启动 BLE 传输。
     *
     * 预检失败(权限缺失/蓝牙未开启)时静默返回 false,不调用 [callback.onError],
     * 由上层 [MesChatController] 决定是否提示用户(实现 BLE/Wi-Fi Aware 自动降级)。
     * 仅运行时错误(如 GATT Server 启动失败、广播失败)才通过 [callback.onError] 上报。
     *
     * @return true=已成功启动(或此前已运行);false=预检失败未启动
     */
    fun start(): Boolean {
        if (isRunning) return true
        if (!hasPermissions()) return false
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return false
        // 重建发送执行器(上次 stop 可能已 shutdown)
        if (sendExecutor.isShutdown) {
            sendExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "BleMeshTransport-sender").apply { isDaemon = true }
            }
        }
        isRunning = true
        startGattServer()
        startAdvertising()
        startScanning()
        return true
    }

    /** 检查 BLE 通道是否可用(权限已授 + 蓝牙已开),不实际启动 */
    fun isAvailable(): Boolean {
        if (!hasPermissions()) return false
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) return false
        return true
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        stopScanning()
        stopAdvertising()
        // 关闭所有连接
        connections.values.toList().forEach { conn ->
            try {
                conn.gatt?.disconnect()
                conn.gatt?.close()
            } catch (e: Exception) {
                Log.w(TAG, "close gatt error", e)
            }
        }
        connections.clear()
        peerIdToAddress.clear()
        connectedAddresses.clear()
        notifiedAddresses.clear()
        reassemblyBuffers.clear()
        reassemblyMeta.clear()
        try {
            gattServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close gattServer error", e)
        }
        gattServer = null
        // 关闭发送执行器,释放线程
        sendExecutor.shutdownNow()
    }

    /** 是否正在运行 */
    fun isRunning(): Boolean = isRunning

    /** 获取已连接的 peer 列表 */
    fun getConnectedPeers(): List<Pair<String, String>> {
        return connections.values
            .filter { it.peerId != null }
            .map { it.peerId!! to (it.nickname ?: "未知") }
    }

    // ════════════════════════════════════════
    //  权限检查
    // ════════════════════════════════════════

    private fun hasPermissions(): Boolean {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        return perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    // ════════════════════════════════════════
    //  GATT Server
    // ════════════════════════════════════════

    private fun startGattServer() {
        try {
            gattServer = bluetoothManager?.openGattServer(context, gattServerCallback)
            val service = BluetoothGattService(
                SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )
            val char = BluetoothGattCharacteristic(
                MSG_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            val descriptor = BluetoothGattDescriptor(
                CCC_UUID,
                BluetoothGattDescriptor.PERMISSION_WRITE or
                    BluetoothGattDescriptor.PERMISSION_READ
            )
            char.addDescriptor(descriptor)
            service.addCharacteristic(char)
            gattServer?.addService(service)
            Log.d(TAG, "GATT Server started, peerId=$myPeerId")
        } catch (e: Exception) {
            Log.e(TAG, "startGattServer failed", e)
            callback.onError("GATT Server 启动失败: ${e.message}")
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            mainHandler.post {
                val address = device.address
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        // 服务端被动连接:创建 GattConnection(isServerSide=true)
                        Log.d(TAG, "GATT Server: device connected $address")
                        connectedAddresses.add(address)
                        if (!connections.containsKey(address)) {
                            connections[address] = GattConnection(
                                gatt = null,
                                device = device,
                                address = address,
                                isServerSide = true
                            )
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "GATT Server: device disconnected $address")
                        removeConnection(address)
                    }
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, byteArrayOf())
            }
            // 处理收到的分片
            handleIncomingFragment(value, device.address)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            }
            // 记录对端是否订阅了 notification,服务端 notify 前据此判断
            if (descriptor.uuid == CCC_UUID) {
                val address = device.address
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
                ) {
                    notifiedAddresses.add(address)
                    Log.d(TAG, "Peer $address subscribed notifications")
                } else {
                    notifiedAddresses.remove(address)
                    Log.d(TAG, "Peer $address unsubscribed notifications")
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mainHandler.post {
                connections[device.address]?.maxFragmentDataSize =
                    (mtu - 3 - FRAGMENT_HEADER_SIZE).coerceAtLeast(17)
                Log.d(TAG, "GATT Server MTU changed: $mtu for ${device.address}")
            }
        }
    }

    // ════════════════════════════════════════
    //  Advertiser
    // ════════════════════════════════════════

    private fun startAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "BluetoothLeAdvertiser not available")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0) // 持续广播
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
            isAdvertising = true
            Log.d(TAG, "Advertising started")
        } catch (e: Exception) {
            Log.e(TAG, "startAdvertising failed", e)
        }
    }

    private fun stopAdvertising() {
        if (isAdvertising && advertiser != null) {
            try {
                advertiser?.stopAdvertising(advertiseCallback)
            } catch (e: Exception) {
                Log.w(TAG, "stopAdvertising error", e)
            }
        }
        isAdvertising = false
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "Advertise started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertise failed: $errorCode")
            callback.onError("蓝牙广播启动失败(错误码 $errorCode)")
        }
    }

    // ════════════════════════════════════════
    //  Scanner
    // ════════════════════════════════════════

    private fun startScanning() {
        scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.w(TAG, "BluetoothLeScanner not available")
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            scanner?.startScan(listOf(filter), settings, scanCallback)
            isScanning = true
            Log.d(TAG, "Scanning started")
        } catch (e: Exception) {
            Log.e(TAG, "startScanning failed", e)
        }
    }

    private fun stopScanning() {
        if (isScanning && scanner != null) {
            try {
                scanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.w(TAG, "stopScan error", e)
            }
        }
        isScanning = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address
            // 避免重复连接
            if (connectedAddresses.contains(address)) return
            if (connections.containsKey(address)) return
            mainHandler.post {
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
        }
    }

    // ════════════════════════════════════════
    //  GATT Client — 主动连接
    // ════════════════════════════════════════

    private fun connectToDevice(device: BluetoothDevice) {
        val address = device.address
        if (connectedAddresses.contains(address) || connections.containsKey(address)) return
        connectedAddresses.add(address)

        Log.d(TAG, "Connecting to $address")
        val gattCallback = createGattClientCallback(device)
        val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        // GATT Client 连接
        connections[address] = GattConnection(
            gatt = gatt,
            device = device,
            address = address,
            isServerSide = false
        )
    }

    private fun createGattClientCallback(device: BluetoothDevice): BluetoothGattCallback {
        val address = device.address
        return object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                mainHandler.post {
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.d(TAG, "GATT Client connected to $address, requesting MTU")
                            gatt.requestMtu(MAX_MTU)
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.d(TAG, "GATT Client disconnected from $address (status=$status)")
                            removeConnection(address)
                        }
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                mainHandler.post {
                    val conn = connections[address]
                    if (conn != null) {
                        conn.maxFragmentDataSize = (mtu - 3 - FRAGMENT_HEADER_SIZE).coerceAtLeast(17)
                        Log.d(TAG, "MTU negotiated: $mtu, fragmentDataSize=${conn.maxFragmentDataSize}")
                    }
                    gatt.discoverServices()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                mainHandler.post {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "Service discovery failed for $address: $status")
                        return@post
                    }
                    // enable notifications
                    val service = gatt.getService(SERVICE_UUID)
                    val char = service?.getCharacteristic(MSG_CHAR_UUID)
                    if (char != null) {
                        gatt.setCharacteristicNotification(char, true)
                        val descriptor = char.getDescriptor(CCC_UUID)
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                        }
                    }
                    // 发送 hello
                    sendHello(address)
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                val data = characteristic.value ?: return
                handleIncomingFragment(data, address)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "onCharacteristicWrite failed to $address: status=$status")
                }
            }
        }
    }

    // ════════════════════════════════════════
    //  消息发送
    // ════════════════════════════════════════

    /**
     * 发送数据(JSON 字符串)给指定 peer 或所有 peer。
     *
     * @param json JSON 数据
     * @param targetPeerId 目标 peerId,null=广播给所有连接
     */
    fun sendMessage(json: String, targetPeerId: String? = null) {
        val data = json.toByteArray(Charsets.UTF_8)
        val targets = if (targetPeerId != null) {
            val addr = peerIdToAddress[targetPeerId]
            if (addr != null) listOfNotNull(connections[addr]) else emptyList()
        } else {
            connections.values.toList()
        }
        if (targets.isEmpty()) return
        // 通过单线程执行器串行发送,避免并发 GATT 写
        sendExecutor.execute {
            targets.forEach { conn ->
                sendFragmentedData(data, conn)
            }
        }
    }

    /** 发送 hello 消息(建立连接后交换身份) */
    private fun sendHello(address: String) {
        val conn = connections[address] ?: return
        val helloJson = """{"_type":"hello","peerId":"$myPeerId"}"""
        val data = helloJson.toByteArray(Charsets.UTF_8)
        sendExecutor.execute {
            sendFragmentedData(data, conn)
        }
    }

    /** 分片发送数据(在后台线程执行) */
    private fun sendFragmentedData(data: ByteArray, conn: GattConnection) {
        val msgHash = computeMsgHash(data)
        val fragDataSize = conn.maxFragmentDataSize
        val totalFragments = (data.size + fragDataSize - 1) / fragDataSize
        if (totalFragments > 65535) {
            Log.e(TAG, "Message too large: ${data.size} bytes")
            return
        }

        var consecutiveFail = 0
        for (i in 0 until totalFragments) {
            val offset = i * fragDataSize
            val end = minOf(offset + fragDataSize, data.size)
            val fragmentData = data.copyOfRange(offset, end)
            val packet = ByteBuffer.allocate(FRAGMENT_HEADER_SIZE + fragmentData.size)
                .putInt(msgHash)
                .putShort(totalFragments.toShort())
                .putShort(i.toShort())
                .put(fragmentData)
                .array()
            val ok = writePacket(packet, conn)
            if (!ok) {
                consecutiveFail++
                // 连续失败 3 次认为连接异常,放弃本条消息后续分片
                if (consecutiveFail >= 3) {
                    Log.e(TAG, "Aborting fragmented send to ${conn.address}: repeated write failures")
                    return
                }
            } else {
                consecutiveFail = 0
            }
            // 短暂延迟,避免 BLE 队列溢出
            if (totalFragments > 1) {
                try {
                    Thread.sleep(20)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    /** 通过 GATT 写入数据包。返回是否提交成功(不代表对端已收到)。 */
    private fun writePacket(packet: ByteArray, conn: GattConnection): Boolean {
        var ok = false
        try {
            if (conn.isServerSide) {
                // 服务端通过 notify 发送:仅当对端已订阅 CCC 时才发送,否则会静默失败
                if (!notifiedAddresses.contains(conn.address)) {
                    Log.w(TAG, "skip notify to ${conn.address}: peer not subscribed")
                    return false
                }
                val server = gattServer ?: return false
                val service = server.getService(SERVICE_UUID)
                val char = service?.getCharacteristic(MSG_CHAR_UUID) ?: return false
                char.value = packet
                ok = server.notifyCharacteristicChanged(conn.device, char, false)
                if (!ok) {
                    Log.w(TAG, "notifyCharacteristicChanged returned false to ${conn.address}")
                }
            } else {
                // 客户端通过 write 发送
                val gatt = conn.gatt ?: return false
                val service = gatt.getService(SERVICE_UUID)
                val char = service?.getCharacteristic(MSG_CHAR_UUID) ?: return false
                char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                char.value = packet
                ok = gatt.writeCharacteristic(char)
                if (!ok) {
                    Log.w(TAG, "writeCharacteristic returned false to ${conn.address}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "writePacket error to ${conn.address}", e)
        }
        return ok
    }

    // ════════════════════════════════════════
    //  分片接收与重组
    // ════════════════════════════════════════

    private fun handleIncomingFragment(packet: ByteArray, fromAddress: String) {
        if (packet.size < FRAGMENT_HEADER_SIZE) {
            Log.w(TAG, "Fragment too small: ${packet.size}")
            return
        }
        val buffer = ByteBuffer.wrap(packet)
        val msgHash = buffer.int
        val totalFragments = buffer.short.toInt() and 0xFFFF
        val fragmentIndex = buffer.short.toInt() and 0xFFFF
        val fragmentData = ByteArray(packet.size - FRAGMENT_HEADER_SIZE)
        buffer.get(fragmentData)

        if (totalFragments == 0 || fragmentIndex < 0 || fragmentIndex >= totalFragments) {
            Log.w(TAG, "Invalid fragment: total=$totalFragments idx=$fragmentIndex")
            return
        }

        val hashKey = msgHash.toString()
        val conn = connections[fromAddress]
        val fromPeerId = conn?.peerId ?: fromAddress

        // 顺便清理超时的重组缓冲,避免不完整消息长期占用内存
        sweepStaleReassembly()

        // 使用 computeIfAbsent 保证原子性:多线程并发到达同一条消息的分片时不会丢片
        val fragMap = reassemblyBuffers.computeIfAbsent(hashKey) { ConcurrentHashMap() }
        fragMap[fragmentIndex] = fragmentData
        // 记录/刷新元数据(总数、来源、时间)
        reassemblyMeta[hashKey] = Triple(totalFragments, fromPeerId, System.currentTimeMillis())

        // 检查是否收齐(用当前已知总数判断)
        if (fragMap.size >= totalFragments) {
            // 仅由一个线程完成重组:remove 返回非 null 者负责重组
            val taken = reassemblyBuffers.remove(hashKey)
            if (taken != null) {
                reassemblyMeta.remove(hashKey)
                // 校验完整性后再重组
                val baos = java.io.ByteArrayOutputStream()
                var complete = true
                for (i in 0 until totalFragments) {
                    val frag = taken[i]
                    if (frag == null) {
                        complete = false
                        break
                    }
                    baos.write(frag)
                }
                if (!complete) {
                    Log.w(TAG, "Reassembly incomplete for $hashKey despite count match")
                    return
                }
                val json = baos.toString("UTF-8")
                processReceivedJson(json, fromAddress, fromPeerId)
            }
        }
    }

    /** 清理超时未收齐的分片重组缓冲,防止内存泄漏 */
    private fun sweepStaleReassembly() {
        if (reassemblyMeta.isEmpty()) return
        val now = System.currentTimeMillis()
        val staleKeys = reassemblyMeta.entries
            .filter { now - it.value.third > reassemblyTimeoutMs }
            .map { it.key }
        if (staleKeys.isEmpty()) return
        staleKeys.forEach { key ->
            reassemblyBuffers.remove(key)
            reassemblyMeta.remove(key)
            Log.w(TAG, "Swept stale reassembly buffer: $key")
        }
    }

    /** 处理重组完成的 JSON */
    private fun processReceivedJson(json: String, fromAddress: String, fromPeerId: String) {
        try {
            val obj = org.json.JSONObject(json)
            val type = obj.optString("_type", "")
            if (type == "hello") {
                // hello 消息:注册 peer
                val peerId = obj.optString("peerId", "")
                if (peerId.isNotBlank()) {
                    val conn = connections[fromAddress]
                    if (conn != null) {
                        val isFirstHello = conn.peerId == null
                        conn.peerId = peerId
                        peerIdToAddress[peerId] = fromAddress
                        // 使用 peerId 作为昵称(后续可通过 announce 更新)
                        conn.nickname = peerId
                        if (isFirstHello) {
                            mainHandler.post {
                                callback.onPeerConnected(peerId, peerId)
                            }
                        }
                        // Bug A 修复:服务端(被动连接方)收到客户端 hello 后,
                        // 必须回复自己的 hello,否则客户端永远不知道服务端的 peerId。
                        // 客户端(主动连接方)已在 onServicesDiscovered 发送过 hello,无需重复。
                        if (conn.isServerSide) {
                            sendHello(fromAddress)
                        }
                    }
                }
            } else {
                // 正常消息
                mainHandler.post {
                    callback.onDataReceived(json, fromPeerId)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "processReceivedJson error", e)
        }
    }

    // ════════════════════════════════════════
    //  连接管理
    // ════════════════════════════════════════

    private fun removeConnection(address: String) {
        val conn = connections.remove(address)
        notifiedAddresses.remove(address)
        conn?.let { c ->
            try {
                c.gatt?.disconnect()
                c.gatt?.close()
            } catch (e: Exception) {
                Log.w(TAG, "disconnect error", e)
            }
            val peerId = c.peerId
            if (peerId != null) {
                peerIdToAddress.remove(peerId)
                mainHandler.post {
                    callback.onPeerDisconnected(peerId)
                }
            }
        }
    }

    // ════════════════════════════════════════
    //  工具
    // ════════════════════════════════════════

    private fun computeMsgHash(data: ByteArray): Int {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return ByteBuffer.wrap(hash, 0, 4).int
    }
}
