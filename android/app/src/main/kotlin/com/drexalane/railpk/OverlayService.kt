package com.drexalane.railpk

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Color
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.abs

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var fusedLocation: FusedLocationProviderClient
    private lateinit var locationManager: LocationManager
    private lateinit var network: RailNetwork

    private var overlayView: View? = null
    private var pkText: TextView? = null
    private var lineText: TextView? = null
    private var speedText: TextView? = null
    private var distanceText: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var overlayCreated = false

    // Moteur PK
    private var lastResult: PkResult? = null
    private var lastSpeed = 0f
    private var lastGpsTime = 0L
    private var speedGraceCounter = 0
    private var geoJsonLoadAttempts = 0
    private var geoJsonErrorMsg: String? = null
    private val gpsTimeoutHandler = Handler(Looper.getMainLooper())
    private val lineHistory = ArrayDeque<String>()

    // Position overlay
    private var overlayX = 100
    private var overlayY = 100

    // Stats session
    private var sessionDistanceM = 0.0       // distance totale parcourue
    private var sessionMovingTimeMs = 0.0   // temps cumulé au-dessus du seuil
    private var lastProcessTimeElapsed = 0L  // timestamp monotone dernier appel (elapsedRealtime)

    // Anti-dérive arrêt (A3)
    private val recentPksRunning = ArrayDeque<Double>()

    // Extrapolation tunnel (A2)
    private var extrapolationStartPk = 0.0
    private var extrapolationStartTime = 0L
    private var extrapolationSpeed = 0f
    private var extrapolationDirection = 0  // +1 pair, -1 impair
    private var extrapolationArmed = false

    // WakeLock (H2)
    private var wakeLock: PowerManager.WakeLock? = null

    // Config change callback (H5)
    private var configCallbacks: ComponentCallbacks? = null

    // Listener changement couleur (SharedPreferences)
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    companion object {
        private const val CHANNEL_ID = "railpk_overlay"
        private const val NOTIFICATION_ID = 1

        private const val HYSTERESIS = 5
        private const val SPEED_THRESHOLD_MS = 0.83f  // ~3 km/h
        private const val SPEED_GRACE_READINGS = 3      // délai de grâce avant gel
        private const val GEOJSON_RETRY_COUNT = 3
        private const val GEOJSON_RETRY_DELAY_MS = 10000L
        private const val GPS_INTERVAL_ACTIVE_MS = 500L     // 2 Hz en mouvement
        private const val GPS_INTERVAL_IDLE_MS = 5000L     // 0.2 Hz à l'arrêt
        private const val EXTRAPOLATION_DELAY_MS = 5000L   // A2 : délai avant extrapolation
        private const val EXTRAPOLATION_MAX_MS = 300000L   // A2 : timeout extrapolation (5 min)
        private const val RECENT_PK_WINDOW = 7             // A3 : fenêtre médiane
        private const val TEXT_SIZE_SP = 72f                // PK texte principal
        private const val LINE_TEXT_SIZE_SP = 16f           // texte secondaire
        private const val AUTO_SIZE_MIN_PK_SP = 56.0f        // min auto-size PK
        private const val AUTO_SIZE_MIN_LINE_SP = 10.0f      // min auto-size secondaire

        var isRunning = false
            private set

        fun setPkColor(context: Context, color: Int) {
            context.getSharedPreferences("overlay", Context.MODE_PRIVATE)
                .edit().putInt("pkColor", color).apply()
            pkColorPref = color
        }

        // Couleur PK persistée — défaut magenta
        var pkColorPref: Int = Color.argb(255, 217, 0, 208)

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                putExtra("resetStats", true)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: ForegroundServiceStartNotAllowedException) {
                android.util.Log.e("RailPK", "Foreground service start not allowed from background", e)
            } catch (e: SecurityException) {
                android.util.Log.e("RailPK", "Security exception starting service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }

        /** Recentre l'overlay sans réinitialiser les stats de session. */
        fun recenter(context: Context) {
            if (!isRunning) return
            val intent = Intent(context, OverlayService::class.java).apply {
                putExtra("recenter", true)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: SecurityException) {
                android.util.Log.e("RailPK", "Security exception during recenter", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        network = RailNetwork()

        // Acquérir WakeLock partiel pour le GPS (H2)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RailPK:GPS")
        wakeLock?.acquire(8 * 60 * 60 * 1000L)  // timeout 8h max (durée max service)

        // Enregistrer callback de changement de config (H5)
        configCallbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                restoreOverlayPosition()
            }
            override fun onLowMemory() {}
        }
        registerComponentCallbacks(configCallbacks)

        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        isRunning = true

        // Listener SharedPreferences pour changement couleur à la volée
        val prefs = getSharedPreferences("overlay", Context.MODE_PRIVATE)
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "pkColor") {
                pkColorPref = prefs.getInt("pkColor", Color.argb(255, 217, 0, 208))
                Handler(Looper.getMainLooper()).post {
                    lastResult?.let { updateDisplay(it) }
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener!!)

        // Charge le réseau ferré avec retry
        loadGeoJsonWithRetry()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("resetStats", false) == true) {
            sessionDistanceM = 0.0
            sessionMovingTimeMs = 0.0
            lastProcessTimeElapsed = 0L
        }
        createOverlay()
        if (intent?.getBooleanExtra("recenter", false) == true) {
            // Relit la position depuis les prefs sans toucher aux stats
            restoreOverlayPosition()
        }
        startLocationUpdates()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    // ─── Overlay ────────────────────────────────────────────

    private fun createOverlay() {
        if (overlayCreated) return
        overlayCreated = true

        // Restaure la position persistée
        val prefs = getSharedPreferences("overlay", Context.MODE_PRIVATE)
        pkColorPref = prefs.getInt("pkColor", Color.argb(255, 217, 0, 208))
        val dm = resources.displayMetrics
        val xFrac = prefs.getFloat("xFrac", -1f)
        overlayX = if (xFrac >= 0f) (xFrac * dm.widthPixels).toInt() else prefs.getInt("x", 100)
        val yFrac = prefs.getFloat("yFrac", -1f)
        overlayY = if (yFrac >= 0f) (yFrac * dm.heightPixels).toInt() else prefs.getInt("y", 100)

        // Conteneur visible (fond + texte) — taille fixe, polices ajustées via adjustTextSize
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(128, 250, 248, 240))  // blanc cassé 50% opaque
                setStroke(dp(1), Color.argb(80, 0, 0, 0))  // bordure gris foncé
                cornerRadius = dp(8).toFloat()
            }
        }

        // Poignée de drag
        val handle = View(this).apply {
            setBackgroundColor(Color.argb(180, 0, 0, 0))
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(6)).apply {
                bottomMargin = dp(8)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        container.addView(handle)

        pkText = TextView(this).apply {
            text = "---"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isSingleLine = true
            gravity = Gravity.CENTER
            setShadowLayer(4f, 0f, 1f, Color.argb(60, 0, 0, 0))
        }
        container.addView(pkText)

        val rowLp = { LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) } }

        lineText = TextView(this).apply {
            text = "----"
            setTextColor(Color.argb(200, 0, 0, 0))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, LINE_TEXT_SIZE_SP)
            isSingleLine = true
            gravity = Gravity.CENTER
            setShadowLayer(3f, 0f, 1f, Color.argb(40, 0, 0, 0))
            layoutParams = rowLp()
        }
        container.addView(lineText)

        speedText = TextView(this).apply {
            text = ""
            setTextColor(Color.argb(200, 0, 0, 0))  // noir 80%% — même règle que le code ligne
            setTextSize(TypedValue.COMPLEX_UNIT_SP, LINE_TEXT_SIZE_SP)
            isSingleLine = true
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = rowLp()
        }
        container.addView(speedText)

        distanceText = TextView(this).apply {
            text = ""
            setTextColor(Color.argb(200, 0, 0, 0))  // noir 80%%
            setTextSize(TypedValue.COMPLEX_UNIT_SP, LINE_TEXT_SIZE_SP)
            isSingleLine = true
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = rowLp()
        }
        container.addView(distanceText)

        overlayView = container

        layoutParams = WindowManager.LayoutParams(
            dp(260),
            dp(248),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = overlayX
            y = overlayY
        }

        setupTouch(container)
        startGpsTimeoutChecker()

        try {
            windowManager.addView(overlayView, layoutParams)
        } catch (e: WindowManager.BadTokenException) {
            android.util.Log.e("RailPK", "Overlay addView failed — permission révoquée ?", e)
            overlayView = null
            overlayCreated = false
            stopSelf()
        }
    }

    /** Restaure la position de l'overlay après rotation / changement de config (H5). */
    private fun restoreOverlayPosition() {
        if (overlayView == null || layoutParams == null) return
        val dm = resources.displayMetrics
        val prefs = getSharedPreferences("overlay", Context.MODE_PRIVATE)
        val xFrac = prefs.getFloat("xFrac", -1f)
        val yFrac = prefs.getFloat("yFrac", -1f)
        if (xFrac >= 0f && yFrac >= 0f) {
            overlayX = (xFrac * dm.widthPixels).toInt()
            overlayY = (yFrac * dm.heightPixels).toInt()
            layoutParams!!.x = overlayX
            layoutParams!!.y = overlayY
            windowManager.updateViewLayout(overlayView, layoutParams)
        }
    }

    // ─── Touch handlers ─────────────────────────────────────

    private fun setupTouch(view: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val dragThreshold = dp(8).toFloat()

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams!!.x
                    initialY = layoutParams!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!isDragging && (abs(dx) > dragThreshold || abs(dy) > dragThreshold)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        layoutParams!!.x = initialX + dx.toInt()
                        layoutParams!!.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(overlayView, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        overlayX = layoutParams!!.x
                        overlayY = layoutParams!!.y
                        val displayMetrics = resources.displayMetrics
                        getSharedPreferences("overlay", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("x", overlayX)
                            .putInt("y", overlayY)
                            .putFloat("xFrac", overlayX.toFloat() / displayMetrics.widthPixels)
                            .putFloat("yFrac", overlayY.toFloat() / displayMetrics.heightPixels)
                            .apply()
                    }
                    isDragging = false
                    true
                }
                else -> true
            }
        }
    }


    // ─── GPS timeout checker ───────────────────────────────

    private val gpsTimeoutRunnable = object : Runnable {
        override fun run() {
            if (lastGpsTime > 0 && lastResult != null && network.loaded) {
                val elapsed = System.currentTimeMillis() - lastGpsTime
                when {
                    elapsed > EXTRAPOLATION_MAX_MS -> {
                        extrapolationArmed = false
                        updateDisplay(null)
                    }
                    elapsed > EXTRAPOLATION_DELAY_MS && extrapolationArmed -> {
                        // A2 : extrapolation vitesse en tunnel
                        val deltaKm = (extrapolationSpeed * elapsed) / 1_000_000.0
                        val pk = extrapolationStartPk + deltaKm * extrapolationDirection
                        val ext = PkResult(pk, lastResult!!.codeLigne, deadReckoning = true)
                        updateDisplay(ext, extrapolating = true)
                    }
                }
            }
            gpsTimeoutHandler.postDelayed(this, 1000L)
        }
    }

    private fun startGpsTimeoutChecker() {
        gpsTimeoutHandler.postDelayed(gpsTimeoutRunnable, 2000L)
    }

    // ─── GPS ────────────────────────────────────────────────

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        val request = buildLocationRequest(GPS_INTERVAL_ACTIVE_MS)
        this.gpsIntervalMs = GPS_INTERVAL_ACTIVE_MS

        fusedLocation.requestLocationUpdates(
            request,
            locationCallback,
            Looper.getMainLooper()
        )

        // Fallback LocationManager si Google Play Services absent (émulateur vanilla)
        val gpsAvailable = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this)
        if (gpsAvailable != com.google.android.gms.common.ConnectionResult.SUCCESS) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    GPS_INTERVAL_ACTIVE_MS,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
        }
    }

    private var gpsIntervalMs = GPS_INTERVAL_ACTIVE_MS
    private var gpsIdleCounter = 0            // hystérésis pour éviter le flap au seuil
    private val GPS_IDLE_HYSTERESIS = 5  // lectures consécutives sous seuil avant idle

    /** Reconstruit la requête GPS sans batching (latence minimale). */
    private fun buildLocationRequest(intervalMs: Long): LocationRequest {
        return LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY, intervalMs
        ).apply {
            setMinUpdateIntervalMillis(0)
        }.build()
    }

    /** Passe en GPS basse fréquence si vitesse < seuil (A6).
     * Hystérésis : 5 lectures consécutives sous le seuil avant de passer en idle. */
    private fun adjustGpsFrequency(speed: Float) {
        if (speed < SPEED_THRESHOLD_MS) {
            gpsIdleCounter++
        } else {
            gpsIdleCounter = 0
            // Repasse immédiatement en actif si au-dessus du seuil
            if (gpsIntervalMs != GPS_INTERVAL_ACTIVE_MS) {
                setGpsInterval(GPS_INTERVAL_ACTIVE_MS)
            }
            return
        }
        if (gpsIdleCounter >= GPS_IDLE_HYSTERESIS && gpsIntervalMs != GPS_INTERVAL_IDLE_MS) {
            setGpsInterval(GPS_INTERVAL_IDLE_MS)
        }
    }

    private fun setGpsInterval(intervalMs: Long) {
        gpsIntervalMs = intervalMs
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            fusedLocation.removeLocationUpdates(locationCallback)
            fusedLocation.requestLocationUpdates(
                buildLocationRequest(gpsIntervalMs),
                locationCallback,
                Looper.getMainLooper()
            )
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            processLocation(loc)
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) {
                processLocation(loc)
            }
        }
    }

    private fun processLocation(loc: Location) {
        // Rejeter les fixes de précision médiocre (> 30 m)
        if (loc.hasAccuracy() && loc.accuracy > 30f) return

        val prevGpsTime = lastGpsTime
        lastGpsTime = System.currentTimeMillis()

        // Rattrapage odomètre après perte GPS (tunnel)
        if (prevGpsTime > 0 && extrapolationArmed) {
            val gapMs = lastGpsTime - prevGpsTime
            if (gapMs > EXTRAPOLATION_DELAY_MS) {
                sessionDistanceM += (extrapolationSpeed * gapMs) / 1000.0
                sessionMovingTimeMs += gapMs.toDouble()
                lastProcessTimeElapsed = SystemClock.elapsedRealtime()  // évite double-comptage
            }
        }

        if (!network.loaded) {
            // Chargement en cours ou échoué → n'affiche l'erreur que si définitif
            if (geoJsonLoadAttempts >= GEOJSON_RETRY_COUNT) {
                updateDisplay(null, geoJsonError = true, geoJsonErrorMsg = geoJsonErrorMsg)
            }
            return
        }

        lastSpeed = loc.speed
        adjustGpsFrequency(lastSpeed)

        // A2 : armer/désarmer extrapolation
        if (lastSpeed >= SPEED_THRESHOLD_MS && lastResult != null) {
            extrapolationStartPk = lastResult!!.pk
            extrapolationStartTime = lastGpsTime
            extrapolationSpeed = lastSpeed
            extrapolationArmed = true
        }

        // Odomètre — vitesse GPS × temps (Doppler GNSS, stable)
        // Distance = somme de lastSpeed × dt quand au-dessus du seuil
        // Temps de marche = somme des dt au-dessus du seuil
        // Vitesse moyenne = distance / temps_de_marche
        val nowElapsed = SystemClock.elapsedRealtime()
        val dtMs = if (lastProcessTimeElapsed > 0) nowElapsed - lastProcessTimeElapsed else 0L
        if (dtMs in 1..9999 && lastSpeed >= SPEED_THRESHOLD_MS) {
            sessionDistanceM += lastSpeed * (dtMs / 1000.0)  // m/s × s = m
            sessionMovingTimeMs += dtMs.toDouble()
        }
        lastProcessTimeElapsed = nowElapsed

        if (lastSpeed < SPEED_THRESHOLD_MS && lastResult != null) {
            speedGraceCounter++
            if (speedGraceCounter > SPEED_GRACE_READINGS) {
                // A3 : médiane des PK récents en mouvement
                if (recentPksRunning.isNotEmpty()) {
                    val sorted = recentPksRunning.sorted()
                    val median = sorted[sorted.size / 2]
                    lastResult = PkResult(median, lastResult!!.codeLigne, deadReckoning = true)
                }
                val r = lastResult ?: return
                updateDisplay(r, deadReckoning = true)
                return
            }
            // Encore dans le délai de grâce → utilise la dernière position
        } else {
            speedGraceCounter = 0
        }

        val nearest = network.nearest(loc.longitude, loc.latitude) ?: return
        val point = nearest.point

        var pk = point.pk
        var interpole = false
        val n = network.neighbors(point)
        if (n != null) {
            val interp = GeoUtils.interpolePk(n.first, n.second, loc.longitude, loc.latitude)
            if (interp != null) {
                pk = interp
                interpole = true
            }
        }

        val codeLigne = detectLine(point.codeLigne)

        lastResult = PkResult(pk, codeLigne, interpole)

        // A3 : buffer PK récents en mouvement
        recentPksRunning.addLast(pk)
        while (recentPksRunning.size > RECENT_PK_WINDOW) recentPksRunning.removeFirst()

        // A2 : direction extrapolation
        if (recentPksRunning.size >= 2) {
            val delta = recentPksRunning.last() - recentPksRunning.first()
            extrapolationDirection = if (delta >= 0) 1 else -1
        }

        updateDisplay(lastResult!!)
    }

    // ─── Affichage ──────────────────────────────────────────

    private fun updateDisplay(result: PkResult?, deadReckoning: Boolean = false, geoJsonError: Boolean = false, geoJsonErrorMsg: String? = null, extrapolating: Boolean = false) {
        pkText?.post {
            when {
                geoJsonError -> {
                    pkText?.text = "Erreur"
                    pkText?.setTextColor(Color.RED)
                    lineText?.text = geoJsonErrorMsg ?: "GeoJSON"
                    lineText?.setTextColor(Color.argb(200, 0, 0, 0))
                }
                result == null -> {
                    pkText?.text = "PAS"
                    pkText?.setTextColor(Color.argb(100, 0, 0, 0))
                    lineText?.text = "GPS"
                    lineText?.setTextColor(Color.argb(200, 0, 0, 0))
                }
                else -> {
                    val pkDisplay = if (deadReckoning || extrapolating) {
                        val tick = (result.pk * 5.0).toInt()
                        val intPart = tick / 5
                        val frac = (tick % 5) * 2
                        val dr = if (frac == 0) "${intPart}" else "${intPart},$frac"
                        "~$dr"
                    } else {
                        val tick = (result.pk * 5.0).toInt()
                        val intPart = tick / 5
                        val frac = (tick % 5) * 2
                        if (frac == 0) "${intPart}" else "${intPart},$frac"
                    }
                    // Décimales et virgule à 50% de la taille du PK
                    val spannable = SpannableString(pkDisplay)
                    val commaIdx = pkDisplay.indexOf(',')
                    if (commaIdx >= 0) {
                        spannable.setSpan(
                            RelativeSizeSpan(0.5f),
                            commaIdx,
                            pkDisplay.length,
                            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                    pkText?.text = spannable

                    if (deadReckoning) {
                        pkText?.setTextColor(Color.argb(100, 0, 0, 0))
                    } else {
                        pkText?.setTextColor(pkColorPref)
                    }
                    lineText?.text = result.codeLigne
                    lineText?.setTextColor(Color.argb(200, 0, 0, 0))
                    // Stats session
                    val avgKmh = if (sessionMovingTimeMs > 0) (sessionDistanceM / (sessionMovingTimeMs / 1000.0) * 3.6).toInt() else 0
                    speedText?.text = "Ø $avgKmh km/h"
                    val distKm = (sessionDistanceM / 1000.0)
                    distanceText?.text = "Σ %.1f km".format(distKm)
                }
            }
            // Ajustement manuel de la police si le texte dépasse
            val maxW = (resources.displayMetrics.widthPixels * 0.85).toInt()
            pkText!!.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
            adjustTextSize(lineText!!, LINE_TEXT_SIZE_SP, AUTO_SIZE_MIN_LINE_SP, maxW)
            adjustTextSize(speedText!!, LINE_TEXT_SIZE_SP, AUTO_SIZE_MIN_LINE_SP, maxW)
            adjustTextSize(distanceText!!, LINE_TEXT_SIZE_SP, AUTO_SIZE_MIN_LINE_SP, maxW)
        }
    }

    /** Réduit progressivement la police si le texte dépasse maxWidthPx. */
    private fun adjustTextSize(tv: TextView, maxSp: Float, minSp: Float, maxWidthPx: Int) {
        val text = tv.text?.toString() ?: return
        if (text.isEmpty()) return
        var size = maxSp
        while (size >= minSp) {
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (tv.paint.measureText(text) <= maxWidthPx) break
            size -= 1f
        }
    }

    // ─── Logique métier ─────────────────────────────────────

    private fun detectLine(candidate: String): String {
        lineHistory.addLast(candidate)
        while (lineHistory.size > HYSTERESIS) lineHistory.removeFirst()

        val allSame = lineHistory.all { it == candidate }
        if (allSame && lastResult != null && lastResult!!.codeLigne != candidate) {
            lineHistory.clear()
            lineHistory.addLast(candidate)
            return candidate
        }
        if (lastResult != null && !allSame) return lastResult!!.codeLigne
        return candidate
    }

    // ─── Chargement GeoJSON avec retry ─────────────────────

    private fun loadGeoJsonWithRetry() {
        Thread({
            while (geoJsonLoadAttempts < GEOJSON_RETRY_COUNT && !network.loaded) {
                try {
                    network.load(this@OverlayService)
                    if (network.loaded) {
                        val rt = Runtime.getRuntime()
                        val freeMB = rt.freeMemory() / (1024 * 1024)
                        val totalMB = rt.totalMemory() / (1024 * 1024)
                        android.util.Log.i("RailPK",
                            "GeoJSON chargé — mémoire: ${totalMB - freeMB}MB utilisés / ${totalMB}MB alloués")
                        break
                    }
                } catch (e: Exception) {
                    geoJsonErrorMsg = "${e.javaClass.simpleName}: ${e.message ?: "?"}"
                    android.util.Log.e("RailPK",
                        "Échec chargement GeoJSON (tentative ${geoJsonLoadAttempts + 1}/$GEOJSON_RETRY_COUNT) — $geoJsonErrorMsg", e)
                    if (e is java.lang.OutOfMemoryError) {
                        android.util.Log.e("RailPK", "⚠️ OUT OF MEMORY — heap insuffisant pour charger ${network.points.size} points")
                    } else if (e is java.io.IOException) {
                        android.util.Log.e("RailPK", "⚠️ IO ERROR — fichier introuvable ou illisible dans l'APK")
                    }
                }
                geoJsonLoadAttempts++
                if (geoJsonLoadAttempts < GEOJSON_RETRY_COUNT) {
                    try {
                        Thread.sleep(GEOJSON_RETRY_DELAY_MS)
                    } catch (_: InterruptedException) { break }
                }
            }
            if (!network.loaded) {
                geoJsonErrorMsg = network.lastError
                updateDisplay(null, geoJsonError = true, geoJsonErrorMsg = geoJsonErrorMsg)
            }
        }, "railpk-loader").start()
    }

    // ─── Helpers ────────────────────────────────────────────

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Overlay PK",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Service d'overlay PK"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Rail PK")
            .setContentText("Overlay PK actif")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            builder.setForegroundServiceBehavior(
                NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
            )
        }

        return builder.build()
    }

    // ─── Lifecycle ──────────────────────────────────────────

    override fun onDestroy() {
        isRunning = false
        gpsTimeoutHandler.removeCallbacks(gpsTimeoutRunnable)
        fusedLocation.removeLocationUpdates(locationCallback)
        locationManager.removeUpdates(locationListener)
        configCallbacks?.let { unregisterComponentCallbacks(it) }
        prefsListener?.let { getSharedPreferences("overlay", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(it) }
        wakeLock?.let { if (it.isHeld) it.release() }
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
