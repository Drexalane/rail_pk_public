package com.drexalane.railpk

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import org.json.JSONObject
class MainActivity : FlutterActivity() {
    private val channel = "com.drexalane.railpk/overlay"
    private val updateChannel = "com.drexalane.railpk/update"
    private val eventChannel = "com.drexalane.railpk/overlay_status"

    private var overlayEventSink: EventChannel.EventSink? = null
    private var locationRevocationReceiver: BroadcastReceiver? = null

    companion object {
        private const val REQUEST_OVERLAY = 1001
        private const val REQUEST_LOCATION = 1002
        const val VERSION_CODE = 9  // incrémenter à chaque release publique
        const val VERSION_NAME = "2.1.5"  // à bumper avec pubspec.yaml
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Crash log local (A10)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(Date())
                val file = File(getExternalFilesDir(null), "crash_$ts.log")
                file.writeText("$thread\n${throwable.stackTraceToString()}")
            } catch (_: Exception) { }
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // MethodChannel pour les appels Flutter → Android
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "startOverlay" -> {
                        if (checkPermissions()) {
                            OverlayService.start(this)
                            sendOverlayStatus(true)
                            result.success(true)
                        } else {
                            result.success(false)
                        }
                    }
                    "stopOverlay" -> {
                        OverlayService.stop(this)
                        sendOverlayStatus(false)
                        result.success(true)
                    }
                    "isOverlayRunning" -> {
                        result.success(OverlayService.isRunning)
                    }
                    "requestPermissions" -> {
                        requestAllPermissions()
                        result.success(true)
                    }
                    "hasAllPermissions" -> {
                        result.success(hasOverlayPermission() && hasLocationPermission())
                    }
                    "isGpsEnabled" -> {
                        val lm = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
                        result.success(lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER))
                    }
                    "hasOverlayPermission" -> {
                        result.success(hasOverlayPermission())
                    }
                    "centerOverlay" -> {
                        centerOverlay()
                        result.success(true)
                    }
                    "setPkColor" -> {
                        val color = when (val arg = call.arguments) {
                            is Int -> arg
                            is Long -> arg.toInt()
                            else -> return@setMethodCallHandler
                        }
                        OverlayService.pkColorPref = color
                        OverlayService.setPkColor(this, color)
                        result.success(true)
                    }
                    "getVersion" -> {
                        result.success(VERSION_NAME)
                    }
                    else -> result.notImplemented()
                }
            }

        // EventChannel pour diffuser l'état de l'overlay vers Flutter
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, eventChannel)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(args: Any?, events: EventChannel.EventSink?) {
                    overlayEventSink = events
                    events?.success(OverlayService.isRunning)
                }
                override fun onCancel(args: Any?) {
                    overlayEventSink = null
                }
            })

        // Enregistre le receveur de révocation de permission localisation (Android 14+)
        registerLocationRevocationReceiver()

        // MethodChannel pour la mise à jour auto
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, updateChannel)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "hasNetwork" -> {
                        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                        val active = cm.activeNetwork
                        val caps = if (active != null) cm.getNetworkCapabilities(active) else null
                        result.success(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
                    }
                    "checkUpdate" -> {
                        Thread {
                            try {
                                val info = fetchLatestRelease()
                                runOnUiThread { result.success(info) }
                            } catch (e: Exception) {
                                runOnUiThread { result.error("UPDATE_ERROR", e.message, null) }
                            }
                        }.start()
                    }
                    "downloadUpdate" -> {
                        val url = call.argument<String>("url") ?: ""
                        Thread {
                            try {
                                val apkPath = downloadApk(url)
                                runOnUiThread { result.success(apkPath) }
                            } catch (e: Exception) {
                                runOnUiThread { result.error("DOWNLOAD_ERROR", e.message, null) }
                            }
                        }.start()
                    }
                    "installApk" -> {
                        val path = call.argument<String>("path") ?: ""
                        installApk(path)
                        result.success(true)
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun sendOverlayStatus(running: Boolean) {
        overlayEventSink?.success(running)
    }

    private fun registerLocationRevocationReceiver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            locationRevocationReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == "android.location.action.LOCATION_PERMISSION_REVOKED") {
                        // Notifier Flutter que la permission a été révoquée
                        overlayEventSink?.success(false)
                        // Arrêter l'overlay si la permission est perdue
                        OverlayService.stop(this@MainActivity)
                    }
                }
            }
            val filter = IntentFilter("android.location.action.LOCATION_PERMISSION_REVOKED")
            registerReceiver(locationRevocationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        }
    }

    private fun checkPermissions(): Boolean {
        // Overlay permission
        if (!hasOverlayPermission()) {
            showOverlayPermissionDialog()
            return false
        }
        // Location permission
        if (!hasLocationPermission()) {
            requestLocationPermission()
            return false
        }
        // Battery optimization
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                requestBatteryExemption()
            }
        }
        return true
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(this)
        else true
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permission Overlay")
            .setMessage("Rail PK a besoin de la permission d'affichage par-dessus les autres applications pour superposer le PK sur le PDF d'aide à la conduite.")
            .setPositiveButton("Autoriser") { _, _ -> requestOverlayPermission() }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun requestAllPermissions() {
        if (!hasOverlayPermission()) requestOverlayPermission()
        if (!hasLocationPermission()) requestLocationPermission()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_OVERLAY)
        }
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            REQUEST_LOCATION
        )
    }

    private fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun centerOverlay() {
        val prefs = getSharedPreferences("overlay", Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat("xFrac", 0.5f)
            .putFloat("yFrac", 0.3f)
            .remove("x")
            .remove("y")
            .apply()
        if (OverlayService.isRunning) {
            // Recentre sans toucher aux stats de session (Ø, Σ)
            OverlayService.recenter(this)
        }
    }

    // ── Mise à jour automatique ──

    private val githubApiUrl = "https://api.github.com/repos/Drexalane/rail_pk_public/releases/latest"

    /** Appelle l'API GitHub Releases pour comparer la version. */
    private fun fetchLatestRelease(): Map<String, Any?> {
        val conn = URL(githubApiUrl).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        val body = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(body)
        val tag = json.getString("tag_name")
        val bodyText = json.optString("body", "")
        // Extraire versionCode du body (format: versionCode: N)
        val codeRegex = Regex("""versionCode[:\s]+(\d+)""")
        val latestCode = codeRegex.find(bodyText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val assets = json.getJSONArray("assets")
        var downloadUrl = ""
        var size = 0L
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name").endsWith(".apk")) {
                downloadUrl = asset.getString("browser_download_url")
                size = asset.getLong("size")
                break
            }
        }
        conn.disconnect()
        return mapOf(
            "updateAvailable" to (latestCode > VERSION_CODE),
            "version" to tag,
            "versionCode" to latestCode,
            "currentVersionCode" to VERSION_CODE,
            "downloadUrl" to downloadUrl,
            "size" to size,
        )
    }

    /** Télécharge l'APK dans le cache externe. */
    private fun downloadApk(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 120000
        conn.setRequestProperty("Accept", "application/octet-stream")
        // Suivre les redirections GitHub
        conn.instanceFollowRedirects = true
        val file = File(externalCacheDir, "railpk_update.apk")
        conn.inputStream.use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }
        conn.disconnect()
        return file.absolutePath
    }

    /** Lance l'installateur Android pour l'APK téléchargé. */
    private fun installApk(path: String) {
        val file = File(path)
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val apkUri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            // Accorder permission de lecture temporaire
            grantUriPermission(
                "com.android.packageinstaller",
                apkUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            apkUri
        } else {
            Uri.fromFile(file)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        startActivity(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY) {
            if (hasOverlayPermission()) {
                // Auto-démarrage après acceptation des permissions
                if (!OverlayService.isRunning) {
                    checkPermissions()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkPermissions()
            }
        }
    }

    override fun onDestroy() {
        try {
            locationRevocationReceiver?.let { unregisterReceiver(it) }
        } catch (_: IllegalArgumentException) { }
        super.onDestroy()
    }
}
