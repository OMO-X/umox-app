package com.umox.app.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import com.umox.app.config.AppConfig

class WifiP2pProbe(
    private val context: Context,
    private val onPeersDiscovered: (List<WifiP2pDevice>) -> Unit,
    private val onConnectionInfo: (WifiP2pInfo, WifiP2pGroup?) -> Unit,
    private val onLog: (String) -> Unit
) {
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    // Target MAC address speedometer (dapat disesuaikan via AppConfig atau runtime parameter)
    var targetMac: String = AppConfig.targetP2pMac

    init {
        manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = manager?.initialize(context, context.mainLooper, null)
    }

    var isGroupConnected: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    fun checkConnectionState() {
        manager?.requestConnectionInfo(channel) { info ->
            if (info != null && info.groupFormed) {
                isGroupConnected = true
                manager?.requestGroupInfo(channel) { group ->
                    val groupOwnerIp = info.groupOwnerAddress?.hostAddress ?: "192.168.2.1"
                    onLog("✔ TERDETEKSI WI-FI P2P AKTIF: ${group?.networkName ?: "Group P2P"} (IP: $groupOwnerIp)")
                    onConnectionInfo(info, group)
                }
            } else {
                isGroupConnected = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun registerAndListen() {
        if (manager == null || channel == null) {
            onLog("Wi-Fi Direct (P2P) tidak didukung pada perangkat ini.")
            return
        }

        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        receiver = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        val isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        onLog("Status Wi-Fi Direct: ${if (isEnabled) "AKTIF" else "NONAKTIF"}")
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        manager?.requestPeers(channel) { peers ->
                            val deviceList = peers.deviceList.toList()
                            onLog("Ditemukan ${deviceList.size} perangkat Wi-Fi P2P di sekitar.")

                            var targetFound: WifiP2pDevice? = null
                            for (dev in deviceList) {
                                val isMotor = (targetMac.isNotBlank() && dev.deviceAddress.equals(targetMac, ignoreCase = true)) ||
                                        AppConfig.matchesTargetP2pMac(dev.deviceAddress) ||
                                        dev.deviceName.contains("OMO", ignoreCase = true) ||
                                        dev.deviceName.contains("DIRECT-OMO", ignoreCase = true) ||
                                        AppConfig.matchesCandidateName(dev.deviceName)

                                onLog("• P2P Peer: ${dev.deviceName} (${dev.deviceAddress}) [${dev.status}] ${if (isMotor) "★ MOTOR OMOWAY!" else ""}")
                                if (isMotor && targetFound == null) {
                                    targetFound = dev
                                }
                            }
                            onPeersDiscovered(deviceList)

                            // Otomatis sambungkan jika target motor ditemukan DAN belum terhubung
                            if (!isGroupConnected) {
                                targetFound?.let { motor ->
                                    onLog("★ Menghubungkan otomatis ke ${motor.deviceName} (${motor.deviceAddress})...")
                                    connectToMotor(motor.deviceAddress)
                                }
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        if (networkInfo?.isConnected == true) {
                            isGroupConnected = true
                            onLog("✔ KONEKSI WI-FI DIRECT TERHUBUNG KE MOTOR!")
                            manager?.requestConnectionInfo(channel) { info ->
                                manager?.requestGroupInfo(channel) { group ->
                                    val groupOwnerIp = info.groupOwnerAddress?.hostAddress ?: "192.168.2.1"
                                    onLog("» IP Speedometer (Group Owner): $groupOwnerIp")
                                    if (group != null) {
                                        onLog("» Network P2P: ${group.networkName}")
                                    }
                                    onConnectionInfo(info, group)
                                }
                            }
                        } else {
                            isGroupConnected = false
                            onLog("Wi-Fi Direct terputus atau belum terhubung.")
                        }
                    }
                }
            }
        }

        context.registerReceiver(receiver, intentFilter)
        onLog("Listener Wi-Fi Direct aktif. Memeriksa status koneksi saat ini...")
        
        // Cek langsung apakah perangkat sudah terhubung dalam grup P2P sebelumnya
        checkConnectionState()

        discoverPeers()
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                onLog("Mencari sinyal Wi-Fi Direct motor (discoverPeers)...")
            }

            override fun onFailure(reasonCode: Int) {
                onLog("Gagal mencari peers Wi-Fi Direct (Kode error: $reasonCode).")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun connectToMotor(macAddress: String = targetMac) {
        if (isGroupConnected) {
            onLog("Sudah terhubung ke grup Wi-Fi Direct motor. Memperbarui informasi...")
            checkConnectionState()
            return
        }

        val config = WifiP2pConfig().apply {
            deviceAddress = macAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0 // HP bertindak sebagai Client (bukan Group Owner)
        }

        onLog("Mengirim permintaan koneksi ke $macAddress...")
        manager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                onLog("✔ Permintaan sambungan Wi-Fi Direct berhasil dikirim!")
            }

            override fun onFailure(reasonCode: Int) {
                onLog("Gagal menyambung ke Wi-Fi Direct motor (Kode error: $reasonCode).")
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun disconnectGroup() {
        manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                isGroupConnected = false
                onLog("Grup Wi-Fi Direct dilepas.")
            }

            override fun onFailure(reasonCode: Int) {
                onLog("Gagal melepas grup Wi-Fi Direct (Kode error: $reasonCode).")
            }
        })
    }

    fun unregister() {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                // Ignore if not registered
            }
            receiver = null
        }
    }
}
