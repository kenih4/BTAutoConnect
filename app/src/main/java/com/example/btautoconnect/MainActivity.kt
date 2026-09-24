package com.example.btautoconnect

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.animation.ObjectAnimator
import android.database.ContentObserver
import android.media.AudioManager
import android.media.ToneGenerator
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
    private lateinit var textProfiles: TextView
    private lateinit var textVolume: TextView
    private lateinit var audioManager: AudioManager
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

    private var pendingVerify: Runnable? = null
    private val pollIntervalMs = 1000L
    private val waitForHfpMs = 30000L
    private val waitForCarA2dpMs = 20000L
    private val fallbackVerifyMs = 5000L
    private var waitStartMs = 0L
    private var hfpSeenMs = 0L
    private var fallbackSentMs = 0L
    private var waitNote: String? = null
    private var autoConnectPending = false
    private var volumeAppliedOnA2dp = false
    private var wasStopped = false
    private var blinkAnimator: ObjectAnimator? = null
    private lateinit var teamsReader: TeamsChannelReader
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var launchedOtherApp = false

    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshVolume()
        }
    }

    companion object {
        private const val PREFS_NAME = "bt_auto_connect_prefs"
        private const val KEY_TARGET_ADDRESS = "target_address"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_HISTORY = "device_history"
        private const val KEY_DEFAULT_TARGET = "default_target_address"
        private const val KEY_TEAMS_TEAM = "teams_team"
        private const val KEY_TEAMS_CHANNEL = "teams_channel"
        private const val TARGET_VOLUME_PERCENT = 60

        private val UUID_A2DP_SINK: java.util.UUID =
            java.util.UUID.fromString("0000110b-0000-1000-8000-00805f9b34fb")
        private val UUIDS_HANDS_FREE: Set<java.util.UUID> = setOf(
            java.util.UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb"),
            java.util.UUID.fromString("00001108-0000-1000-8000-00805f9b34fb")
        )

        private val COLOR_OK = android.graphics.Color.parseColor("#2E7D32")
        private val COLOR_WARN = android.graphics.Color.parseColor("#EF6C00")
        private val COLOR_NG = android.graphics.Color.parseColor("#C62828")
        private val COLOR_NEUTRAL = android.graphics.Color.parseColor("#757575")
        private const val REQUEST_BT_PERMISSIONS = 100
        private const val REQUEST_TERMUX_PERMISSION = 101
        private const val TERMUX_PACKAGE = "com.termux"
        private const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
        private const val MAIL_READER_PACKAGE = "com.kenih4.morningreader"
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
            if (a2dpProxy != null && headsetProxy != null) runAutoConnect()
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
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED,
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    refreshStatus()
                    return
                }
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
        textProfiles = findViewById(R.id.textProfiles)
        textVolume = findViewById(R.id.textVolume)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, volumeObserver)
        refreshVolume()
        findViewById<TextView>(R.id.textBuildInfo).text =
            "v${BuildConfig.VERSION_NAME} ・ ビルド日時: ${BuildConfig.BUILD_TIME}"
        btnConnect = findViewById(R.id.btnConnect)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)
        btnRunTermuxScript = findViewById(R.id.btnRunTermuxScript)
        switchAutoReconnect = findViewById(R.id.switchAutoReconnect)
        containerDeviceList = findViewById(R.id.containerDeviceList)

        targetAddress = prefs.getString(KEY_TARGET_ADDRESS, null)
        val defaultAddress = prefs.getString(KEY_DEFAULT_TARGET, null)
        if (defaultAddress != null) {
            targetAddress = defaultAddress
            prefs.edit().putString(KEY_TARGET_ADDRESS, defaultAddress).apply()
        } else {
            loadHistory().filter { it.lastConnected > 0 }.maxByOrNull { it.lastConnected }?.let {
                targetAddress = it.address
                prefs.edit().putString(KEY_TARGET_ADDRESS, it.address).apply()
            }
        }
        autoConnectPending = savedInstanceState == null && targetAddress != null
        switchAutoReconnect.isChecked = prefs.getBoolean(KEY_AUTO_RECONNECT, false)
        switchAutoReconnect.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_AUTO_RECONNECT, checked).apply()
        }

        btnConnect.setOnClickListener {
            if (blinkAnimator != null) cancelConnectAttempt() else attemptConnect()
        }
        btnOpenSettings.setOnClickListener { openBluetoothSettings() }
        btnRunTermuxScript.setOnClickListener { runTermuxScript() }
        findViewById<Button>(R.id.btnOpenYoutubeMusic).setOnClickListener { openYoutubeMusic() }
        findViewById<Button>(R.id.btnOpenMailReader).setOnClickListener { openMailReader() }

        findViewById<android.widget.ImageButton>(R.id.btnSettings).setOnClickListener { showSettingsMenu() }

        teamsReader = TeamsChannelReader(this)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.JAPAN
                ttsReady = true
            }
        }
        findViewById<Button>(R.id.btnReadTeams).apply {
            setOnClickListener { readTeamsChannel() }
            setOnLongClickListener {
                showTeamsSettingsDialog()
                true
            }
        }

        registerReceiver(
            aclReceiver,
            IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            }
        )

        ensurePermissionsThen {
            bluetoothAdapter.getProfileProxy(this, profileListener, BluetoothProfile.A2DP)
            bluetoothAdapter.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET)
            loadBondedDevices()
            refreshStatus()

            if (autoConnectPending) {
                if (!bluetoothAdapter.isEnabled) {
                    runAutoConnect()
                } else {
                    reconnectHandler.postDelayed({ runAutoConnect() }, 3000)
                }
            }
        }
    }

    private fun runAutoConnect() {
        if (!autoConnectPending) return
        autoConnectPending = false
        attemptConnect()
    }

    override fun onStop() {
        super.onStop()
        wasStopped = true
    }

    override fun onStart() {
        super.onStart()
        if (!wasStopped) return
        wasStopped = false
        if (launchedOtherApp) {
            launchedOtherApp = false
            return
        }
        if (!autoConnectPending && ::bluetoothAdapter.isInitialized) {
            attemptConnect()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(aclReceiver)
        contentResolver.unregisterContentObserver(volumeObserver)
        blinkAnimator?.cancel()
        tts?.stop()
        tts?.shutdown()
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
            val required = requiredProfiles(row.address).joinToString("・") { it.label }
            btn.text = "$marker${row.name}\n$last$unpaired\n必要: $required"
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

    private data class HistoryEntry(
        val address: String,
        val name: String,
        val lastConnected: Long,
        val requiredProfiles: Set<String>? = null
    )

    private enum class Profile(val key: String, val label: String) {
        A2DP("A2DP", "A2DP(音楽)"),
        HFP("HFP", "HFP(通話)")
    }

    // ---------- 接続履歴 ----------

    private fun loadHistory(): MutableList<HistoryEntry> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(json)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val addr = o.getString("address")
                val profiles = o.optJSONArray("profiles")?.let { a ->
                    (0 until a.length()).map { a.getString(it) }.toSet()
                }
                HistoryEntry(addr, o.optString("name", addr), o.optLong("last", 0L), profiles)
            }
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun saveHistory(list: List<HistoryEntry>) {
        val arr = JSONArray()
        list.forEach {
            val o = JSONObject()
                .put("address", it.address)
                .put("name", it.name)
                .put("last", it.lastConnected)
            it.requiredProfiles?.let { keys -> o.put("profiles", JSONArray(keys.toList())) }
            arr.put(o)
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
            if (touchTime) System.currentTimeMillis() else old?.lastConnected ?: 0L,
            old?.requiredProfiles
        )
        if (idx >= 0) list[idx] = entry else list.add(entry)
        saveHistory(list)
    }

    private fun showSettingsMenu() {
        val items = arrayOf("起動時に接続する既定のデバイス", "デバイスごとの必要な接続プロファイル")
        AlertDialog.Builder(this)
            .setTitle("設定")
            .setItems(items) { _, which ->
                if (which == 0) showDefaultDeviceDialog() else showProfileDeviceDialog()
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    private fun connectedHistory(): List<HistoryEntry> =
        loadHistory().filter { it.lastConnected > 0 }.sortedByDescending { it.lastConnected }

    private fun showProfileDeviceDialog() {
        val entries = connectedHistory()
        if (entries.isEmpty()) {
            Toast.makeText(this, "接続履歴がありません。一度接続するとここに表示されます", Toast.LENGTH_LONG).show()
            return
        }
        val labels = entries.map { e ->
            "${e.name}\n必要: ${requiredProfiles(e.address).joinToString("・") { it.label }}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("必要な接続プロファイルを設定するデバイス")
            .setItems(labels) { _, which -> showRequiredProfilesDialog(entries[which]) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showRequiredProfilesDialog(entry: HistoryEntry) {
        val all = Profile.values()
        val current = requiredProfiles(entry.address)
        val checked = BooleanArray(all.size) { all[it] in current }
        AlertDialog.Builder(this)
            .setTitle("${entry.name} の必要な接続プロファイル")
            .setMultiChoiceItems(all.map { it.label }.toTypedArray(), checked) { _, i, isChecked ->
                checked[i] = isChecked
            }
            .setPositiveButton("保存") { _, _ ->
                val selected = all.filterIndexed { i, _ -> checked[i] }
                if (selected.isEmpty()) {
                    Toast.makeText(this, "1つ以上選択してください", Toast.LENGTH_SHORT).show()
                } else {
                    saveRequiredProfiles(entry.address, selected.map { it.key }.toSet())
                }
            }
            .setNeutralButton("自動判定に戻す") { _, _ -> saveRequiredProfiles(entry.address, null) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun saveRequiredProfiles(address: String, keys: Set<String>?) {
        saveHistory(loadHistory().map { if (it.address == address) it.copy(requiredProfiles = keys) else it })
        loadBondedDevices()
        refreshStatus()
    }

    // ユーザー設定があればそれを、なければ機器が対応を宣言しているプロファイルを返す
    private fun requiredProfiles(addr: String): Set<Profile> {
        loadHistory().firstOrNull { it.address == addr }?.requiredProfiles?.let { keys ->
            val set = Profile.values().filter { it.key in keys }.toSet()
            if (set.isNotEmpty()) return set
        }
        val device = findBondedDevice(addr)
        return (device?.let { detectProfiles(it) }) ?: Profile.values().toSet()
    }

    @SuppressWarnings("MissingPermission")
    private fun detectProfiles(device: BluetoothDevice): Set<Profile>? {
        val uuids = device.uuids?.map { it.uuid } ?: return null
        val set = linkedSetOf<Profile>()
        if (UUID_A2DP_SINK in uuids) set.add(Profile.A2DP)
        if (uuids.any { it in UUIDS_HANDS_FREE }) set.add(Profile.HFP)
        return set.ifEmpty { null }
    }

    private fun proxyFor(profile: Profile): BluetoothProfile? = when (profile) {
        Profile.A2DP -> a2dpProxy
        Profile.HFP -> headsetProxy
    }

    private fun profileStateText(need: Set<Profile>, connected: Set<Profile>): String =
        need.joinToString(" / ") { "${it.label}: ${if (it in connected) "接続" else "未接続"}" }

    private fun showDefaultDeviceDialog() {
        val entries = loadHistory().filter { it.lastConnected > 0 }.sortedByDescending { it.lastConnected }
        if (entries.isEmpty()) {
            Toast.makeText(this, "接続履歴がありません。一度接続するとここに表示されます", Toast.LENGTH_LONG).show()
            return
        }
        val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        val labels = arrayOf("指定なし(最後に接続したデバイス)") +
            entries.map { "${it.name}\n最終接続: ${fmt.format(Date(it.lastConnected))}" }
        val current = prefs.getString(KEY_DEFAULT_TARGET, null)
        val checked = entries.indexOfFirst { it.address == current }.let { if (it < 0) 0 else it + 1 }

        AlertDialog.Builder(this)
            .setTitle("起動時に接続する既定のデバイス")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                if (which == 0) {
                    prefs.edit().remove(KEY_DEFAULT_TARGET).apply()
                } else {
                    val chosen = entries[which - 1]
                    prefs.edit().putString(KEY_DEFAULT_TARGET, chosen.address)
                        .putString(KEY_TARGET_ADDRESS, chosen.address).apply()
                    targetAddress = chosen.address
                    loadBondedDevices()
                    refreshStatus()
                }
                dialog.dismiss()
            }
            .setNegativeButton("キャンセル", null)
            .show()
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
        val btOn = bluetoothAdapter.isEnabled
        setStatus(textBtPower, if (btOn) "Bluetooth: ON" else "Bluetooth: OFF", if (btOn) COLOR_OK else COLOR_NG)
        refreshTargetLabel()
        val addr = targetAddress
        if (addr == null) {
            setStatus(textStatus, "状態: 対象デバイス未選択", COLOR_WARN)
            setStatus(textProfiles, "接続プロファイル: -", COLOR_NEUTRAL)
            return
        }
        if (!hasConnectPermission()) {
            setStatus(textStatus, "状態: 権限待ち", COLOR_WARN)
            setStatus(textProfiles, "接続プロファイル: -", COLOR_NEUTRAL)
            return
        }
        val device = findBondedDevice(addr)
        val need = requiredProfiles(addr)
        val connected = need.filter { isProfileConnected(proxyFor(it), addr) }.toSet()
        val profileColor = when {
            connected.size == need.size -> COLOR_OK
            connected.isNotEmpty() -> COLOR_WARN
            else -> COLOR_NG
        }
        setStatus(
            textProfiles,
            "接続プロファイル: " + profileStateText(need, connected) +
                (waitNote?.let { " ・$it" } ?: ""),
            profileColor
        )
        if (Profile.values().any { isProfileConnected(proxyFor(it), addr) }) {
            setStatus(textStatus, "状態: 接続中", COLOR_OK)
        } else if (device != null) {
            setStatus(textStatus, "状態: 未接続", COLOR_NG)
        } else {
            setStatus(textStatus, "状態: デバイス不明", COLOR_WARN)
        }
    }

    private fun setStatus(view: TextView, text: String, color: Int) {
        view.text = text
        view.setTextColor(color)
        view.setTypeface(null, android.graphics.Typeface.BOLD)
    }

    // ---------- 接続 ----------

    @SuppressWarnings("MissingPermission")
    private fun attemptConnect(promptEnable: Boolean = true) {
        pendingVerify?.let { reconnectHandler.removeCallbacks(it) }
        pendingVerify = null
        waitNote = null
        stopBlink()

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

        volumeAppliedOnA2dp = false
        setMediaVolumePercent(TARGET_VOLUME_PERCENT)

        val need = requiredProfiles(addr)
        if (need.all { isProfileConnected(proxyFor(it), addr) }) {
            Toast.makeText(
                this,
                "すでに接続済みです(${need.joinToString("・") { it.label }})",
                Toast.LENGTH_SHORT
            ).show()
            refreshStatus()
            return
        }

        // A2DPは車側から接続してくる機器が多く、先にスマホから要求すると拒否されるため、まずHFPだけ要求して待つ
        if (Profile.HFP in need && !isProfileConnected(headsetProxy, addr)) {
            val headsetOk = tryHiddenConnect(headsetProxy, device)
            Toast.makeText(
                this,
                "HFP(通話)要求: ${if (headsetOk) "送信OK" else "失敗"}\n車側からの接続を待ちます",
                Toast.LENGTH_SHORT
            ).show()
        }

        waitStartMs = SystemClock.elapsedRealtime()
        hfpSeenMs = 0L
        fallbackSentMs = 0L
        startBlink()
        pollConnection(addr)
    }

    private fun cancelConnectAttempt() {
        pendingVerify?.let { reconnectHandler.removeCallbacks(it) }
        pendingVerify = null
        waitNote = null
        stopBlink()
        refreshStatus()
        Toast.makeText(this, "接続試行を中断しました", Toast.LENGTH_SHORT).show()
    }

    private fun pollConnection(addr: String) {
        pendingVerify = null
        val now = SystemClock.elapsedRealtime()
        val need = requiredProfiles(addr)
        val needA2dp = Profile.A2DP in need
        val needHfp = Profile.HFP in need
        val a2dp = isProfileConnected(a2dpProxy, addr)
        val headset = isProfileConnected(headsetProxy, addr)
        val connected = need.filter { isProfileConnected(proxyFor(it), addr) }.toSet()
        if (headset && hfpSeenMs == 0L) hfpSeenMs = now

        if (a2dp && !volumeAppliedOnA2dp) {
            volumeAppliedOnA2dp = true
            setMediaVolumePercent(TARGET_VOLUME_PERCENT)
        }

        if (connected.size == need.size) {
            waitNote = null
            stopBlink()
            refreshStatus()
            playConnectedSound()
            Toast.makeText(this, "接続完了 → ${need.joinToString("・") { it.label }}", Toast.LENGTH_SHORT).show()
            return
        }

        var finished = false
        if (needHfp && !headset) {
            val remain = (waitForHfpMs - (now - waitStartMs)) / 1000
            if (remain <= 0) finished = true else waitNote = "HFP接続待ち(あと${remain}秒)"
        } else if (needA2dp && !a2dp) {
            if (fallbackSentMs == 0L) {
                val reference = if (needHfp) hfpSeenMs else waitStartMs
                val remain = (waitForCarA2dpMs - (now - reference)) / 1000
                if (remain > 0) {
                    waitNote = "車側からのA2DP接続待ち(あと${remain}秒)"
                } else {
                    val device = findBondedDevice(addr)
                    val ok = device != null && tryHiddenConnect(a2dpProxy, device)
                    fallbackSentMs = now
                    waitNote = "スマホ側からA2DP接続を試行中"
                    Toast.makeText(
                        this,
                        "車側から接続されないため、スマホ側からA2DP接続を1回試します(要求: ${if (ok) "送信OK" else "失敗"})",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else if (now - fallbackSentMs >= fallbackVerifyMs) {
                finished = true
            }
        }

        if (finished) {
            waitNote = null
            stopBlink()
            refreshStatus()
            if (connected.isNotEmpty()) {
                Toast.makeText(this, "一部のみ接続 → ${profileStateText(need, connected)}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(
                    this,
                    "自動接続できませんでした。設定画面から手動で接続してください",
                    Toast.LENGTH_LONG
                ).show()
                openBluetoothSettings()
            }
            return
        }

        refreshStatus()
        val next = Runnable { pollConnection(addr) }
        pendingVerify = next
        reconnectHandler.postDelayed(next, pollIntervalMs)
    }

    @SuppressWarnings("MissingPermission")
    private fun isProfileConnected(proxy: BluetoothProfile?, addr: String): Boolean =
        proxy?.connectedDevices?.any { it.address == addr } ?: false

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
        launchedOtherApp = true
        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    }

    // ---------- Teamsチャンネルの読み上げ ----------

    private fun readTeamsChannel() {
        val speaker = tts
        if (speaker != null && speaker.isSpeaking) {
            speaker.stop()
            Toast.makeText(this, "読み上げを停止しました", Toast.LENGTH_SHORT).show()
            return
        }
        val team = prefs.getString(KEY_TEAMS_TEAM, "").orEmpty()
        val channel = prefs.getString(KEY_TEAMS_CHANNEL, "").orEmpty()
        if (team.isBlank() || channel.isBlank()) {
            showTeamsSettingsDialog()
            return
        }
        if (!ttsReady) {
            Toast.makeText(this, "音声合成の準備中です。少し待ってからもう一度押してください", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Teamsからメッセージを取得中…", Toast.LENGTH_SHORT).show()
        teamsReader.fetch(team, channel, onSignInLaunch = { launchedOtherApp = true }) { result ->
            result.onSuccess { speakTeamsMessages(channel, it) }
            result.onFailure {
                Toast.makeText(this, it.message ?: "取得に失敗しました", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun speakTeamsMessages(channel: String, messages: List<TeamsChannelReader.Message>) {
        val speaker = tts ?: return
        if (messages.isEmpty()) {
            speaker.speak("${channel}チャンネルに読み上げるメッセージはありません", TextToSpeech.QUEUE_FLUSH, null, "teams-none")
            return
        }
        speaker.speak("${channel}チャンネルの最新${messages.size}件を読み上げます", TextToSpeech.QUEUE_FLUSH, null, "teams-head")
        messages.forEachIndexed { i, m ->
            speaker.speak("${m.sender}さん。${m.text.take(500)}", TextToSpeech.QUEUE_ADD, null, "teams-$i")
        }
    }

    private fun showTeamsSettingsDialog() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val teamInput = EditText(this).apply {
            hint = "チーム名"
            setText(prefs.getString(KEY_TEAMS_TEAM, ""))
        }
        val channelInput = EditText(this).apply {
            hint = "チャンネル名"
            setText(prefs.getString(KEY_TEAMS_CHANNEL, ""))
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(teamInput)
            addView(channelInput)
        }
        AlertDialog.Builder(this)
            .setTitle("Teamsの読み上げ対象")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                prefs.edit()
                    .putString(KEY_TEAMS_TEAM, teamInput.text.toString().trim())
                    .putString(KEY_TEAMS_CHANNEL, channelInput.text.toString().trim())
                    .apply()
                Toast.makeText(this, "保存しました。ボタンを押すと読み上げます", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // ---------- 接続完了の効果音 ----------

    private fun playConnectedSound() {
        // 出力先がBluetoothへ切り替わるのを少し待ってから鳴らす
        reconnectHandler.postDelayed({
            try {
                val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
                tone.startTone(ToneGenerator.TONE_PROP_ACK, 300)
                reconnectHandler.postDelayed({ tone.release() }, 800)
            } catch (e: RuntimeException) {
                // 効果音が出せなくても接続自体には影響しない
            }
        }, 600)
    }

    // ---------- 接続試行中の点滅 ----------

    private fun startBlink() {
        stopBlink()
        btnConnect.text = "接続試行中…"
        blinkAnimator = ObjectAnimator.ofFloat(btnConnect, "alpha", 1f, 0.25f).apply {
            duration = 500
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
    }

    private fun stopBlink() {
        blinkAnimator?.cancel()
        blinkAnimator = null
        btnConnect.alpha = 1f
        btnConnect.text = "接続する"
    }

    // ---------- 音量 ----------

    private fun setMediaVolumePercent(percent: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = Math.round(max * percent / 100f).coerceIn(0, max)
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (e: SecurityException) {
            Toast.makeText(this, "音量を設定できませんでした", Toast.LENGTH_SHORT).show()
        }
        refreshVolume()
    }

    private fun refreshVolume() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val percent = if (max > 0) cur * 100 / max else 0
        // 0%=青(寒色) → 100%=赤(暖色)。文字として読める明るさに抑える
        val hue = 240f * (1f - percent / 100f)
        val color = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.9f, 0.7f))
        setStatus(textVolume, "メディア音量: $percent% ($cur/$max)", color)
    }

    private fun openMailReader() {
        val intent = packageManager.getLaunchIntentForPackage(MAIL_READER_PACKAGE)
        if (intent == null) {
            Toast.makeText(this, "メール読み上げがインストールされていません", Toast.LENGTH_LONG).show()
            return
        }
        launchedOtherApp = true
        startActivity(intent)
    }

    private fun openYoutubeMusic() {
        val intent = packageManager.getLaunchIntentForPackage(YOUTUBE_MUSIC_PACKAGE)
        if (intent == null) {
            Toast.makeText(this, "YouTube Musicがインストールされていません", Toast.LENGTH_LONG).show()
            return
        }
        launchedOtherApp = true
        startActivity(intent)
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
                launchedOtherApp = true
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
