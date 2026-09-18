package com.example.btautoconnect

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * 車のオーディオ(A2DP/HFP)など、ペアリング済みBluetoothデバイスの
 * 状態表示・接続・自動再接続を行うアプリ。
 *
 * ★重要な制約★
 * BluetoothA2dp.connect() / BluetoothHeadset.connect() は隠しAPIであり、
 * 通常の(署名なし・root化していない)アプリはこれらを直接呼び出す権限を
 * 持っていません。本アプリではリフレクション経由で「試す」実装をしていますが、
 * メーカー・Androidバージョンによっては SecurityException で失敗します。
 * 失敗した場合はシステムのBluetooth設定画面を開いて手動接続を促します。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var textTarget: TextView
    private lateinit var textStatus: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnOpenSettings: Button
    private lateinit var switchAutoReconnect: android.widget.Switch
    private lateinit var containerDeviceList: LinearLayout

    private var targetAddress: String? = null
    private var a2dpProxy: BluetoothProfile? = null
    private var headsetProxy: BluetoothProfile? = null

    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val reconnectIntervalMs = 5000L

    companion object {
        private const val PREFS_NAME = "bt_auto_connect_prefs"
        private const val KEY_TARGET_ADDRESS = "target_address"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val REQUEST_BT_PERMISSIONS = 100
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = proxy
                BluetoothProfile.HEADSET -> headsetProxy = proxy
            }
            refreshStatus()
        }

        override fun onServiceDisconnected(profile: Int) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = null
                BluetoothProfile.HEADSET -> headsetProxy = null
            }
        }
    }

    private val aclReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            if (device?.address != targetAddress) return

            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    reconnectAttempts = 0
                    refreshStatus()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    refreshStatus()
                    if (switchAutoReconnect.isChecked) {
                        scheduleReconnect()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Toast.makeText(this, "この端末はBluetoothに対応していません", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        bluetoothAdapter = adapter

        textTarget = findViewById(R.id.textTarget)
        textStatus = findViewById(R.id.textStatus)
        btnConnect = findViewById(R.id.btnConnect)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)
        switchAutoReconnect = findViewById(R.id.switchAutoReconnect)
        containerDeviceList = findViewById(R.id.containerDeviceList)

        targetAddress = prefs.getString(KEY_TARGET_ADDRESS, null)
        switchAutoReconnect.isChecked = prefs.getBoolean(KEY_AUTO_RECONNECT, false)
        switchAutoReconnect.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_AUTO_RECONNECT, checked).apply()
        }

        btnConnect.setOnClickListener { attemptConnect() }
        btnOpenSettings.setOnClickListener { openBluetoothSettings() }

        registerReceiver(
            aclReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
        )

        ensurePermissionsThen {
            bluetoothAdapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)
            bluetoothAdapter.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET)
            loadBondedDevices()
            refreshStatus()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(aclReceiver)
        a2dpProxy?.let { bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, it) }
        headsetProxy?.let { bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        reconnectHandler.removeCallbacksAndMessages(null)
    }

    // ---------- 権限 ----------

    private fun hasConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermissionsThen(action: () -> Unit) {
        if (hasConnectPermission()) {
            action()
            return
        }
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_BT_PERMISSIONS
        )
        // 許可待ち。onRequestPermissionsResult で再度呼び出す。
        pendingAfterPermission = action
    }

    private var pendingAfterPermission: (() -> Unit)? = null

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_BT_PERMISSIONS &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            pendingAfterPermission?.invoke()
        } else {
            Toast.makeText(this, "Bluetooth権限がないと動作しません", Toast.LENGTH_LONG).show()
        }
        pendingAfterPermission = null
    }

    // ---------- デバイス一覧 ----------

    @SuppressWarnings("MissingPermission")
    private fun loadBondedDevices() {
        if (!hasConnectPermission()) return
        containerDeviceList.removeAllViews()
        val bonded = bluetoothAdapter.bondedDevices ?: emptySet()

        if (bonded.isEmpty()) {
            val tv = TextView(this)
            tv.text = "ペアリング済みデバイスがありません。先に端末のBluetooth設定でペアリングしてください。"
            containerDeviceList.addView(tv)
            return
        }

        for (device in bonded) {
            val btn = Button(this)
            val name = device.name ?: device.address
            btn.text = if (device.address == targetAddress) "★ $name (対象)" else name
            btn.setOnClickListener {
                targetAddress = device.address
                prefs.edit().putString(KEY_TARGET_ADDRESS, device.address).apply()
                loadBondedDevices()
                refreshTargetLabel()
                refreshStatus()
            }
            containerDeviceList.addView(btn)
        }
    }

    private fun refreshTargetLabel() {
        val addr = targetAddress
        if (addr == null) {
            textTarget.text = "対象デバイス: 未選択"
            return
        }
        val device = findBondedDevice(addr)
        textTarget.text = "対象デバイス: ${device?.name ?: addr}"
    }

    @SuppressWarnings("MissingPermission")
    private fun findBondedDevice(address: String): BluetoothDevice? {
        if (!hasConnectPermission()) return null
        return bluetoothAdapter.bondedDevices?.firstOrNull { it.address == address }
    }

    // ---------- 状態表示 ----------

    @SuppressWarnings("MissingPermission")
    private fun refreshStatus() {
        refreshTargetLabel()
        val addr = targetAddress
        if (addr == null) {
            textStatus.text = "状態: 対象デバイス未選択"
            return
        }
        if (!hasConnectPermission()) {
            textStatus.text = "状態: 権限待ち"
            return
        }
        val device = findBondedDevice(addr)
        val connectedViaA2dp = a2dpProxy?.connectedDevices?.any { it.address == addr } ?: false
        val connectedViaHeadset = headsetProxy?.connectedDevices?.any { it.address == addr } ?: false
        textStatus.text = if (connectedViaA2dp || connectedViaHeadset) {
            "状態: 接続中"
        } else if (device != null) {
            "状態: 未接続"
        } else {
            "状態: デバイス不明"
        }
    }

    // ---------- 接続 ----------

    @SuppressWarnings("MissingPermission")
    private fun attemptConnect() {
        val addr = targetAddress
        if (addr == null) {
            Toast.makeText(this, "先にデバイスを選択してください", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasConnectPermission()) {
            ensurePermissionsThen { attemptConnect() }
            return
        }
        val device = findBondedDevice(addr)
        if (device == null) {
            Toast.makeText(this, "デバイスが見つかりません", Toast.LENGTH_SHORT).show()
            return
        }

        val a2dpOk = tryHiddenConnect(a2dpProxy, device)
        val headsetOk = tryHiddenConnect(headsetProxy, device)

        if (a2dpOk || headsetOk) {
            Toast.makeText(this, "接続要求を送信しました", Toast.LENGTH_SHORT).show()
            reconnectHandler.postDelayed({ refreshStatus() }, 1500)
        } else {
            Toast.makeText(
                this,
                "この端末では自動接続できませんでした。設定画面から手動で接続してください",
                Toast.LENGTH_LONG
            ).show()
            openBluetoothSettings()
        }
    }

    /**
     * BluetoothProfile(A2DP/HEADSET)の隠しメソッド connect(BluetoothDevice) を
     * リフレクションで呼び出す。多くの端末では SecurityException で失敗する。
     */
    private fun tryHiddenConnect(proxy: BluetoothProfile?, device: BluetoothDevice): Boolean {
        if (proxy == null) return false
        return try {
            val method = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
            val result = method.invoke(proxy, device)
            (result as? Boolean) ?: true
        } catch (e: Exception) {
            false
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) return
        reconnectAttempts++
        reconnectHandler.postDelayed({ attemptConnect() }, reconnectIntervalMs)
    }

    private fun openBluetoothSettings() {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }
}
