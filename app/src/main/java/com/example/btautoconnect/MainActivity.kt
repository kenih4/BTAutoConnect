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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private lateinit var textBtPower: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnOpenSettings: Button
    private lateinit var btnRunTermuxScript: Button
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
        private const val KEY_HISTORY = "device_history"
        private const val REQUEST_BT_PERMISSIONS = 100
        private const val REQUEST_TERMUX_PERMISSION = 101
        private const val TERMUX_PACKAGE = "com.termux"
        private const val TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
        private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            when (profile) {
                BluetoothProfile.A2DP -> a2dpProxy = proxy
                BluetoothProfile.HEADSET -> headsetProxy = proxy
            }
            if (hasConnectPermission()) {
                @Suppress("MissingPermission")
                proxy.connectedDevices.forEach {
                    recordHistory(it.address, it.name, touchTime = true)
                }
                loadBondedDevices()
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
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                refreshStatus()
                return
            }
            val device: BluetoothDevice =intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                ?: return

            if (intent.action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                recordHistory(device.address, deviceNameOrNull(device), touchTime = true)
                loadBondedDevices()
            }
            if (device.address != targetAddress) return

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
        textBtPower = findViewById(R.id.textBtPower)
        btnConnect = findViewById(R.id.btnConnect)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)
        btnRunTermuxScript = findViewById(R.id.btnRunTermuxScript)
        switchAutoReconnect = findViewById(R.id.switchAutoReconnect)
        containerDeviceList = findViewById(R.id.containerDeviceList)

        targetAddress = prefs.getString(KEY_TARGET_ADDRESS, null)
        loadHistory().filter { it.lastConnected > 0 }.maxByOrNull { it.lastConnected }?.let {
            targetAddress = it.address
            prefs.edit().putString(KEY_TARGET_ADDRESS, it.address).apply()
        }
        switchAutoReconnect.isChecked = prefs.getBoolean(KEY_AUTO_RECONNECT, false)
        switchAutoReconnect.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_AUTO_RECONNECT, checked).apply()
        }

        btnConnect.setOnClickListener { attemptConnect() }
        btnOpenSettings.setOnClickListener { openBluetoothSettings() }
        btnRunTermuxScript.setOnClickListener { runTermuxScript() }

        registerReceiver(
            aclReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
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

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            reconnectHandler.postDelayed({ attemptConnect(promptEnable = false) }, 2000)
        } else {
            Toast.makeText(this, "Bluetoothがオンになりませんでした", Toast.LENGTH_SHORT).show()
        }
    }
    private var pendingAfterTermuxPermission: (() -> Unit)? = null

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_BT_PERMISSIONS -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    pendingAfterPermission?.invoke()
                } else {
                    Toast.makeText(this, "Bluetooth権限がないと動作しません", Toast.LENGTH_LONG).show()
                }
                pendingAfterPermission = null
            }
            REQUEST_TERMUX_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    pendingAfterTermuxPermission?.invoke()
                } else {
                    Toast.makeText(this, "Termux実行権限がないとスクリプトを実行できません", Toast.LENGTH_LONG).show()
                }
                pendingAfterTermuxPermission = null
            }
        }
    }

    // ---------- デバイス一覧 ----------

    @SuppressWarnings("MissingPermission")
    private fun loadBondedDevices() {
        if (!hasConnectPermission()) return
        containerDeviceList.removeAllViews()
        val bonded = bluetoothAdapter.bondedDevices ?: emptySet()
        val history = loadHistory()
        val historyByAddr = history.associateBy { it.address }
        val bondedAddrs = bonded.map { it.address }.toSet()

        val rows = bonded.map { d ->
            val h = historyByAddr[d.address]
            DeviceRow(d.address, d.name ?: h?.name ?: d.address, true, h?.lastConnected ?: 0L)
        } + history.filter { it.address !in bondedAddrs }
            .map { DeviceRow(it.address, it.name, false, it.lastConnected) }

        if (rows.isEmpty()) {
            val tv = TextView(this)
            tv.text = "ペアリング済み・接続履歴のデバイスがありません。先に端末のBluetooth設定でペアリングしてください。"
            containerDeviceList.addView(tv)
            return
        }

        val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        for (row in rows.sortedByDescending { it.lastConnected }) {
            val btn = Button(this)
            val marker = if (row.address == targetAddress) "★ " else ""
            val last = if (row.lastConnected > 0) {
                "最終接続: ${fmt.format(Date(row.lastConnected))}"
            } else {
                "接続履歴なし"
            }
            val unpaired = if (row.bonded) "" else " ・ペアリング解除済み(長押しで履歴削除)"
            btn.text = "$marker${row.name}\n$last$unpaired"
            btn.isAllCaps = false
            btn.setOnClickListener {
                targetAddress = row.address
                prefs.edit().putString(KEY_TARGET_ADDRESS, row.address).apply()
                recordHistory(row.address, row.name, touchTime = false)
                loadBondedDevices()
                refreshTargetLabel()
                refreshStatus()
            }
            if (!row.bonded) {
                btn.setOnLongClickListener {
                    removeHistory(row.address)
                    if (targetAddress == row.address) {
                        targetAddress = null
                        prefs.edit().remove(KEY_TARGET_ADDRESS).apply()
                    }
                    loadBondedDevices()
                    refreshStatus()
                    true
                }
            }
            containerDeviceList.addView(btn)
        }
    }

    private data class DeviceRow(
        val address: String,
        val name: String,
        val bonded: Boolean,
        val lastConnected: Long
    )

    private data class HistoryEntry(val address: String, val name: String, val lastConnected: Long)

    // ---------- 接続履歴 ----------

    private fun loadHistory(): MutableList<HistoryEntry> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(json)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val addr = o.getString("address")
                HistoryEntry(addr, o.optString("name", addr), o.optLong("last", 0L))
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun saveHistory(list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("address", it.address)
                    .put("name", it.name)
                    .put("last", it.lastConnected)
            )
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    private fun recordHistory(address: String, name: String?, touchTime: Boolean) {
        val list = loadHistory()
        val idx = list.indexOfFirst { it.address == address }
        val old = list.getOrNull(idx)
        val entry = HistoryEntry(
            address,
            name ?: old?.name ?: address,
            if (touchTime) System.currentTimeMillis() else old?.lastConnected ?: 0L
        )
        if (idx >= 0) list[idx] = entry else list.add(entry)
        saveHistory(list)
    }

    private fun removeHistory(address: String) {
        saveHistory(loadHistory().filter { it.address != address })
    }

    @SuppressWarnings("MissingPermission")
    private fun deviceNameOrNull(device: BluetoothDevice): String? {
        if (!hasConnectPermission()) return null
        return device.name
    }

    private fun refreshTargetLabel() {
        val addr = targetAddress
        if (addr == null) {
            textTarget.text = "対象デバイス: 未選択"
            return
        }
        val name = findBondedDevice(addr)?.name
            ?: loadHistory().firstOrNull { it.address == addr }?.name
            ?: addr
        textTarget.text = "対象デバイス: $name"
    }

    @SuppressWarnings("MissingPermission")
    private fun findBondedDevice(address: String): BluetoothDevice? {
        if (!hasConnectPermission()) return null
        return bluetoothAdapter.bondedDevices?.firstOrNull { it.address == address }
    }

    // ---------- 状態表示 ----------

    @SuppressWarnings("MissingPermission")
    private fun refreshStatus() {
        textBtPower.text = if (bluetoothAdapter.isEnabled) "Bluetooth: ON" else "Bluetooth: OFF"
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
    private fun attemptConnect(promptEnable: Boolean = true) {
        val addr = targetAddress
        if (addr == null) {
            Toast.makeText(this, "先にデバイスを選択してください", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasConnectPermission()) {
            ensurePermissionsThen { attemptConnect(promptEnable) }
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            if (promptEnable) {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
            return
        }
        val device = findBondedDevice(addr)
        if (device == null) {
            Toast.makeText(
                this,
                "ペアリングが解除されています。Bluetooth設定で再ペアリングしてください",
                Toast.LENGTH_LONG
            ).show()
            openBluetoothSettings()
            return
        }
        recordHistory(addr, device.name, touchTime = false)

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
        reconnectHandler.postDelayed({ attemptConnect(promptEnable = false) }, reconnectIntervalMs)
    }

    private fun openBluetoothSettings() {
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    // ---------- Termux連携 ----------

    private fun isTermuxInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun hasTermuxRunPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, TERMUX_RUN_COMMAND_PERMISSION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureTermuxPermissionThen(action: () -> Unit) {
        if (hasTermuxRunPermission()) {
            action()
            return
        }
        ActivityCompat.requestPermissions(
            this, arrayOf(TERMUX_RUN_COMMAND_PERMISSION), REQUEST_TERMUX_PERMISSION
        )
        pendingAfterTermuxPermission = action
    }

    private fun runTermuxScript() {
        if (!isTermuxInstalled()) {
            Toast.makeText(this, "Termuxがインストールされていません", Toast.LENGTH_LONG).show()
            return
        }
        ensureTermuxPermissionThen {
            val intent = Intent().apply {
                setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_HOME/run.sh")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf<String>())
                putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            try {
                ContextCompat.startForegroundService(this, intent)
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Termuxの起動に失敗しました。Termux側で「外部アプリからの実行」が" +
                        "許可されているか確認してください(~/.termux/termux.properties に" +
                        " allow-external-apps=true を設定しTermuxを再起動)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
