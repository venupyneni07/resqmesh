package org.resqmesh.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.Context
import android.content.ClipData
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.RotateDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.MediaStore
import android.text.InputFilter
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.resqmesh.app.core.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.File

/** All normal screens consume the same local-device state for both transports. */
class MainActivity : ComponentActivity() {
    private val navy = Color.rgb(17, 43, 55)
    private val teal = Color.rgb(18, 107, 100)
    private val cream = Color.rgb(246, 247, 248)
    private val line = Color.rgb(220, 226, 229)
    private val softTeal = Color.rgb(230, 243, 239)
    private val softRed = Color.rgb(255, 239, 235)
    private val muted = Color.rgb(89, 107, 114)
    private val red = Color.rgb(180, 51, 42)
    private val amber = Color.rgb(125, 81, 15)
    private lateinit var mesh: MeshController
    private var state = MeshUiState()
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var banner: TextView
    private lateinit var navigation: LinearLayout
    private lateinit var formActions: LinearLayout
    private lateinit var locationProvider: SosLocationProvider
    private lateinit var draftStore: SosDraftStore
    private var draftSaveWarningShown = false
    private var screen = "home"
    private var selectedReport: String? = null
    private val draft = mutableMapOf<String, String>()
    private val fields = mutableMapOf<String, EditText>()
    private var categoryIndex = -1
    private val quickNeeds = linkedSetOf<String>()
    private val expandedSections = linkedSetOf<String>()
    private var draftLocation = LocationContext()
    private var draftInitialized = false
    private var locationPending = false
    private var locationNotice: String? = null
    private var locationSummary: TextView? = null
    private var homeLocationBinding: Pair<TextView, SavedSosLocation?>? = null
    private var journeyLocationBinding: Pair<TextView, LocationContext>? = null
    private var reviewLocationBinding: Pair<TextView, LocationContext>? = null
    private var categoryError: TextView? = null
    private var reviewStatus: TextView? = null
    private var activeToast: Toast? = null
    private var generalSosConfirmation: AlertDialog? = null
    private var deviceLocationButton: Button? = null
    private val locationKeys = setOf("building", "location", "floor", "room", "zone")
    private val needsNames get() = linkedMapOf("cannot_move" to tr(R.string.ui_cannot_move), "cannot_speak" to tr(R.string.ui_cannot_speak), "people_injured" to tr(R.string.ui_people_injured))
    private var listPanel: LinearLayout? = null
    private var statusTitle: TextView? = null
    private var statusSubtitle: TextView? = null
    private var countLabel: TextView? = null
    private var connectionHint: TextView? = null
    private var relayHelp: Button? = null
    private var renderKey = ""
    private var reportsPage = 0
    private var attentionOnly = false
    private var carriedPage = 0
    private var journeyDetails = false
    private var submissionError: String? = null
    private var locationEditor: SavedSosLocation? = null
    private var locationReturn = "home"
    private var editorLocationSummary: TextView? = null
    private var editorLocationButton: Button? = null
    private var settingsExpanded = false
    private var simulationExpanded = false
    private var omitQuickLocation = false
    private val submission: SosSubmissionViewModel by viewModels()
    internal val mediaCapture: MediaCaptureViewModel by viewModels()
    private var mediaTimer: TextView? = null
    private var mediaRenderKey = ""
    private var sendVoiceWhenReady = false
    private var audioPlayer: MediaPlayer? = null
    private var videoPlayer: VideoView? = null
    private var mediaLocation: SavedSosLocation? = null
    // This activity extends ComponentActivity directly; the old transitive FragmentActivity is never used.
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val cameraResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val file = mediaCapture.pendingFile
        if (result.resultCode == RESULT_OK && file != null && file.length() == 0L && result.data?.data != null) {
            try {
                contentResolver.openInputStream(result.data!!.data!!)?.use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(8192); var total = 0L
                    while (true) { val count = input.read(buffer); if (count < 0) break; total += count
                        require(total <= 8_388_608L) { "Capture is too large" }; output.write(buffer, 0, count) }
                } }
            } catch (_: Exception) { file.delete() }
        }
        file?.let { runCatching { revokeUriPermission(FileProvider.getUriForFile(this, "$packageName.capture", it), Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        mediaCapture.cameraFinished(result.resultCode == RESULT_OK)
    }
    private val submitting get() = submission.state.value.pending
    private var submitButton: Button? = null
    private var settingsDirty = false
    private var backgroundStatus: TextView? = null
    private var backgroundToggle: Switch? = null
    private var syncingBackgroundToggle = false
    private val compactHeight get() = resources.configuration.screenHeightDp < 500 || resources.configuration.fontScale >= 1.6f
    private val categoryNames get() = listOf(tr(R.string.ui_medical), tr(R.string.ui_fire), tr(R.string.ui_flood), tr(R.string.ui_accident), tr(R.string.ui_trapped), tr(R.string.ui_safety_threat), tr(R.string.ui_other_not_sure))

    override fun attachBaseContext(newBase: Context) {
        val configuration = android.content.res.Configuration(newBase.resources.configuration)
        configuration.setLocale(Locale.ENGLISH)
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }
    private fun tr(id: Int, vararg args: Any): String = if (args.isEmpty()) getString(id) else getString(id, *args)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mesh = (application as MeshApplication).mesh
        locationProvider = SosLocationProvider(this)
        draftStore = SosDraftStore(this)
        omitQuickLocation = getSharedPreferences("resqmesh", 0).getBoolean("omitQuickLocation", false)
        state = mesh.state.value
        sendVoiceWhenReady = savedInstanceState?.getBoolean("sendVoiceWhenReady") ?: false
        mediaLocation = if (savedInstanceState?.containsKey("mediaLocationSaved") == true) savedInstanceState.getBundle("mediaLocation")?.let(::restoreMediaLocation)
            else availableQuickLocation()
        screen = savedInstanceState?.getString("screen") ?: "home"
        reportsPage = savedInstanceState?.getInt("reportsPage") ?: 0
        attentionOnly = savedInstanceState?.getBoolean("attentionOnly") ?: false
        journeyDetails = savedInstanceState?.getBoolean("journeyDetails") ?: false
        submissionError = savedInstanceState?.getString("submissionError")
        locationReturn = savedInstanceState?.getString("locationReturn") ?: "home"
        locationEditor = savedInstanceState?.getBundle("locationEditor")?.let(::restoreMediaLocation)
        settingsExpanded = savedInstanceState?.getBoolean("settingsExpanded") ?: false
        simulationExpanded = savedInstanceState?.getBoolean("simulationExpanded") ?: false
        selectedReport = savedInstanceState?.getString("report")
        categoryIndex = (savedInstanceState?.getInt("category", -1) ?: -1).coerceIn(-1, categoryNames.lastIndex)
        quickNeeds.addAll(savedInstanceState?.getStringArrayList("quickNeeds").orEmpty())
        expandedSections.addAll(savedInstanceState?.getStringArrayList("expandedSections").orEmpty())
        draftInitialized = savedInstanceState?.getBoolean("draftInitialized") ?: false
        savedInstanceState?.let { saved ->
            draftLocation = LocationContext(saved.getString("locationSource") ?: "unknown",
                saved.getLong("locationObservedAt").takeIf { it > 0 },
                saved.getDouble("latitude", Double.NaN).takeUnless { it.isNaN() },
                saved.getDouble("longitude", Double.NaN).takeUnless { it.isNaN() },
                saved.getDouble("accuracyM", Double.NaN).takeUnless { it.isNaN() })
            locationNotice = saved.getString("locationNotice")
            if (locationNotice?.startsWith("Finding location") == true)
                locationNotice = "Location lookup stopped. Use device location to try again."
        }
        for (key in listOf("text", "building", "location", "floor", "room", "zone", "people", "vulnerability"))
            draft[key] = savedInstanceState?.getString("draft_$key") ?: ""
        if (savedInstanceState == null) draftStore.load()?.let { saved ->
            draft.putAll(saved.fields); categoryIndex = saved.category; quickNeeds.addAll(saved.needs)
            expandedSections.addAll(saved.expanded); draftLocation = saved.location; draftInitialized = saved.initialized
        }
        val root = column().apply { setBackgroundColor(Color.WHITE) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else view.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(9), dp(20), dp(9))
        }
        header.addView(ImageView(this).apply {
            setImageDrawable(icon(R.drawable.ux_shield, red, 28)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(30), dp(34)).apply { marginEnd = dp(10) })
        val brand = column()
        brand.addView(label("ResQMesh", 19, navy, true))
        if (!compactHeight) brand.addView(label(tr(R.string.ui_help_starts_with_a_signal), 11, muted))
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(label("SOS", 12, red, true).apply {
            background = shape(softRed, 8); setPadding(dp(10), dp(5), dp(10), dp(5))
        })
        root.addView(header)
        banner = label(tr(R.string.ui_simulation_mode_virtual_devices), 11, amber, true).apply {
            setBackgroundColor(Color.rgb(255, 243, 214)); setPadding(dp(20), dp(6), dp(20), dp(6))
        }
        root.addView(banner)
        scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(cream) }
        content = column().apply { setPadding(dp(20), dp(18), dp(20), dp(24)) }
        scroll.addView(content); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        formActions = column().apply { setBackgroundColor(Color.WHITE); setPadding(dp(20), dp(10), dp(20), dp(10)); visibility = View.GONE }
        if (!compactHeight) root.addView(formActions)
        navigation = LinearLayout(this).apply { setBackgroundColor(Color.WHITE); setPadding(dp(8), dp(6), dp(8), dp(4)); elevation = dp(3).toFloat() }
        root.addView(navigation)
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        else if (Build.VERSION.SDK_INT >= 27) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        else window.navigationBarColor = navy
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when(screen) { "home" -> { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                    "lab" -> show("settings")
                    "location" -> show(locationReturn)
                    "journey" -> show(if (state.reports.any { it.report.id == selectedReport && it.report.originId != state.localNodeId }) "network" else "reports")
                    "review" -> show("form")
                    "media" -> { sendVoiceWhenReady = false; mediaCapture.stopVoice("Recording stopped. Your audio is ready when you return."); show("home") }
                    else -> show("home") }
            }
        })
        renderScreen()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                submission.state.collect { result ->
                    if (result.pending) {
                        submitButton?.isEnabled = false
                        reviewStatus?.text = tr(R.string.ui_saving_your_sos_on_this_device)
                        if (screen == "review") submitButton?.text = tr(R.string.ui_saving_sos)
                    } else if (result.reportId != null) {
                        submission.consumeResult()
                        keepDraft(); fields.clear()
                        if (result.clearDraft) {
                            draft.clear(); quickNeeds.clear(); expandedSections.clear(); categoryIndex = -1
                            draftLocation = LocationContext(); draftInitialized = false; locationNotice = null
                            draftStore.clear()
                        }
                        if (result.clearMedia) mediaCapture.consumeSaved()
                        selectedReport = result.reportId; journeyDetails = false; show("journey"); toast(tr(R.string.ui_sos_saved_on_this_device))
                    } else if (result.error != null) {
                        submissionError = result.error; submission.consumeResult(); renderScreen()
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mediaCapture.state.collect { capture ->
                    mediaTimer?.text = "${capture.seconds}s / 30s"
                    if (sendVoiceWhenReady && !capture.recording && !capture.processing) {
                        sendVoiceWhenReady = false
                        if (capture.attachment != null) sendMediaSos(capture.attachment)
                    }
                    val key = listOf(capture.kind, capture.phase, capture.attachment, capture.notice).toString()
                    if (screen in setOf("media", "home") && key != mediaRenderKey) { mediaRenderKey = key; renderScreen() }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Refresh age text while visible without replacing a fix or rebuilding an editing form.
                while (isActive) {
                    refreshLocationAges()
                    delay(30_000)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mesh.state.collect { next ->
                    val firstReady = !state.ready && next.ready
                    val modeChanged = state.simulation != next.simulation
                    val perspectiveChanged = state.localNodeId != next.localNodeId
                    state = next
                    banner.visibility = if (next.simulation) View.VISIBLE else View.GONE
                    if ((firstReady && screen == "settings" && !settingsDirty) ||
                        ((firstReady || modeChanged || perspectiveChanged) && screen !in setOf("form", "settings"))) renderScreen()
                    else updateLive()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (screen == "media" || screen == "journey") renderScreen()
        mesh.onAuthentication = { peer, code, answer -> runOnUiThread {
            if (isFinishing || isDestroyed) answer(false)
            else AlertDialog.Builder(this).setTitle("Verify nearby device")
                .setMessage("Compare the code on both phones:\n\n$code\n\n$peer\nOnly accept when the codes match.")
                .setPositiveButton("Codes match") { _, _ -> answer(true) }
                .setNegativeButton("Reject") { _, _ -> answer(false) }
                .setOnCancelListener { answer(false) }.show()
        } }
        mesh.foreground(); mesh.refresh()
    }
    override fun onStop() {
        keepDraft()
        activeToast?.cancel()
        generalSosConfirmation?.dismiss()
        stopPlayback()
        mediaCapture.stopVoice("Recording stopped because ResQMesh left the screen. Review it before sending.")
        locationProvider.cancel()
        if (locationPending) { locationPending = false; locationNotice = "Location lookup stopped. You can try again or send without it."; updateLocationSummary() }
        mesh.onAuthentication = null; mesh.background(); super.onStop()
    }
    override fun onSaveInstanceState(out: Bundle) {
        keepDraft(); out.putString("screen", screen); out.putString("report", selectedReport); out.putInt("category", categoryIndex)
        out.putInt("reportsPage", reportsPage); out.putBoolean("attentionOnly", attentionOnly)
        out.putBoolean("journeyDetails", journeyDetails); out.putString("submissionError", submissionError)
        out.putString("locationReturn", locationReturn); locationEditor?.let { out.putBundle("locationEditor", locationBundle(it)) }
        out.putBoolean("settingsExpanded", settingsExpanded); out.putBoolean("simulationExpanded", simulationExpanded)
        out.putStringArrayList("quickNeeds", ArrayList(quickNeeds)); out.putStringArrayList("expandedSections", ArrayList(expandedSections))
        out.putBoolean("draftInitialized", draftInitialized); out.putString("locationSource", draftLocation.source)
        out.putLong("locationObservedAt", draftLocation.observedAt ?: 0)
        out.putDouble("latitude", draftLocation.latitude ?: Double.NaN); out.putDouble("longitude", draftLocation.longitude ?: Double.NaN)
        out.putDouble("accuracyM", draftLocation.accuracyM ?: Double.NaN); out.putString("locationNotice", locationNotice)
        out.putBoolean("sendVoiceWhenReady", sendVoiceWhenReady)
        out.putBoolean("mediaLocationSaved", true)
        mediaLocation?.let { where -> out.putBundle("mediaLocation", Bundle().apply {
            putString("building", where.building); putString("zone", where.zone); putString("locationText", where.locationText)
            putString("floor", where.floor); putString("room", where.room); putString("source", where.context.source)
            putLong("observedAt", where.context.observedAt ?: 0); putDouble("latitude", where.context.latitude ?: Double.NaN)
            putDouble("longitude", where.context.longitude ?: Double.NaN); putDouble("accuracyM", where.context.accuracyM ?: Double.NaN)
        }) }
        draft.forEach { (key, value) -> out.putString("draft_$key", value) }; super.onSaveInstanceState(out)
    }
    private fun keepDraft() {
        fields.forEach { (key, field) -> draft[key] = field.text.toString() }
        persistDraft()
    }
    private fun persistDraft() {
        if (!::draftStore.isInitialized) return
        if (hasTypedDraft() || locationKeys.any { !draft[it].isNullOrBlank() } || draftLocation.latitude != null) {
            if (!draftStore.save(SavedSosDraft(draft.toMap(), categoryIndex, quickNeeds.toSet(), expandedSections.toSet(), draftLocation, draftInitialized)) && !draftSaveWarningShown) {
                draftSaveWarningShown = true
                toast(tr(R.string.ui_your_draft_is_still_on_this_screen_but_recovery_storage_is_unavailable))
            }
        } else if (!draftStore.restoreFailed) draftStore.clear()
    }
    private fun show(next: String) {
        activeToast?.cancel()
        keepDraft()
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(content.windowToken, 0)
        if (screen == "media" && next != "media") {
            mediaCapture.cancelPermissionRequest()
            mediaCapture.stopVoice("Recording stopped. Your audio remains on this device.")
        }
        if (screen in setOf("location", "form") && next != screen) { locationProvider.cancel(); locationPending = false }
        screen = next; renderScreen(); scroll.scrollTo(0, 0); scroll.post { scroll.scrollTo(0, 0) }
    }
    private fun renderScreen() {
        stopPlayback(); mediaTimer = null; editorLocationSummary = null; editorLocationButton = null
        backgroundStatus = null; backgroundToggle = null
        fields.clear(); homeLocationBinding = null; journeyLocationBinding = null; reviewLocationBinding = null; categoryError = null; reviewStatus = null; locationSummary = null; deviceLocationButton = null; submitButton = null; formActions.removeAllViews(); formActions.visibility = View.GONE; listPanel = null; statusTitle = null; statusSubtitle = null; countLabel = null
        connectionHint = null; relayHelp = null; renderKey = ""; content.removeAllViews(); navigation.removeAllViews()
        val destinations = listOf(Triple("home", tr(R.string.ui_home), R.drawable.ux_house), Triple("reports", tr(R.string.ui_reports), R.drawable.ux_inbox),
            Triple("network", tr(R.string.ui_network), R.drawable.ux_network), Triple("settings", tr(R.string.ui_settings), R.drawable.ux_settings))
        for ((key, title, glyph) in destinations) {
            val active = screen == key || (key == "home" && screen in setOf("form", "review", "media", "location")) ||
                (key == "reports" && screen == "journey") || (key == "settings" && screen == "lab")
            navigation.addView(Button(this).apply {
                text = title; isAllCaps = false; textSize = 12f; minHeight = dp(if (compactHeight) 48 else 60); minimumWidth = 0
                setTextColor(if (active) teal else muted); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = ripple(if (active) softTeal else Color.WHITE, 12); stateListAnimator = null
                if (!compactHeight) setCompoundDrawables(null, icon(glyph, if (active) teal else muted, 22), null, null)
                compoundDrawablePadding = dp(3); setPadding(dp(2), dp(5), dp(2), dp(4))
                isSelected = active
                if (Build.VERSION.SDK_INT >= 30) stateDescription = if (active) tr(R.string.ui_current_tab) else null
                setOnClickListener { show(key) }
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(3); marginEnd = dp(3) })
        }
        when(screen) { "form" -> formScreen(); "review" -> reviewScreen(); "media" -> mediaScreen(); "location" -> locationEditorScreen()
            "reports" -> reportsScreen(); "journey" -> journeyScreen(); "network" -> networkScreen()
            "settings" -> settingsScreen(); "lab" -> labScreen(); else -> homeScreen() }
        if (compactHeight) {
            (formActions.parent as? ViewGroup)?.removeView(formActions)
            content.addView(formActions, spaced(16))
        }
        updateLive()
    }
    private fun heading(title: String, subtitle: String? = null) {
        if (Build.VERSION.SDK_INT >= 28) content.accessibilityPaneTitle = title
        content.addView(label(title, 26, navy, true).apply { if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }); subtitle?.let { content.addView(label(it, 14, muted), spaced(6)) }
    }
    private fun homeScreen() {
        heading(tr(R.string.ui_need_help), tr(R.string.ui_speak_show_or_choose_what_you_need))
        if (draftStore.restoreFailed) notice(content, tr(R.string.ui_unsent_draft), tr(R.string.ui_could_not_restore_the_unsent_draft_existing_saved_reports_are_unchange))
        val quickLocation = availableQuickLocation()
        val options = listOf(
            Triple(tr(R.string.ui_record_voice), R.drawable.ux_mic) { startMediaCapture("audio") },
            Triple(tr(R.string.ui_take_photo), R.drawable.ux_camera) { startMediaCapture("image") },
            Triple(tr(R.string.ui_record_video), R.drawable.ux_video) { startMediaCapture("video") },
            Triple(tr(R.string.ui_choose_help), R.drawable.ux_file_text) { show("form") })
        options.chunked(if (resources.configuration.fontScale >= 1.6f || resources.configuration.screenWidthDp < 340) 1 else 2).forEachIndexed { rowIndex, entries ->
            val row = LinearLayout(this)
            entries.forEachIndexed { index, (title, glyph, click) ->
                row.addView(tile(title, glyph, title == tr(R.string.ui_record_voice), click), LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (index == 0) marginEnd = dp(6) else marginStart = dp(6)
                })
            }
            content.addView(row, spaced(if (rowIndex == 0) 18 else 12))
        }
        content.addView(label(tr(R.string.ui_can_t_record_or_type), 13, muted), spaced(18))
        val send = action(tr(R.string.ui_general_sos), red) { confirmGeneralSos(quickLocation) }.apply {
            isEnabled = !submitting; textSize = 16f
            setCompoundDrawables(icon(R.drawable.ux_send, Color.WHITE, 20), null, null, null); compoundDrawablePadding = dp(8)
        }
        submitButton = send; content.addView(send, spaced(7))
        content.addView(label(tr(R.string.ui_review_and_confirm_a_general_help_request_no_typing_needed), 12, muted), spaced(6))
        submissionFailure(content)
        val where = card()
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageView(this).apply { setImageDrawable(icon(R.drawable.ux_map_pin, teal, 22)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(dp(24), dp(30)).apply { marginEnd = dp(10) })
        val text = column()
        text.addView(label(if (omitQuickLocation) tr(R.string.ui_location_excluded) else if (quickLocation == null) tr(R.string.ui_location_unknown) else savedLocationTitle(quickLocation), 14, navy, true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        val age = label(compactLocationDescription(quickLocation), 12, muted)
        homeLocationBinding = age to quickLocation; text.addView(age, spaced(3))
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f)); where.addView(row)
        where.addView(back(if (quickLocation == null) tr(R.string.ui_add_location) else tr(R.string.ui_change_location)) { openLocationEditor("home", quickLocation) })
        content.addView(where, spaced(16))
        val connection = card()
        statusTitle = label(tr(R.string.ui_starting), 14, navy, true); connection.addView(statusTitle)
        countLabel = label("", 12, muted); connection.addView(countLabel, spaced(4))
        connection.addView(back(tr(R.string.ui_connection_details)) { show("network") })
        content.addView(connection, spaced(10))
        if (mediaCapture.state.value.attachment != null) {
            val unsent = card(); unsent.addView(label(tr(R.string.ui_unsent_media), 15, navy, true))
            unsent.addView(label(tr(R.string.ui_saved_on_this_phone_nothing_sent_yet), 13, muted), spaced(4))
            unsent.addView(back(tr(R.string.ui_continue_unsent_media_sos)) { reviewMediaDraft() }); content.addView(unsent, spaced(12))
        }
        if (hasTypedDraft()) {
            val unsent = card(); unsent.addView(label(tr(R.string.ui_unsent_draft), 15, navy, true))
            unsent.addView(label(tr(R.string.ui_your_selected_help_and_details_are_kept_here), 13, muted), spaced(4))
            unsent.addView(back(tr(R.string.ui_resume_draft)) { show("form") })
            unsent.addView(back(tr(R.string.ui_discard_draft)) { confirmDiscardDraft() }); content.addView(unsent, spaced(12))
        }
        sectionLabel(tr(R.string.ui_latest_sos), tr(R.string.ui_your_full_history_is_in_reports))
        listPanel = column(); content.addView(listPanel)
        paragraph(tr(R.string.ui_keep_resqmesh_open_to_relay_use_official_emergency_services_when_avail))
    }
    private fun hasTypedDraft() = categoryIndex >= 0 || quickNeeds.isNotEmpty() || draft.any { it.key !in locationKeys && it.value.isNotBlank() }
    private fun confirmDiscardDraft() {
        if(submitting) return
        AlertDialog.Builder(this).setTitle(tr(R.string.ui_discard_unsent_draft)).setMessage(tr(R.string.ui_your_selected_help_and_typed_details_will_be_removed_sent_reports_are_))
            .setNegativeButton(tr(R.string.ui_keep_draft), null).setPositiveButton(tr(R.string.ui_discard)) { _, _ ->
                draft.clear(); fields.clear(); quickNeeds.clear(); expandedSections.clear(); categoryIndex = -1
                draftInitialized = false; draftLocation = LocationContext(); submissionError = null; draftStore.clear(); show("home")
            }.show()
    }
    private fun reportsScreen() {
        heading(tr(R.string.ui_your_reports), tr(R.string.ui_check_what_was_saved_and_who_has_received_it))
        val filters = LinearLayout(this)
        listOf(tr(R.string.ui_all_reports) to false, tr(R.string.ui_needs_attention) to true).forEachIndexed { index, (title, attention) ->
            filters.addView(choice(title, attentionOnly == attention) { attentionOnly = attention; reportsPage = 0; renderScreen(); scroll.scrollTo(0, 0) },
                LinearLayout.LayoutParams(0, -2, 1f).apply { if (index == 0) marginEnd = dp(6) })
        }
        content.addView(filters, spaced(18))
        content.addView(label(tr(R.string.ui_needs_attention_no_upload_or_receipt_recorded_including_expired_report), 12, muted), spaced(8))
        listPanel = column(); content.addView(listPanel, spaced(6))
    }
    private fun quickDraft(location: SavedSosLocation?) = SosDraft(
        building = location?.building.orEmpty(), zone = location?.zone.orEmpty(), locationText = location?.locationText.orEmpty(),
        floor = location?.floor.orEmpty(), room = location?.room.orEmpty(), locationContext = location?.context ?: LocationContext())
    private fun confirmGeneralSos(location: SavedSosLocation?) {
        if (submitting || generalSosConfirmation?.isShowing == true) return
        // Freeze the exact information shown here; lookup or an unsent draft cannot alter it.
        val packet = quickDraft(location)
        val message = tr(R.string.general_sos_confirmation, QUICK_SOS_MESSAGE, quickLocationDescription(location),
            if (location == null) tr(R.string.unknown_location_warning) else "")
        var confirmed = false
        val dialog = AlertDialog.Builder(this)
            .setTitle(tr(R.string.ui_send_general_sos))
            .setMessage(message)
            .setNegativeButton(tr(R.string.ui_cancel), null)
            .setPositiveButton(tr(R.string.ui_send_sos)) { _, _ ->
                if (!confirmed) { confirmed = true; submitSos(packet, clearDraft = false) }
            }.create()
        generalSosConfirmation = dialog
        dialog.setOnDismissListener { if (generalSosConfirmation === dialog) generalSosConfirmation = null }
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(red) }
        dialog.show()
    }
    private fun restoreMediaLocation(saved: Bundle) = SavedSosLocation(building = saved.getString("building").orEmpty(),
        zone = saved.getString("zone").orEmpty(), locationText = saved.getString("locationText").orEmpty(),
        floor = saved.getString("floor").orEmpty(), room = saved.getString("room").orEmpty(),
        context = LocationContext(saved.getString("source") ?: "unknown", saved.getLong("observedAt").takeIf { it > 0 },
            saved.getDouble("latitude", Double.NaN).takeUnless { it.isNaN() }, saved.getDouble("longitude", Double.NaN).takeUnless { it.isNaN() },
            saved.getDouble("accuracyM", Double.NaN).takeUnless { it.isNaN() }))
    internal fun reviewMediaDraft() { show("media") }

    private fun startMediaCapture(kind: String) {
        if (submitting) return
        if (mediaCapture.state.value.attachment != null) {
            show("media")
            toast("Your unsent capture is here. Send it, or discard it before starting another.")
            return
        }
        val needsPermission = kind == "audio" && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        if (!mediaCapture.selectCapture(kind, needsPermission)) { show("media"); return }
        sendVoiceWhenReady = false
        submissionError = null
        mediaLocation = availableQuickLocation()
        show("media")
        if (kind == "audio") {
            if (needsPermission) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 72)
            else mediaCapture.startVoice()
        } else launchCamera(kind)
    }

    private fun launchCamera(kind: String) {
        try {
            val intent = Intent(if (kind == "image") MediaStore.ACTION_IMAGE_CAPTURE else MediaStore.ACTION_VIDEO_CAPTURE)
            if (intent.resolveActivity(packageManager) == null) {
                mediaCapture.fail("No camera app is available for ${if (kind == "image") "photos" else "video"}. Choose another method or send SOS without media."); return
            }
            val file = mediaCapture.beginCamera(kind)
            val uri = FileProvider.getUriForFile(this, "$packageName.capture", file)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)
            intent.clipData = ClipData.newRawUri("Emergency capture", uri)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (kind == "video") {
                // Leave room for muxer padding while the actual file remains under the 15-second contract.
                intent.putExtra(MediaStore.EXTRA_DURATION_LIMIT, 14)
                intent.putExtra(MediaStore.EXTRA_SIZE_LIMIT, 8_388_608L)
                intent.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0)
            }
            cameraResult.launch(intent)
        } catch (error: Exception) {
            android.util.Log.w("ResQMeshCapture", "Could not launch $kind camera", error)
            mediaCapture.cameraFinished(false)
            mediaCapture.fail("Could not open the ${if (kind == "image") "photo" else "video"} camera. Try again or choose another method.")
        }
    }

    private fun sendMediaSos(attachment: DraftAttachment) {
        if (submitting) return
        stopPlayback()
        submitSos(quickDraft(mediaLocation).copy(attachments = listOf(attachment)), clearDraft = false)
    }

    private fun mediaScreen() {
        val capture = mediaCapture.state.value
        mediaRenderKey = listOf(capture.kind, capture.phase, capture.attachment, capture.notice).toString()
        content.addView(back(tr(R.string.ui_back_to_home)) { sendVoiceWhenReady = false; show("home") })
        heading(when { capture.recording -> tr(R.string.ui_recording_your_sos); capture.processing -> tr(R.string.ui_saving_your_capture)
            capture.attachment != null -> tr(R.string.ui_ready_to_send)
            capture.phase == CapturePhase.REQUESTING_PERMISSION -> tr(R.string.ui_microphone_permission)
            capture.phase in setOf(CapturePhase.STARTING, CapturePhase.WAITING_CAMERA) -> when (capture.kind) {
                "image" -> tr(R.string.ui_opening_photo_camera); "video" -> tr(R.string.ui_opening_video_camera); else -> tr(R.string.ui_starting_voice_recording) }
            capture.kind == "image" -> tr(R.string.ui_no_photo_captured)
            capture.kind == "video" -> tr(R.string.ui_no_video_captured)
            capture.kind == "audio" -> tr(R.string.ui_voice_recording_unavailable)
            else -> tr(R.string.ui_choose_a_capture_method) })
        if (capture.recording) {
            paragraph(tr(R.string.ui_describe_what_happened_and_where_you_are_keep_it_short_you_can_stop_an))
            val recording = card()
            recording.addView(label(tr(R.string.ui_microphone_on), 14, red, true))
            mediaTimer = label("${capture.seconds}s / 30s", 38, navy, true)
            recording.addView(mediaTimer, spaced(14)); content.addView(recording, spaced(18))
            paragraph(tr(R.string.ui_only_this_recording_is_attached_resqmesh_stops_recording_when_you_leav))
            formActions.visibility = View.VISIBLE
            formActions.addView(action(tr(R.string.ui_stop_send_sos), red) {
                sendVoiceWhenReady = true
                mediaCapture.stopVoice()
            })
            formActions.addView(back(tr(R.string.ui_stop_review_first)) { mediaCapture.stopVoice() })
            content.addView(back(tr(R.string.ui_cancel_recording)) { sendVoiceWhenReady = false; mediaCapture.discard(); show("home") }, spaced())
        } else if (capture.processing) {
            paragraph(tr(R.string.ui_preparing_a_small_attachment_nothing_has_been_sent_yet))
        } else if (capture.attachment != null) {
            val attachment = capture.attachment
            val media = card()
            media.addView(label("${mediaName(attachment.metadata.kind)} · ${mediaSize(attachment.metadata.byteSize)}" +
                (attachment.metadata.durationMs?.let { " · ${String.format(Locale.ROOT, "%.1f", it / 1000.0)}s" } ?: ""), 16, navy, true))
            addMediaPreview(media, attachment.metadata.kind, attachment.filePath)
            content.addView(media, spaced(16))
            paragraph(tr(R.string.ui_your_capture_is_ready_add_a_location_if_you_can_then_send_typing_is_op))
            val location = card()
            location.addView(label(tr(R.string.ui_location_to_include), 11, muted, true))
            location.addView(label(quickLocationDescription(mediaLocation), 13, muted), spaced(7))
            location.addView(back(tr(R.string.ui_change_location)) { if (!submitting) openLocationEditor("media", mediaLocation) }.apply { isEnabled = !submitting })
            content.addView(location, spaced(12))
            content.addView(back(tr(R.string.ui_discard_try_again)) {
                if (submitting) return@back
                val kind = attachment.metadata.kind; mediaCapture.discard(); startMediaCapture(kind)
            }.apply { isEnabled = !submitting }, spaced())
            content.addView(back(tr(R.string.ui_discard_capture)) { if (!submitting) { mediaCapture.discard(); show("home") } }.apply { isEnabled = !submitting })
            formActions.visibility = View.VISIBLE
            val send = action(if (submitting) tr(R.string.ui_saving_sos) else tr(when (attachment.metadata.kind) { "audio" -> R.string.ui_send_voice_sos; "image" -> R.string.ui_send_photo_sos; else -> R.string.ui_send_video_sos }), red) {
                sendMediaSos(attachment)
            }.apply { isEnabled = !submitting }
            submitButton = send; formActions.addView(send)
            formActions.addView(label(tr(R.string.ui_sos_is_saved_first_media_delivery_may_take_longer), 12, muted), spaced(6))
        } else if (capture.phase == CapturePhase.REQUESTING_PERMISSION) {
            paragraph(tr(R.string.ui_allow_microphone_access_in_the_android_prompt_to_start_your_voice_mess))
        } else if (capture.phase in setOf(CapturePhase.STARTING, CapturePhase.WAITING_CAMERA)) {
            paragraph(if (capture.kind == "audio") tr(R.string.ui_getting_the_microphone_ready_recording_stays_on_this_screen)
                else "Use the camera to ${if (capture.kind == "image") "take your photo" else "record your video"}, then confirm it. Nothing is sent until you review and send.")
        } else {
            capture.notice?.let { notice(content, tr(R.string.ui_nothing_was_sent), it) }
            capture.kind?.let { kind ->
                content.addView(action(when(kind) { "image" -> tr(R.string.ui_try_photo_again); "video" -> tr(R.string.ui_try_video_again); else -> tr(R.string.ui_try_voice_again) }, red) {
                    startMediaCapture(kind)
                }, spaced(18))
                if (kind == "audio" && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED &&
                    !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                    content.addView(secondary(tr(R.string.ui_open_microphone_settings), R.drawable.ux_settings) {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }, spaced(10))
                }
            }
            content.addView(back(tr(R.string.ui_choose_another_method)) { show("home") }, spaced(12))
        }
        if (capture.attachment != null) capture.notice?.let { notice(content, tr(R.string.ui_about_this_capture), it) }
        submissionFailure(content)
        if (!capture.recording && !capture.processing && capture.phase !in setOf(CapturePhase.STARTING, CapturePhase.REQUESTING_PERMISSION, CapturePhase.WAITING_CAMERA)) content.addView(back(tr(R.string.ui_send_sos_without_media)) {
            sendVoiceWhenReady = false; confirmGeneralSos(mediaLocation)
        }, spaced(12))
    }

    private fun compactLocationDescription(location: SavedSosLocation?): String {
        if (location == null) return tr(R.string.ui_optional_add_a_landmark_if_you_can)
        val source = when (location.context.source) { "device" -> tr(R.string.ui_device_coordinates); "saved" -> tr(R.string.ui_saved_place); "manual" -> tr(R.string.ui_entered_by_you); else -> tr(R.string.ui_location_details) }
        return "$source · ${location.context.observedAt?.let(::locationAge) ?: "time unknown"}"
    }
    private fun availableQuickLocation(): SavedSosLocation? = if (omitQuickLocation) null else
        mesh.savedSosLocation() ?: locationProvider.lastKnown()?.let { SavedSosLocation(context = it) }
    private fun locationBundle(where: SavedSosLocation) = Bundle().apply {
        putString("building", where.building); putString("zone", where.zone); putString("locationText", where.locationText)
        putString("floor", where.floor); putString("room", where.room); putString("source", where.context.source)
        putLong("observedAt", where.context.observedAt ?: 0); putDouble("latitude", where.context.latitude ?: Double.NaN)
        putDouble("longitude", where.context.longitude ?: Double.NaN); putDouble("accuracyM", where.context.accuracyM ?: Double.NaN)
    }
    private fun openLocationEditor(returnTo: String, current: SavedSosLocation?) {
        locationProvider.cancel(); locationPending = false
        locationReturn = returnTo; locationEditor = current; locationNotice = null; show("location")
    }
    private fun locationEditorScreen() {
        content.addView(back(if (locationReturn == "media") tr(R.string.ui_back_to_capture) else tr(R.string.ui_back_to_home)) { show(locationReturn) })
        heading(tr(R.string.ui_location_for_sos), tr(R.string.ui_help_responders_find_you_location_is_optional))
        val where = card()
        where.addView(label(tr(R.string.ui_location_details_heading), 11, muted, true))
        editorLocationSummary = label(listOfNotNull(quickLocationDescription(locationEditor), locationNotice).joinToString("\n"), 14, muted).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        where.addView(editorLocationSummary, spaced(9))
        val caption = label(tr(R.string.ui_landmark_address), 13, navy, true)
        where.addView(caption, spaced(16))
        val existing = locationEditor?.let { listOf(it.building, it.locationText, it.floor.takeIf(String::isNotBlank)?.let { floor -> "Floor $floor" }.orEmpty(),
            it.room.takeIf(String::isNotBlank)?.let { room -> "Room $room" }.orEmpty(), it.zone).filter(String::isNotBlank).joinToString(" · ") }.orEmpty()
        val address = field(tr(R.string.ui_a_building_street_or_visible_landmark), existing, 240).apply { id = View.generateViewId(); minLines = 2 }
        caption.labelFor = address.id; where.addView(address, spaced(6))
        address.addTextChangedListener(afterChanged {
            locationProvider.cancel(); locationPending = false
            val entered = address.text.toString().trim()
            locationEditor = entered.takeIf(String::isNotBlank)?.let { SavedSosLocation(locationText = it, context = LocationContext("manual", System.currentTimeMillis())) }
            locationNotice = null; updateLocationSummary()
        })
        editorLocationButton = secondary(tr(R.string.ui_use_device_location), R.drawable.ux_map_pin) {
            if (!locationProvider.hasPermission()) requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 73)
            else acquireEditorLocation()
        }
        where.addView(editorLocationButton, spaced(14))
        mesh.savedSosLocation()?.let { saved -> where.addView(back(tr(R.string.ui_use_saved_location)) {
            locationProvider.cancel(); locationPending = false; locationEditor = saved; locationNotice = null; renderScreen()
        }) }
        content.addView(where, spaced(16))
        notice(content, tr(R.string.ui_check_before_sending), tr(R.string.ui_a_saved_place_may_be_outdated_device_coordinates_can_also_be_unavailab))
        formActions.visibility = View.VISIBLE
        formActions.addView(action(tr(R.string.ui_use_this_location)) { applyEditorLocation(locationEditor) })
        formActions.addView(back(tr(R.string.ui_continue_without_location)) { applyEditorLocation(null) })
        updateLocationSummary()
    }
    private fun acquireEditorLocation() {
        if (screen != "location" || locationPending) return
        locationPending = true; locationNotice = tr(R.string.ui_finding_location_you_can_continue_without_waiting); updateLocationSummary()
        locationProvider.request { fix ->
            if (isDestroyed || isFinishing || screen != "location") return@request
            locationPending = false
            if (fix != null) { locationEditor = SavedSosLocation(context = fix); locationNotice = "Device coordinates ready. Entering a landmark replaces these coordinates."; renderScreen() }
            else { locationNotice = tr(R.string.ui_no_device_location_found_add_a_landmark_or_continue_without_location); updateLocationSummary() }
        }
    }
    private fun applyEditorLocation(where: SavedSosLocation?) {
        locationProvider.cancel(); locationPending = false
        if (locationReturn == "media") mediaLocation = where
        else {
            if (!mesh.saveSosLocation(where)) { locationNotice = "Could not save the location. Try again or continue without it."; updateLocationSummary(); return }
            omitQuickLocation = where == null
            getSharedPreferences("resqmesh", 0).edit().putBoolean("omitQuickLocation", omitQuickLocation).apply()
        }
        locationNotice = null; show(locationReturn)
    }

    private fun mediaName(kind: String) = when (kind) { "audio" -> tr(R.string.ui_voice_message); "image" -> tr(R.string.ui_photo); "video" -> tr(R.string.ui_video); else -> tr(R.string.ui_attachment) }
    private fun mediaSize(bytes: Long) = if (bytes < 1_048_576) "${(bytes / 1024).coerceAtLeast(1)} KB" else String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
    private fun addMediaPreview(parent: LinearLayout, kind: String, path: String) {
        val file = File(path)
        if (!file.isFile) { parent.addView(label(tr(R.string.ui_attachment_is_unavailable_on_this_device), 14, amber), spaced()); return }
        if (kind == "image") {
            val photo = ImageView(this).apply {
                contentDescription = tr(R.string.ui_photo_attached_to_this_sos); adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(BitmapFactory.decodeFile(path))
            }
            parent.addView(photo, LinearLayout.LayoutParams(-1, dp(210)).apply { topMargin = dp(12) })
        } else if (kind == "audio") {
            val button = action(tr(R.string.ui_play_voice_message), navy) { }
            button.setOnClickListener {
                if (audioPlayer?.isPlaying == true) { stopPlayback(); button.text = tr(R.string.ui_play_voice_message) }
                else try {
                    stopPlayback()
                    audioPlayer = MediaPlayer().apply {
                        setDataSource(path); prepare(); setOnCompletionListener { stopPlayback(); button.text = tr(R.string.ui_play_voice_message) }
                        setOnErrorListener { _, _, _ -> stopPlayback(); button.text = tr(R.string.ui_play_voice_message); toast("Could not play this attachment."); true }
                        start()
                    }
                    button.text = tr(R.string.ui_stop_playback)
                } catch (_: Exception) { stopPlayback(); toast("Could not play this attachment.") }
            }
            parent.addView(button, spaced(12))
        } else if (kind == "video") {
            val video = VideoView(this).apply {
                contentDescription = "Video attached to this SOS"; setVideoURI(Uri.fromFile(file))
                setOnErrorListener { _, _, _ -> toast("Could not play this attachment."); true }
            }
            videoPlayer = video
            video.setOnPreparedListener { video.seekTo(1) }
            parent.addView(video, LinearLayout.LayoutParams(-1, dp(210)).apply { topMargin = dp(12) })
            val play = action(tr(R.string.ui_play_video), navy) { }
            play.setOnClickListener {
                if (video.isPlaying) { video.pause(); play.text = tr(R.string.ui_play_video) }
                else { video.start(); play.text = "Pause video" }
            }
            video.setOnCompletionListener { play.text = tr(R.string.ui_play_video) }
            parent.addView(play, spaced())
        }
    }
    private fun stopPlayback() {
        try { audioPlayer?.release() } catch (_: Exception) { }; audioPlayer = null
        videoPlayer?.stopPlayback(); videoPlayer = null
    }

    private fun formScreen() {
        if (!draftInitialized) {
            mesh.savedSosLocation()?.let(::applyLocation)
            if (!omitQuickLocation && draftLocation.source == "unknown") locationProvider.lastKnown()?.let { draftLocation = it }
            draftInitialized = true
        }
        content.addView(back(tr(R.string.ui_back_to_home)) { show("home") })
        content.addView(label(tr(R.string.ui_step_1_of_2_details), 11, teal, true), spaced(4))
        heading(tr(R.string.ui_what_help_do_you_need), tr(R.string.ui_tap_one_option_you_can_send_without_typing))
        val choices = card()
        choices.addView(label(tr(R.string.ui_emergency_type), 15, navy, true))
        categoryError = label(tr(R.string.ui_choose_an_emergency_type_or_other_not_sure), 13, red).apply { visibility = View.GONE }
        choices.addView(categoryError, spaced(5))
        val categoryButtons = mutableListOf<Button>()
        categoryNames.withIndex().toList().chunked(if (resources.configuration.fontScale > 1.15f) 2 else 3).forEach { options ->
            val row = LinearLayout(this)
            options.forEach { (index, name) ->
                val button = choice(name, categoryIndex == index) {
                    categoryIndex = index
                    categoryError?.visibility = View.GONE
                    categoryButtons.forEachIndexed { i, b -> paintChoice(b, i == index) }
                    persistDraft()
                }
                categoryButtons.add(button)
                row.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
            }
            choices.addView(row, spaced(7))
        }
        choices.addView(label(tr(R.string.ui_quick_needs), 15, navy, true), spaced(18))
        needsNames.forEach { (key, name) ->
            choices.addView(CheckBox(this).apply {
                text = name; textSize = 16f; setTextColor(navy); minHeight = dp(48); isChecked = key in quickNeeds
                buttonTintList = android.content.res.ColorStateList.valueOf(teal)
                setOnCheckedChangeListener { _, checked -> if (checked) quickNeeds.add(key) else quickNeeds.remove(key); persistDraft() }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        content.addView(choices, spaced(14))
        optionalSection("people", tr(R.string.ui_people_affected)) { body ->
            body.addView(label(tr(R.string.ui_leave_unknown_if_you_are_unsure), 13, muted))
            val countButtons = mutableListOf<Button>()
            listOf(tr(R.string.ui_unknown), "1", "2", "3", "4", "5").chunked(3).forEach { options ->
                val row = LinearLayout(this)
                options.forEach { name ->
                    val value = if (name == tr(R.string.ui_unknown)) "" else name
                    val button = choice(name, draft["people"].orEmpty() == value) {
                        draft["people"] = value; fields["people"]?.setText(value)
                        countButtons.forEach { paintChoice(it, (if (it.text.toString() == tr(R.string.ui_unknown)) "" else it.text.toString()) == value) }
                        persistDraft()
                    }; countButtons.add(button)
                    row.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
                }; body.addView(row, spaced(7))
            }
            addField(body, "people", tr(R.string.ui_or_enter_another_number), tr(R.string.ui_unknown), 5, numeric = true)
            fields["people"]?.addTextChangedListener(afterChanged {
                val value = fields["people"]?.text.toString()
                countButtons.forEach { paintChoice(it, (if (it.text.toString() == tr(R.string.ui_unknown)) "" else it.text.toString()) == value) }
            })
            addField(body, "vulnerability", tr(R.string.ui_extra_help_needed_if_known), "e.g. Elderly person, limited mobility", 240)
        }
        optionalSection("message", tr(R.string.ui_write_a_message)) { body ->
            body.addView(label(tr(R.string.ui_optional_your_emergency_type_and_selected_needs_are_sent_even_without_), 13, muted))
            addField(body, "text", tr(R.string.ui_your_message_optional), tr(R.string.ui_describe_the_emergency), 2000, multiline = true)
        }
        optionalSection("location", tr(R.string.ui_location)) { body ->
            locationSummary = label("", 14, muted); body.addView(locationSummary)
            deviceLocationButton = action(tr(R.string.ui_use_device_location), navy) { requestSosLocation() }
            body.addView(deviceLocationButton, spaced())
            mesh.savedSosLocation()?.let { saved ->
                body.addView(back(tr(R.string.ui_use_saved_location)) { keepDraft(); applyLocation(saved); renderScreen() })
            }
            body.addView(back(tr(R.string.ui_send_with_unknown_location)) {
                keepDraft(); locationProvider.cancel(); locationPending = false; locationNotice = null
                locationKeys.forEach { draft[it] = "" }; draftLocation = LocationContext(); renderScreen()
            })
            addField(body, "building", tr(R.string.ui_building_landmark), tr(R.string.ui_building_or_landmark), 120)
            addField(body, "location", tr(R.string.ui_address_access_point), tr(R.string.ui_address_or_access_point), 240)
            val row = LinearLayout(this); val left = column(); val right = column()
            addField(left, "floor", tr(R.string.ui_floor), "e.g. Ground", 40); addField(right, "room", tr(R.string.ui_room), "e.g. 12", 40)
            row.addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            row.addView(right, LinearLayout.LayoutParams(0, -2, 1f)); body.addView(row)
            addField(body, "zone", tr(R.string.ui_zone_area), "e.g. East entrance", 120)
            body.addView(action(tr(R.string.ui_save_location_for_quick_sos), navy) {
                keepDraft()
                val saved = currentLocation()
                if (locationKeys.none { !draft[it].isNullOrBlank() } && draftLocation.latitude == null) {
                    toast("Add a location or use device location first. An SOS can still be sent without it.")
                } else {
                    locationNotice = if (mesh.saveSosLocation(saved)) "Saved for future quick SOS. Update it when you move." else "Could not save this location. Your SOS can still be sent."; updateLocationSummary()
                }
            }, spaced())
            if (mesh.savedSosLocation() != null) body.addView(back(tr(R.string.ui_remove_saved_location)) {
                mesh.saveSosLocation(null); locationNotice = "Saved location removed. This draft keeps the location shown above."; updateLocationSummary()
            })
            body.addView(label("Saving is optional. Saved locations may be outdated; responders see when the location was recorded.", 12, muted), spaced())
        }
        updateLocationSummary()
        formActions.visibility = View.VISIBLE
        val send = action(tr(R.string.ui_review_sos), red) {
            keepDraft()
            if (categoryIndex !in categoryNames.indices) {
                categoryError?.visibility = View.VISIBLE
                categoryError?.announceForAccessibility(tr(R.string.ui_choose_an_emergency_type_or_other_not_sure))
                scroll.smoothScrollTo(0, 0)
                return@action
            }
            val rawPeople = draft["people"].orEmpty().trim(); val people = rawPeople.toIntOrNull()
            if (rawPeople.isNotEmpty() && (people == null || people !in 0..10000)) {
                expandedSections.add("people"); renderScreen(); fields["people"]?.apply {
                    error = tr(R.string.ui_enter_0_to_10000_or_leave_unknown); requestFocus()
                    announceForAccessibility(tr(R.string.ui_enter_0_to_10000_or_leave_unknown))
                }; return@action
            }
            // Freeze the visible location before review so a late fix cannot change what is sent.
            locationProvider.cancel()
            if (locationPending) locationNotice = "Location lookup stopped for review. Use device location to try again."
            locationPending = false
            show("review")
        }.apply { isEnabled = !submitting }
        submitButton = send; formActions.addView(send)
        formActions.addView(label(tr(R.string.ui_nothing_is_sent_until_you_confirm_on_the_next_screen), 12, muted), spaced(6))
    }
    private fun currentSosDraft() = SosDraft(text = draft["text"].orEmpty(), emergencyType = EMERGENCY_TYPES[categoryIndex],
        building = draft["building"].orEmpty(), zone = draft["zone"].orEmpty(), locationText = draft["location"].orEmpty(),
        floor = draft["floor"].orEmpty(), room = draft["room"].orEmpty(), peopleAffected = draft["people"].orEmpty().trim().toIntOrNull(),
        vulnerability = draft["vulnerability"].orEmpty(), quickNeeds = quickNeeds.toSet(), locationContext = draftLocation)

    private fun reviewScreen() {
        if (categoryIndex !in categoryNames.indices) { screen = "form"; formScreen(); return }
        val packet = currentSosDraft()
        content.addView(back(tr(R.string.ui_edit_details)) { show("form") })
        content.addView(label(tr(R.string.ui_step_2_of_2_review), 11, teal, true), spaced(4))
        heading(tr(R.string.ui_review_your_sos))
        submissionFailure(content)
        reviewStatus = label(if (submitting) tr(R.string.ui_saving_your_sos_on_this_device) else
            tr(R.string.ui_this_is_the_information_responders_will_receive_nothing_has_been_sent_), 14, muted)
        content.addView(reviewStatus, spaced(14))
        val emergency = card()
        emergency.addView(label(tr(R.string.ui_help_requested), 11, muted, true))
        emergency.addView(label(categoryTitle(packet.emergencyType), 25, red, true), spaced(7))
        emergency.addView(label(tr(R.string.review_needs, if (packet.quickNeeds.isEmpty()) tr(R.string.ui_not_specified) else
            packet.quickNeeds.joinToString(" · ") { needsNames[it] ?: it }), 16, navy), spaced(12))
        emergency.addView(label(tr(R.string.review_people, packet.peopleAffected?.toString() ?: tr(R.string.ui_unknown)), 16, navy), spaced(8))
        if (packet.vulnerability.isNotBlank()) emergency.addView(label(tr(R.string.review_extra_help, packet.vulnerability.trim()), 15, navy), spaced(8))
        content.addView(emergency, spaced(16))
        val where = card(); where.addView(label(tr(R.string.ui_location_to_send), 11, muted, true))
        val address = listOf(packet.building, packet.locationText, packet.floor.takeIf { it.isNotBlank() }?.let { tr(R.string.floor_value, it) }.orEmpty(),
            packet.room.takeIf { it.isNotBlank() }?.let { tr(R.string.room_value, it) }.orEmpty(), packet.zone).filter { it.isNotBlank() }.joinToString(" · ")
        if (address.isNotBlank()) where.addView(label(address, 17, navy, true), spaced(7))
        val position = label(locationDescription(packet.locationContext), 14, muted)
        reviewLocationBinding = position to packet.locationContext; where.addView(position, spaced(8))
        if (address.isBlank() && packet.locationContext.source == "unknown")
            where.addView(label(tr(R.string.ui_responders_will_not_know_where_to_find_you_add_a_landmark_or_device_lo), 14, amber), spaced(10))
        content.addView(where, spaced(12))
        val message = card(); message.addView(label(tr(R.string.ui_message), 11, muted, true))
        if (packet.text.isBlank()) {
            message.addView(label(tr(R.string.ui_no_typed_message), 16, navy, true), spaced(7))
            message.addView(label(tr(R.string.ui_your_selected_emergency_details_are_included_above_the_app_also_sends_), 14, muted), spaced(7))
            message.addView(label(QUICK_SOS_MESSAGE, 14, muted), spaced(7))
        } else message.addView(label(packet.text.trim(), 16, navy), spaced(7))
        content.addView(message, spaced(12))
        if (packet.emergencyType == "other" && packet.quickNeeds.isEmpty() && packet.text.isBlank())
            paragraph(tr(R.string.ui_the_kind_of_emergency_is_still_unknown_you_can_send_this_general_help_))
        formActions.visibility = View.VISIBLE
        val send = action(if (submitting) tr(R.string.ui_saving_sos) else tr(R.string.ui_send_emergency_sos), red) { submitSos(packet, clearDraft = true) }.apply { isEnabled = !submitting }
        submitButton = send; formActions.addView(send)
        formActions.addView(label(tr(R.string.ui_saves_locally_first_delivery_needs_a_connection), 12, muted), spaced(6))
    }
    private fun submitSos(packet: SosDraft, clearDraft: Boolean) {
        if (submitting) return
        submissionError = null
        submitButton?.isEnabled = false
        locationProvider.cancel(); locationPending = false
        submission.send(mesh, packet, clearDraft)
    }
    private fun optionalSection(key: String, title: String, build: (LinearLayout) -> Unit) {
        val section = card()
        val body = column().apply { visibility = if (key in expandedSections) View.VISIBLE else View.GONE }
        val toggle = back(title + if (key in expandedSections) "  −" else "  +") {
            keepDraft()
            if (!expandedSections.add(key)) expandedSections.remove(key)
            renderScreen()
        }.apply {
            textSize = 17f; setTypeface(typeface, Typeface.BOLD)
            val description = tr(if (key in expandedSections) R.string.ui_expanded else R.string.ui_collapsed)
            contentDescription = "$title, $description"
            if (Build.VERSION.SDK_INT >= 30) stateDescription = description
        }
        section.addView(toggle); build(body); section.addView(body); content.addView(section, spaced(10))
    }
    private fun choice(title: String, selected: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = title; isAllCaps = false; textSize = 14f; minHeight = dp(52); minimumWidth = 0; stateListAnimator = null
        setPadding(dp(5), dp(5), dp(5), dp(5)); paintChoice(this, selected); setOnClickListener { onClick() }
    }
    private fun paintChoice(button: Button, selected: Boolean) {
        button.isSelected = selected; button.setTextColor(if (selected) Color.WHITE else navy)
        button.background = RippleDrawable(ColorStateList.valueOf(softTeal), shape(if (selected) teal else Color.WHITE, 10).apply { setStroke(dp(1), if (selected) teal else line) }, shape(Color.WHITE,10))
        if (Build.VERSION.SDK_INT >= 30) button.stateDescription = if (selected) tr(R.string.ui_selected) else tr(R.string.ui_not_selected)
    }
    private fun afterChanged(block: () -> Unit) = object: TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { block() }
    }
    private fun currentLocation() = SavedSosLocation(building = draft["building"].orEmpty(), zone = draft["zone"].orEmpty(),
        locationText = draft["location"].orEmpty(), floor = draft["floor"].orEmpty(), room = draft["room"].orEmpty(), context = draftLocation)
    private fun applyLocation(location: SavedSosLocation) {
        locationProvider.cancel(); locationPending = false
        draft["building"] = location.building; draft["zone"] = location.zone; draft["location"] = location.locationText
        draft["floor"] = location.floor; draft["room"] = location.room; draftLocation = location.context; locationNotice = null
    }
    private fun requestSosLocation() {
        if (locationPending) return
        if (!locationProvider.hasPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), 71)
        } else acquireSosLocation()
    }
    private fun acquireSosLocation() {
        locationPending = true; locationNotice = "Finding location… You can send your SOS while this runs."; updateLocationSummary()
        locationProvider.request { location ->
            if (isDestroyed || isFinishing) return@request
            locationPending = false
            if (location != null) { draftLocation = location; locationNotice = "Device coordinates attached. Add a landmark if useful." }
            else locationNotice = "No device location available. Send now, use a saved location, or add a landmark."
            updateLocationSummary()
        }
    }
    private fun refreshLocationAges() {
        homeLocationBinding?.let { (view, location) -> updateLabel(view, compactLocationDescription(location)) }
        locationSummary?.let { view -> updateLabel(view, listOfNotNull(locationDescription(draftLocation), locationNotice).joinToString("\n")) }
        editorLocationSummary?.let { updateLabel(it, listOfNotNull(quickLocationDescription(locationEditor), locationNotice).joinToString("\n")) }
        journeyLocationBinding?.let { (view, location) -> updateLabel(view, locationDescription(location)) }
        reviewLocationBinding?.let { (view, location) -> updateLabel(view, locationDescription(location)) }
    }
    private fun updateLabel(view: TextView, value: String) {
        if (view.text.toString() != value) view.text = value
    }
    private fun quickLocationDescription(location: SavedSosLocation?) = if (location == null) tr(R.string.ui_location_unknown_sos_can_still_be_sent) else
        listOf(savedLocationTitle(location), locationDescription(location.context)).joinToString("\n")
    private fun updateLocationSummary() {
        refreshLocationAges()
        deviceLocationButton?.isEnabled = !locationPending
        deviceLocationButton?.text = if (locationPending) "Finding location…" else tr(R.string.ui_use_device_location)
        editorLocationButton?.isEnabled = !locationPending
        editorLocationButton?.text = if (locationPending) "Finding location…" else tr(R.string.ui_use_device_location)
    }
    private fun savedLocationTitle(location: SavedSosLocation) = listOf(location.building, location.locationText,
        location.floor.takeIf { it.isNotBlank() }?.let { tr(R.string.floor_value, it) }.orEmpty(),
        location.room.takeIf { it.isNotBlank() }?.let { tr(R.string.room_value, it) }.orEmpty(), location.zone).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { tr(R.string.ui_device_coordinates) }
    private fun locationDescription(context: LocationContext): String {
        if (context.source == "unknown") return "Location unknown · this will not block your SOS"
        val source = when (context.source) { "device" -> "Device location"; "saved" -> "Saved location"; "manual" -> "Location entered by you"; else -> tr(R.string.ui_location) }
        val timestamp = context.observedAt?.let { "Recorded ${formatTime(it)} · ${locationAge(it)}" } ?: "Recorded time unknown"
        val coordinates = if (context.latitude != null && context.longitude != null)
            "\n${String.format(Locale.ROOT, "%.5f, %.5f", context.latitude, context.longitude)}" + (context.accuracyM?.let { " · accuracy ±${it.toInt()} m" } ?: " · accuracy unknown") else ""
        val simulationNote = if (state.simulation) "\nSimulation location · not a verified person's position" else ""
        return "$source\n$timestamp$coordinates$simulationNote"
    }
    private fun locationAge(time: Long): String {
        val age = (System.currentTimeMillis() - time).coerceAtLeast(0)
        return when { age < 60_000 -> "less than a minute ago"; age < 3_600_000 -> "${age / 60_000} min ago";
            age < 86_400_000 -> "${age / 3_600_000} h ago · may be outdated"; else -> "${age / 86_400_000} days ago · may be outdated" }
    }
    private fun journeyScreen() {
        val carried=state.reports.any { it.report.id==selectedReport && it.report.originId!=state.localNodeId }
        content.addView(back(if(carried) tr(R.string.ui_back_to_nearby_network) else tr(R.string.ui_back_to_your_reports)) { show(if(carried) "network" else "reports") }); heading(tr(R.string.ui_sos_delivery))
        listPanel = column(); content.addView(listPanel, spaced())
    }
    private fun networkScreen() {
        heading(tr(R.string.ui_connection), tr(R.string.ui_your_sos_stays_saved_while_a_route_becomes_available))
        val c = card(); c.addView(label(tr(R.string.ui_delivery_connection), 11, muted, true))
        statusTitle = label("", 20, navy, true); c.addView(statusTitle, spaced(10))
        statusSubtitle = label("", 14, muted); c.addView(statusSubtitle, spaced(6))
        connectionHint = label("", 13, muted); c.addView(connectionHint, spaced(12)); content.addView(c, spaced())
        relayHelp = action(tr(R.string.ui_enable_nearby_relay), navy) { resolveRelay() }; content.addView(relayHelp, spaced())
        content.addView(label(tr(R.string.ui_nearby_devices), 20, navy, true), spaced(22)); listPanel = column(); content.addView(listPanel)
        paragraph("Keep the app open. A connected relay can carry a saved report; a gateway signal alone does not confirm delivery.")
        content.addView(label("Device ID · ${state.localNodeId}",12,muted),spaced())
    }
    private fun settingsScreen() {
        settingsDirty = false
        heading(tr(R.string.ui_settings), tr(R.string.ui_set_up_your_connection_before_you_need_it))
        val connection=card()
        connection.addView(label(tr(R.string.ui_nearby_relay),18,navy,true))
        connection.addView(label(tr(R.string.ui_help_carry_sos_reports_while_resqmesh_is_open),14,muted),spaced(6))
        connection.addView(Switch(this).apply {
            text=tr(R.string.ui_relay_while_this_app_is_open);textSize=14f;minHeight=dp(52);setTextColor(navy);isChecked=state.relayEnabled
            setOnCheckedChangeListener { _,enabled -> mesh.setRelayEnabled(enabled) }
        },spaced(8))
        connection.addView(label(tr(R.string.ui_turning_this_off_pauses_nearby_forwarding_direct_delivery_to_the_respo),12,muted),spaced(4))
        connection.addView(secondary(if(state.simulation) tr(R.string.ui_switch_to_nearby_phones) else tr(R.string.ui_switch_to_simulation_mode),R.drawable.ux_network) {
            mesh.switchMode(!state.simulation);show("home")
        },spaced(12))
        content.addView(connection,spaced(18))

        val background = card()
        background.addView(label(tr(R.string.ui_background_relay), 18, navy, true).apply { if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true })
        background.addView(label(tr(R.string.ui_off_by_default_uses_an_ongoing_notification_and_may_use_battery_androi), 13, muted), spaced(6))
        backgroundToggle = Switch(this).apply {
            text = tr(R.string.ui_continue_relaying_with_the_screen_closed); textSize = 14f; minHeight = dp(52); setTextColor(navy)
            isChecked = state.backgroundRelayEnabled; isEnabled = state.ready
            setOnCheckedChangeListener { _, enabled ->
                if (!syncingBackgroundToggle) {
                    if (enabled && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        syncingBackgroundToggle = true; isChecked = false; syncingBackgroundToggle = false
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 74)
                    } else changeBackgroundRelay(enabled)
                }
            }
        }
        background.addView(backgroundToggle, spaced(10))
        backgroundStatus = label("", 12, muted).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        background.addView(backgroundStatus, spaced(6))
        content.addView(background, spaced(12))

        val history = card()
        history.addView(label(tr(R.string.ui_storage_and_history), 18, navy, true).apply { if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true })
        history.addView(label(tr(R.string.ui_only_old_reports_confirmed_delivered_including_all_attachments_can_be_), 13, muted), spaced(6))
        val periods = listOf<Int?>(null, 7, 30, 90)
        val periodLabel = label(tr(R.string.ui_delivered_history_retention), 13, navy, true)
        val period = spinner(listOf(tr(R.string.ui_keep_all_history), tr(R.string.ui_7_days), tr(R.string.ui_30_days), tr(R.string.ui_90_days))).apply {
            id = View.generateViewId(); contentDescription = tr(R.string.ui_delivered_history_retention); isEnabled = state.ready
            setSelection(periods.indexOf(state.retentionDays).coerceAtLeast(0))
        }
        periodLabel.labelFor = period.id
        history.addView(periodLabel, spaced(12)); history.addView(period, spaced(6))
        val clean = secondary(tr(R.string.ui_clean_delivered_history_now)) {
            if (mesh.retentionDays() == null) toast(tr(R.string.ui_choose_a_retention_period_first_keep_all_history_never_removes_reports))
            else AlertDialog.Builder(this).setTitle(tr(R.string.ui_remove_eligible_local_history))
                .setMessage(tr(R.string.ui_this_removes_only_eligible_delivered_reports_from_this_device_pending_))
                .setNegativeButton(tr(R.string.ui_cancel), null)
                .setPositiveButton(tr(R.string.ui_remove_history)) { _, _ -> mesh.cleanupDeliveredHistory { count -> runOnUiThread {
                    toast(tr(R.string.history_removed_count, count))
                } } }.show()
        }.apply { isEnabled = state.ready && state.retentionDays != null }
        period.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!state.ready) return
                val days = periods[position]
                if (days != mesh.retentionDays()) mesh.setRetentionDays(days)
                clean.isEnabled = days != null
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        history.addView(clean, spaced(10)); content.addView(history, spaced(12))

        val setup=card()
        val body=column().apply { visibility=if(settingsExpanded) View.VISIBLE else View.GONE }
        setup.addView(disclosure("Connection setup", R.drawable.ux_settings, settingsExpanded) { expanded ->
            settingsExpanded=expanded;body.visibility=if(expanded) View.VISIBLE else View.GONE
        })
        setup.addView(label("Response-system address and access key",12,muted))
        val addressLabel = label("Backend address",13,navy,true)
        body.addView(addressLabel,spaced(16))
        val address=field("Backend URL",state.backend,250).apply { id = View.generateViewId(); inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        addressLabel.labelFor = address.id
        address.addTextChangedListener(afterChanged { settingsDirty=true })
        body.addView(address,spaced(6))
        body.addView(label("Emulator: 10.0.2.2 reaches this Mac. Physical phones need the server's reachable address.",12,muted),spaced(6))
        val keyLabel = label("Access key",13,navy,true)
        body.addView(keyLabel,spaced(16))
        val key=field(if(state.apiKeyConfigured) "Key saved; leave blank to keep" else "Optional shared demo key","",4096)
            .apply { id = View.generateViewId(); inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        keyLabel.labelFor = key.id
        body.addView(key,spaced(6))
        val clear=CheckBox(this).apply { text="Remove saved key";setTextColor(muted);minHeight=dp(48) };body.addView(clear)
        val result=label("",13,teal).apply { visibility=View.GONE;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        body.addView(result,spaced(6))
        body.addView(action("Save connection") {
            mesh.saveSettings(address.text.toString(),if(clear.isChecked) "" else key.text.toString().trim().ifEmpty { null }) { error -> runOnUiThread {
                if(error!=null) { address.error=error;result.text=error;result.setTextColor(red) }
                else { key.setText("");clear.isChecked=false;settingsDirty=false;result.text="Connection saved";result.setTextColor(teal) }
                result.visibility=View.VISIBLE
            } }
        },spaced(10))
        body.addView(label("Use HTTPS and proper access controls outside a trusted local demo.",12,muted),spaced(10))
        setup.addView(body);content.addView(setup,spaced(12))

        val lab=card();val tools=column().apply { visibility=if(simulationExpanded) View.VISIBLE else View.GONE }
        lab.addView(disclosure("Simulation tools", R.drawable.ux_network, simulationExpanded) { expanded ->
            simulationExpanded=expanded;tools.visibility=if(expanded) View.VISIBLE else View.GONE
        })
        lab.addView(label("Virtual devices · development and rehearsal",12,muted))
        tools.addView(label("Simulation does not test Bluetooth or Wi-Fi radios.",14,muted),spaced(12))
        tools.addView(secondary("Open Simulation Lab",R.drawable.ux_network) {
            if(!state.simulation) mesh.switchMode(true);show("lab")
        },spaced(12))
        lab.addView(tools);content.addView(lab,spaced(12))
        notice(content,"How delivery works","Reports save on this phone first. Keep the app open for relaying. A backend receipt means the system received your alert; human acknowledgement does not confirm a rescue dispatch.")
        paragraph("ResQMesh prototype · use official emergency services when available.")
    }
    private fun changeBackgroundRelay(enabled: Boolean) {
        mesh.setBackgroundRelayEnabled(enabled) { error -> runOnUiThread {
            if (error != null) toast(error)
            if (!isDestroyed) { state = mesh.state.value; updateBackgroundControls() }
        } }
    }
    private fun updateBackgroundControls() {
        val description = when {
            state.backgroundRelayRunning -> tr(R.string.ui_background_relay_is_running)
            state.backgroundRelayEnabled -> tr(R.string.ui_background_relay_is_enabled_waiting_to_start)
            else -> tr(R.string.ui_background_relay_is_off)
        }
        backgroundStatus?.let { updateLabel(it, description) }
        syncingBackgroundToggle = true
        backgroundToggle?.isChecked = state.backgroundRelayEnabled
        syncingBackgroundToggle = false
    }
    private fun labScreen() {
        content.addView(back("Back to Settings") { show("settings") })
        heading("Simulation Lab", "Rehearse offline delivery using virtual devices.")
        content.addView(action("Add virtual device") { mesh.simAddNode() }, spaced())
        content.addView(action("Change a link", navy) { linkDialog() }, spaced())
        content.addView(action("Add two sample SOS reports", navy) { mesh.loadSimulationExamples(); toast("Synthetic reports added on the selected device") }, spaced())
        listPanel = column(); content.addView(listPanel, spaced(20))
        paragraph("Selected-device screens show only that device's knowledge. Removing a device removes its current links; stored report history remains available.")
    }
    private fun linkDialog() {
        val nodes = state.lab.nodes.map { it.id }
        if(nodes.size < 2) { toast("Add another virtual device first"); return }
        val form = column().apply { setPadding(dp(22),dp(10),dp(22),dp(10)) }
        form.addView(label("First device",13,muted)); val from=spinner(nodes); form.addView(from)
        form.addView(label("Second device",13,muted), spaced()); val to=spinner(nodes).apply { setSelection(1) }; form.addView(to)
        AlertDialog.Builder(this).setTitle("Change range between devices").setView(form)
            .setPositiveButton("Connect") { _, _ -> if(from.selectedItem != to.selectedItem) mesh.simSetLink(from.selectedItem.toString(),to.selectedItem.toString(),true) else toast("Choose two different devices") }
            .setNeutralButton("Disconnect") { _, _ -> if(from.selectedItem != to.selectedItem) mesh.simSetLink(from.selectedItem.toString(),to.selectedItem.toString(),false) }
            .setNegativeButton(tr(R.string.ui_cancel),null).show()
    }
    private fun updateLive() {
        if (!::content.isInitialized) return
        updateBackgroundControls()
        statusTitle?.let { updateLabel(it, connectionTitle()) }
        statusSubtitle?.let { updateLabel(it, connectionDescription()) }
        countLabel?.let { updateLabel(it, tr(R.string.nearby_pending_count, connectedPeers().size, pendingCount())) }
        connectionHint?.text = "Internet: ${if(state.connectivity.internetValidated) "available" else if(state.connectivity.networkAvailable) "network present, unverified" else "unavailable"}\nResponse system: ${if(state.connectivity.backendReachable) "reachable" else if(state.connectivity.checking) "checking…" else "not reached"}"
        relayHelp?.apply {
            visibility = if(!state.simulation && state.relayPhase in setOf(RelayPhase.NEEDS_PERMISSION,RelayPhase.RADIOS_OFF,RelayPhase.DISABLED,RelayPhase.PLAY_SERVICES_UNAVAILABLE,RelayPhase.FAILED)) View.VISIBLE else View.GONE
            text=when(state.relayPhase) { RelayPhase.NEEDS_PERMISSION -> "Allow nearby connection"; RelayPhase.RADIOS_OFF -> "Enable Bluetooth and Wi-Fi"; RelayPhase.DISABLED -> "Enable relay"; RelayPhase.PLAY_SERVICES_UNAVAILABLE -> "Google Play services required"; else -> "Check nearby connection" }
        }
        when(screen) {
            "home", "reports" -> updateReports()
            "journey" -> updateJourney()
            "network" -> updatePeers()
            "lab" -> updateLab()
        }
    }
    private fun connectionTitle(): String = when {
        !state.ready -> tr(R.string.ui_starting)
        state.connectivity.backendReachable -> tr(R.string.ui_response_system_connected)
        !state.relayEnabled -> tr(R.string.ui_nearby_relay_paused)
        state.peers.any { it.phase == PeerPhase.CONNECTED && it.gatewayAvailable } -> tr(R.string.ui_gateway_in_range)
        connectedPeers().isNotEmpty() -> tr(R.string.ui_nearby_relay_available)
        state.relayPhase == RelayPhase.SEARCHING || state.relayPhase == RelayPhase.STARTING -> tr(R.string.ui_looking_for_nearby_devices)
        else -> tr(R.string.ui_waiting_for_a_connection)
    }
    private fun connectionDescription(): String = when {
        state.connectivity.backendReachable -> if (state.relayEnabled) tr(R.string.ui_this_phone_can_send_directly_to_the_response_system) else tr(R.string.ui_direct_delivery_is_available_only_nearby_forwarding_is_paused)
        !state.relayEnabled -> tr(R.string.ui_nearby_forwarding_is_paused_direct_delivery_can_still_work_when_the_re)
        state.peers.any { it.phase == PeerPhase.CONNECTED && it.gatewayAvailable } -> tr(R.string.ui_a_nearby_peer_recently_reached_the_response_system)
        connectedPeers().isNotEmpty() -> tr(R.string.ui_saved_reports_can_move_through_nearby_devices)
        state.relayPhase == RelayPhase.NEEDS_PERMISSION -> tr(R.string.ui_allow_nearby_access_to_find_participating_phones)
        state.relayPhase == RelayPhase.RADIOS_OFF -> tr(R.string.ui_enable_bluetooth_and_wi_fi_to_find_relays)
        state.relayPhase == RelayPhase.DISABLED -> tr(R.string.ui_nearby_forwarding_is_paused_direct_delivery_remains_available_when_con)
        state.relayPhase == RelayPhase.PLAY_SERVICES_UNAVAILABLE -> "Nearby requires compatible Google Play services."
        state.relayPhase == RelayPhase.FAILED -> tr(R.string.ui_nearby_connection_needs_attention_reports_stay_saved)
        else -> tr(R.string.ui_waiting_for_a_nearby_resqmesh_device)
    }
    private fun connectedPeers() = state.peers.filter { it.phase == PeerPhase.CONNECTED }
    private fun receipts(id: String) = state.receipts.filter { it.receipt.reportId == id }
    private fun pendingCount() = state.reports.count { it.status != DeliveryStatus.EXPIRED && receipts(it.report.id).isEmpty() && it.status != DeliveryStatus.UPLOADED }
    private fun deliveryTitle(item: StoredReport): String {
        val receipts = receipts(item.report.id)
        return when {
            receipts.any { it.receipt.type == "responder_acknowledged" } -> tr(R.string.ui_responder_acknowledged)
            receipts.any { it.receipt.type == "backend_received" } -> tr(R.string.ui_delivered_to_response_system)
            item.status == DeliveryStatus.UPLOADED -> tr(R.string.ui_uploaded_from_this_device)
            item.status == DeliveryStatus.EXPIRED -> tr(R.string.ui_forwarding_window_ended)
            item.forwardedTo.isNotEmpty() -> tr(R.string.ui_relayed_waiting_for_delivery_receipt)
            item.report.originId != state.localNodeId -> tr(R.string.ui_stored_for_relay)
            !state.relayEnabled -> tr(R.string.ui_saved_nearby_relay_paused)
            else -> tr(R.string.ui_saved_waiting_for_connectivity)
        }
    }
    private fun needsAttention(item: StoredReport) = item.status != DeliveryStatus.UPLOADED && receipts(item.report.id).isEmpty()
    private fun updateReports() {
        val own = state.reports.filter { it.report.originId == state.localNodeId }.sortedWith(compareByDescending<StoredReport> { it.report.createdAt }.thenBy { it.report.id })
        val filtered = if (screen == "reports" && attentionOnly) own.filter(::needsAttention) else own
        val pages = ((filtered.size + 4) / 5).coerceAtLeast(1)
        reportsPage = reportsPage.coerceIn(0, pages - 1)
        val visible = if (screen == "home") own.take(1) else filtered.drop(reportsPage * 5).take(5)
        val key = listOf(screen, reportsPage, attentionOnly, state.relayEnabled, filtered.size, visible.map { listOf(it, receipts(it.report.id)) }).toString()
        if (key == renderKey) return; renderKey = key
        val panel = listPanel ?: return; panel.removeAllViews()
        if (screen == "reports") panel.addView(label(tr(if (attentionOnly) R.string.attention_reports_count else R.string.saved_reports_count, filtered.size), 13, muted), spaced(8))
        if (visible.isEmpty()) {
            val empty = card()
            empty.addView(label(if (attentionOnly) tr(R.string.ui_no_reports_need_attention) else tr(R.string.ui_no_sos_reports_yet), 17, navy, true))
            empty.addView(label(if (attentionOnly) tr(R.string.ui_all_your_saved_reports_have_an_upload_or_receipt_recorded) else tr(R.string.ui_when_you_send_an_sos_its_delivery_status_will_appear_here),14,muted), spaced(6))
            panel.addView(empty,spaced(12))
        }
        visible.forEach { item -> panel.addView(reportRow(item), spaced(10)) }
        if (screen == "reports" && filtered.size > 5) panel.addView(pager(reportsPage, pages) { page ->
            reportsPage = page; renderKey = ""; updateReports(); scroll.scrollTo(0, 0); scroll.post { scroll.scrollTo(0,0) }
        }, spaced(14))
        if (screen == "home" && own.isNotEmpty()) panel.addView(back(tr(R.string.ui_view_all_reports)) { show("reports") })
    }
    private fun reportRow(item: StoredReport, carried: Boolean = false): LinearLayout {
        val r = item.report
        val c = card().apply { tag = "sos-report:${r.id}" }
        c.addView(label(deliveryTitle(item),12,if (needsAttention(item)) amber else teal,true))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(label(reportTitle(r),18,navy,true),LinearLayout.LayoutParams(0,-2,1f))
        row.addView(label(SimpleDateFormat("HH:mm",Locale.ENGLISH).format(Date(r.createdAt)),12,muted))
        c.addView(row,spaced(7))
        c.addView(label(location(r),13,muted).apply { maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END },spaced(5))
        if (r.attachments.isNotEmpty()) c.addView(label(r.attachments.joinToString(" · ") { "${mediaName(it.kind)} attached" },13,navy),spaced(6))
        else c.addView(label(r.text,14,navy).apply { maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END },spaced(6))
        c.addView(label("SOS ${r.id.take(8).uppercase(Locale.ROOT)} · ${SimpleDateFormat("dd MMM",Locale.ENGLISH).format(Date(r.createdAt))}",11,muted),spaced(8))
        c.addView(back(if(carried) tr(R.string.ui_view_carried_report) else tr(R.string.ui_view_delivery_status)) {
            selectedReport=r.id; journeyDetails=false; show("journey")
        }.apply { tag="report:${r.id}";setCompoundDrawables(null,null,icon(R.drawable.ux_chevron_right,teal,18),null) })
        return c
    }
    private fun pager(page: Int, pages: Int, change: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        row.addView(secondary(tr(R.string.ui_previous)) { change(page-1) }.apply { isEnabled=page>0 },LinearLayout.LayoutParams(0,-2,1f))
        row.addView(label("${page+1} / $pages",13,muted).apply { gravity=Gravity.CENTER },LinearLayout.LayoutParams(dp(66),-2))
        row.addView(secondary(tr(R.string.ui_next)) { change(page+1) }.apply { isEnabled=page<pages-1 },LinearLayout.LayoutParams(0,-2,1f))
        return row
    }
    private fun updateJourney() {
        val item=state.reports.firstOrNull { it.report.id == selectedReport }
        val events=state.journeys.filter { it.reportId == selectedReport }.sortedBy { it.at }
        val received=selectedReport?.let(::receipts).orEmpty()
        val media = state.media.filter { it.reportId == selectedReport }
        val key=listOf(item,events,received,state.relayEnabled,media,journeyDetails).toString(); if(key==renderKey)return;renderKey=key
        stopPlayback()
        val panel=listPanel ?: return;panel.removeAllViews();journeyLocationBinding = null
        if(item==null) { panel.addView(label("Loading the report saved on this device…",15,muted));return }
        val r=item.report
        val summary=card();summary.addView(label("YOUR SOS · ${formatTime(r.createdAt)}",11,muted,true))
        summary.addView(label(if (r.attachments.isNotEmpty()) reportTitle(r) else tr(R.string.help_requested_value, categoryTitle(r.emergencyType)), 21, navy, true), spaced(12))
        if (r.peopleAffected != null || r.attachments.isEmpty()) summary.addView(label(tr(R.string.review_people, r.peopleAffected?.toString() ?: tr(R.string.ui_unknown)), 15, navy), spaced(7))
        if (r.quickNeeds.isNotEmpty()) summary.addView(label(r.quickNeeds.joinToString(" · ") { needsNames[it] ?: it }, 15, red, true), spaced(10))
        else if (r.attachments.isEmpty()) summary.addView(label(tr(R.string.review_needs, tr(R.string.ui_not_specified)), 14, muted), spaced(7))
        summary.addView(label(location(r),14,muted),spaced(10))
        if (r.messageSource == "preset") summary.addView(label(if (r.attachments.isEmpty()) tr(R.string.ui_no_typed_message_app_default_help_request) else tr(R.string.ui_no_typed_message_see_attached_media), 12, muted), spaced(7))
        if (r.messageSource != "preset" || r.attachments.isEmpty()) summary.addView(label(r.text,16,navy),spaced())
        summary.addView(label("SOS ${r.id.take(8).uppercase(Locale.ROOT)}",12,muted),spaced())
        val locationLabel = label(locationDescription(r.locationContext), 13, muted)
        journeyLocationBinding = locationLabel to r.locationContext
        summary.addView(locationLabel, spaced(10))
        panel.addView(summary)
        if (r.attachments.isNotEmpty()) {
            val attachments = card(); attachments.addView(label(tr(R.string.ui_attached_to_this_sos), 19, navy, true))
            r.attachments.forEach { attachment ->
                val evidence = media.firstOrNull { it.attachmentId == attachment.id }
                attachments.addView(label("${mediaName(attachment.kind)} · ${mediaSize(attachment.byteSize)}", 16, navy, true), spaced(12))
                val status = when {
                    evidence?.backendReceived == true -> tr(R.string.ui_response_system_received_this_attachment)
                    evidence?.localAvailable == true -> tr(R.string.ui_saved_here_attachment_delivery_unconfirmed)
                    else -> tr(R.string.ui_attachment_metadata_received_file_unavailable_here)
                }
                attachments.addView(label(status, 13, if (evidence?.backendReceived == true) teal else amber), spaced(5))
                if (evidence?.localAvailable == true) {
                    val previewPath = evidence.filePath
                    if (previewPath != null) addMediaPreview(attachments, attachment.kind, previewPath)
                    else attachments.addView(action("Open ${mediaName(attachment.kind).lowercase(Locale.ROOT)}", navy) {
                        mesh.requestMediaPreview(r.id, attachment.id) { path ->
                            if (path == null) toast("Could not open this saved attachment. It is still kept on this device.")
                        }
                    }, spaced(12))
                }
            }
            attachments.addView(label(tr(R.string.ui_sos_delivery_and_attachment_delivery_are_tracked_separately_a_received), 12, muted), spaced(12))
            panel.addView(attachments, spaced())
        }
        val backendKnown = received.any { it.receipt.type == "backend_received" || it.receipt.type == "responder_acknowledged" }
        val humanKnown = received.any { it.receipt.type == "responder_acknowledged" }
        val peerKnown = item.forwardedTo.isNotEmpty() || events.any { it.kind == "peer_stored" }
        val stages = card()
        stages.addView(label(tr(R.string.ui_delivery_status), 11, muted, true))
        stages.addView(label(deliveryTitle(item), 22, if (backendKnown) teal else navy, true), spaced(8))
        stages.addView(label(if (humanKnown) tr(R.string.ui_someone_acknowledged_your_sos_this_does_not_confirm_that_a_team_was_di)
            else if (backendKnown) tr(R.string.ui_your_alert_reached_the_response_system_a_human_has_not_acknowledged_it)
            else if (item.status == DeliveryStatus.EXPIRED) tr(R.string.ui_your_report_is_still_saved_but_automatic_forwarding_has_ended_create_a)
            else if (item.status == DeliveryStatus.UPLOADED) tr(R.string.ui_this_phone_recorded_an_upload_a_delivery_receipt_has_not_reached_it_ye)
            else tr(R.string.ui_saved_on_this_phone_keep_resqmesh_open_while_it_looks_for_a_delivery_r),14,muted),spaced(8))
        listOf(
            Triple(tr(R.string.ui_saved_locally), true, tr(R.string.ui_stored_on_this_device)),
            Triple(tr(R.string.ui_relaying), peerKnown, when { peerKnown -> tr(R.string.ui_a_nearby_device_confirmed_storage); backendKnown -> tr(R.string.ui_reached_backend_without_a_peer_confirmation_on_this_device); item.status == DeliveryStatus.EXPIRED -> tr(R.string.ui_forwarding_window_ended); !state.relayEnabled -> tr(R.string.ui_relay_paused); else -> tr(R.string.ui_waiting_for_a_connection) }),
            Triple(tr(R.string.ui_backend_received), backendKnown, if (backendKnown) tr(R.string.ui_receipt_received_on_this_device) else tr(R.string.ui_no_backend_receipt_yet)),
            Triple(tr(R.string.ui_human_acknowledged), humanKnown, if (humanKnown) tr(R.string.ui_acknowledgement_receipt_received) else tr(R.string.ui_no_human_acknowledgement_yet))
        ).forEach { (title, confirmed, detail) ->
            val row = column(); row.addView(label(title, 15, if (confirmed) teal else muted, confirmed).apply { setCompoundDrawables(icon(if(confirmed) R.drawable.ux_check else R.drawable.ux_clock,if(confirmed) teal else muted,18),null,null,null);compoundDrawablePadding=dp(8) })
            row.addView(label(detail, 12, muted), spaced(3)); stages.addView(row, spaced(12))
        }
        panel.addView(stages,0)
        if (!backendKnown && item.status != DeliveryStatus.UPLOADED) {
            val help = secondary(if (item.status == DeliveryStatus.EXPIRED) tr(R.string.ui_create_new_sos) else tr(R.string.ui_check_connection), R.drawable.ux_network) {
                show(if (item.status == DeliveryStatus.EXPIRED) "home" else "network")
            }
            panel.addView(help,1,spaced(10))
        }
        (summary.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(14)
        if(received.isEmpty() && item.status != DeliveryStatus.UPLOADED) {
            val wait=if(item.status==DeliveryStatus.EXPIRED) tr(R.string.ui_your_report_remains_saved_automatic_forwarding_has_stopped_because_its)
                else if(!state.relayEnabled && item.forwardedTo.isEmpty()) tr(R.string.ui_saved_on_this_phone_nearby_forwarding_is_paused_direct_upload_can_stil)
                else if(item.forwardedTo.isEmpty()) tr(R.string.ui_this_sos_is_saved_locally_and_will_be_forwarded_automatically_when_a_r)
                else tr(R.string.ui_a_nearby_device_stored_this_sos_delivery_beyond_that_device_is_unknown)
            panel.addView(label(wait,15,muted),spaced(16))
        }
        panel.addView(back(if(journeyDetails) tr(R.string.ui_delivery_details_hide) else tr(R.string.ui_delivery_details)) {
            val y=scroll.scrollY;journeyDetails=!journeyDetails;renderKey="";updateJourney();scroll.post { scroll.scrollTo(0,y) }
        },spaced(16))
        if (journeyDetails) {
            val technical=column().apply { tag="delivery-technical-details" }
        technical.addView(label("Delivery history",19,navy,true),spaced(23))
        if(events.isEmpty()) technical.addView(label("Saved on this device · ${formatTime(r.createdAt)}",15,navy),spaced())
        events.forEach { event ->
            val title=when(event.kind) { "created" -> "SOS created and saved"; "received" -> "SOS received and saved"; "peer_stored" -> "Nearby device stored your SOS"; "backend_received" -> "Backend receipt arrived"; "responder_acknowledged" -> "Responder acknowledgement arrived"; "expired" -> tr(R.string.ui_forwarding_window_ended); else -> event.kind.replace('_',' ') }
            val c=card();c.addView(label(title,16,navy,true));c.addView(label(formatTime(event.at) + (event.peerId?.let { " · $it" } ?: ""),13,muted),spaced(5));technical.addView(c,spaced(8))
        }
        received.sortedBy { it.receipt.timestamp }.forEach { packet ->
            val receipt=packet.receipt; val c=card()
            c.addView(label(if(receipt.type=="responder_acknowledged") "Responder acknowledgement" else "Response system receipt",17,teal,true))
            c.addView(label("Recorded by backend ${formatTime(receipt.timestamp)}",13,muted),spaced(5))
            c.addView(label("Original SOS upload route",12,muted,true),spaced())
            c.addView(label("${receipt.relayPath.joinToString(" → ")} → Response system",14,navy,true),spaced(5))
            c.addView(label("${receipt.relayPath.size - 1} hops · Gateway ${receipt.gatewayId ?: "unknown"}",13,muted),spaced(5))
            c.addView(label(if(packet.path.size == 1) "Receipt fetched directly from the configured backend." else "Receipt return route: ${packet.path.joinToString(" → ")}",13,muted),spaced())
            c.addView(label("Receipt is not cryptographically attested. Acknowledgement does not mean a team was dispatched.",12,muted),spaced())
            technical.addView(c,spaced())
        }
        val metadata=card();metadata.addView(label("Report details",17,navy,true))
        metadata.addView(label("${categoryTitle(r.emergencyType)}\nPeople affected: ${r.peopleAffected?.toString() ?: "not supplied"}\nExtra help: ${r.vulnerability ?: "not supplied"}\nForwarding until ${formatTime(r.expiresAt)}",14,muted),spaced(6))
        metadata.addView(label("Stored copy on ${state.localNodeId}",13,navy,true),spaced())
        metadata.addView(label("Source ${r.originId}\n${r.hopCount} relay hops to this device\n${r.relayPath.joinToString(" → ")}",13,muted),spaced(6))
        technical.addView(metadata,spaced())
            panel.addView(technical)
        }
    }

    private fun updatePeers() {
        val carried=state.reports.filter { it.report.originId != state.localNodeId }.sortedByDescending { it.report.createdAt }
        val key=listOf(state.peers.map { listOf(it.id,it.phase,it.gatewayAvailable) },carried,state.receipts,state.relayEnabled,carriedPage).toString()
        if(key==renderKey)return;renderKey=key;val panel=listPanel ?: return;panel.removeAllViews()
        val peers=state.peers.filter { it.phase !in setOf(PeerPhase.LOST,PeerPhase.REJECTED) }
        if(peers.isEmpty()) panel.addView(label("No nearby devices right now. Reports remain saved while ResQMesh searches.",15,muted),spaced())
        peers.forEach { peer ->
            val c=card();c.addView(label(peer.id,17,navy,true));c.addView(label(if(peer.gatewayAvailable) "Gateway available · peer reported" else when(peer.phase) { PeerPhase.CONNECTED -> "Connected relay"; PeerPhase.DISCOVERED -> "Discovered"; PeerPhase.AUTHENTICATING -> "Confirm connection code"; else -> "Connecting…" },14,if(peer.gatewayAvailable)teal else muted),spaced(5));panel.addView(c,spaced())
        }
        panel.addView(label("Reports carried for others",19,navy,true),spaced(22))
        panel.addView(label("${carried.size} reports stored from other devices",13,muted),spaced(6))
        val pages=((carried.size+4)/5).coerceAtLeast(1);carriedPage=carriedPage.coerceIn(0,pages-1)
        carried.drop(carriedPage*5).take(5).forEach { item -> panel.addView(reportRow(item,true),spaced(10)) }
        if(carried.size>5) panel.addView(pager(carriedPage,pages) { page -> carriedPage=page;renderKey="";updatePeers() },spaced(14))
    }

    private fun updateLab() {
        val key=state.lab.toString();if(key==renderKey)return;renderKey=key;val panel=listPanel ?: return
        val y=scroll.scrollY;panel.removeAllViews()
        panel.addView(label("${state.lab.nodes.size} virtual devices · ${state.lab.links.size} links",14,muted,true))
        state.lab.nodes.forEach { node ->
            val c=card();c.addView(label(node.id + if(node.id==state.localNodeId) " · selected" else "",18,navy,true))
            c.addView(Switch(this).apply { text="Internet available";setTextColor(navy);minHeight=dp(52);isChecked=node.internet;setOnCheckedChangeListener { _,on -> mesh.simSetInternet(node.id,on) } },spaced(5))
            val links=state.lab.links.filter { it.first==node.id || it.second==node.id }.map { if(it.first==node.id)it.second else it.first }
            c.addView(label(if(links.isEmpty()) "No devices in range" else "In range: ${links.joinToString(", ")}",13,muted),spaced(5))
            if(node.id!=state.localNodeId)c.addView(action("View as this device",navy) { mesh.simSelectNode(node.id) },spaced())
            if(state.lab.nodes.size>1)c.addView(back("Remove virtual device") { mesh.simRemoveNode(node.id) },spaced(6))
            panel.addView(c,spaced())
        }
        if(state.lab.archivedNodeIds.isNotEmpty()) panel.addView(action("Restore devices with saved reports",navy) { mesh.restoreSavedSimulationNodes() },spaced())
        panel.addView(action("Open selected device Home") { show("home") },spaced(20))
        scroll.post { scroll.scrollTo(0,y) }
    }
    private fun resolveRelay() {
        if(state.relayPhase==RelayPhase.DISABLED) { mesh.setRelayEnabled(true);return }
        if(state.relayPhase==RelayPhase.NEEDS_PERMISSION) {
            val permissions=if(Build.VERSION.SDK_INT>=33) arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE,Manifest.permission.NEARBY_WIFI_DEVICES)
                else if(Build.VERSION.SDK_INT>=31) arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE,Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
                else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
            val missing=permissions.filter { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }
            if(missing.isNotEmpty()) requestPermissions(missing.toTypedArray(),70) else mesh.refresh()
        } else if(state.relayPhase==RelayPhase.RADIOS_OFF) {
            AlertDialog.Builder(this).setTitle("Enable nearby radios").setMessage("Turn on Bluetooth and Wi-Fi${if(Build.VERSION.SDK_INT<=32) ", and Location" else ""}. Return to ResQMesh to resume discovery.")
                .setPositiveButton("Open settings") { _,_-> startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }.setNegativeButton("Close",null).show()
        } else { toast(state.relayDetail ?: "Check Google Play services and nearby permissions in Android Settings.");mesh.refresh() }
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<String>,grants:IntArray) {
        super.onRequestPermissionsResult(code,permissions,grants)
        if (code == 74) {
            if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) changeBackgroundRelay(true)
            else { toast(tr(R.string.ui_allow_notifications_to_keep_the_background_relay_visible_relay_remains)); updateBackgroundControls() }
        }
        if(code==70) { mesh.refresh();if(grants.any { it!=PackageManager.PERMISSION_GRANTED })toast("Reports stay saved. Nearby relay needs the requested permissions.") }
        if (code == 73) {
            if (screen == "location" && locationProvider.hasPermission()) acquireEditorLocation()
            else { locationNotice = tr(R.string.ui_location_access_declined_add_a_landmark_or_continue_without_location); updateLocationSummary() }
        }
        if(code==71) {
            if (locationProvider.hasPermission()) acquireSosLocation()
            else { locationNotice = tr(R.string.ui_location_access_declined_your_sos_can_still_be_sent_with_the_details_a); updateLocationSummary() }
        }
        if (code == 72) {
            val capture = mediaCapture.state.value
            if (capture.kind != "audio" || capture.phase != CapturePhase.REQUESTING_PERMISSION) return
            val granted = grants.isNotEmpty() && grants.all { it == PackageManager.PERMISSION_GRANTED } &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!granted) mediaCapture.fail(tr(R.string.ui_microphone_access_was_not_allowed_try_again_choose_another_method_or_s))
            else if (screen == "media" && !isFinishing && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mediaCapture.startVoice()
            else mediaCapture.cancelPermissionRequest()
        }
    }
    private fun addField(parent:LinearLayout,key:String,title:String,hint:String,max:Int,multiline:Boolean=false,numeric:Boolean=false) {
        val caption=label(title,13,muted,true);parent.addView(caption,spaced(15))
        val f=field(hint,draft[key].orEmpty(),max).apply {
            id=View.generateViewId();minLines=if(multiline)3 else 1
            inputType=if(numeric)InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or (if(multiline)InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            if(multiline)gravity=Gravity.TOP
        };caption.labelFor=f.id;fields[key]=f;parent.addView(f,spaced(5))
        f.addTextChangedListener(afterChanged {
            draft[key] = f.text.toString()
            if (key !in locationKeys) persistDraft()
        })
        if (key in locationKeys) f.addTextChangedListener(afterChanged {
            locationProvider.cancel(); locationPending = false
            draft[key] = f.text.toString()
            // A changed description no longer claims the old saved or measured location.
            draftLocation = if (locationKeys.any { !draft[it].isNullOrBlank() }) LocationContext("manual", System.currentTimeMillis()) else LocationContext()
            locationNotice = null; updateLocationSummary(); persistDraft()
        })
    }
    private fun location(r:Report)=listOfNotNull(r.building,r.locationText,r.floor?.let { tr(R.string.floor_value, it) },r.room?.let { tr(R.string.room_value, it) },r.zone).filter { it.isNotBlank() }.joinToString(" · ").ifBlank {
        if (r.locationContext.latitude != null) String.format(Locale.ROOT, "%.5f, %.5f", r.locationContext.latitude, r.locationContext.longitude) else tr(R.string.ui_location_unknown)
    }
    private fun categoryTitle(type:String?)=type?.let { val i=EMERGENCY_TYPES.indexOf(it);if(i>=0)categoryNames[i] else tr(R.string.ui_emergency) } ?: tr(R.string.ui_emergency)
    private fun reportTitle(report: Report): String = if (report.emergencyType == "other" && report.attachments.isNotEmpty())
        if (report.attachments.size > 1) tr(R.string.ui_media_sos) else when (report.attachments.single().kind) { "audio" -> tr(R.string.ui_voice_sos); "image" -> tr(R.string.ui_photo_sos); "video" -> tr(R.string.ui_video_sos); else -> tr(R.string.ui_media_sos) }
        else categoryTitle(report.emergencyType)
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun label(value:String,size:Int,color:Int,bold:Boolean=false)=TextView(this).apply {
        text=value; textSize=size.toFloat(); setTextColor(color); setLineSpacing(dp(2).toFloat(),1f); includeFontPadding = true
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }
    private fun paragraph(value:String) { content.addView(label(value,14,muted),spaced(14)) }
    private fun sectionLabel(title: String, subtitle: String? = null) {
        content.addView(label(title, 18, navy, true).apply { if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }, spaced(22))
        subtitle?.let { content.addView(label(it, 12, muted), spaced(4)) }
    }
    private fun card()=column().apply { setPadding(dp(16),dp(16),dp(16),dp(16));background=shape(Color.WHITE,16).apply { setStroke(dp(1),line) } }
    private fun shape(color:Int,radius:Int)=GradientDrawable().apply { setColor(color);cornerRadius=dp(radius).toFloat() }
    private fun ripple(color: Int, radius: Int) = RippleDrawable(ColorStateList.valueOf(Color.argb(26, 17, 43, 55)), shape(color, radius), shape(Color.WHITE, radius))
    private fun icon(resource: Int, color: Int, size: Int) = getDrawable(resource)!!.mutate().apply { setTint(color); setBounds(0, 0, dp(size), dp(size)) }
    private fun action(title:String,color:Int=teal,click:()->Unit)=Button(this).apply {
        text=title; textSize=15f; isAllCaps=false; minHeight=dp(52); minimumWidth=0
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(Color.rgb(112,124,130),Color.WHITE)))
        background=ripple(color,12); stateListAnimator=null; typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        setPadding(dp(16),dp(12),dp(16),dp(12));setOnClickListener { click() }
    }
    private fun secondary(title: String, glyph: Int? = null, click: () -> Unit) = action(title, Color.WHITE, click).apply {
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(Color.rgb(139,151,157),navy)))
        background = RippleDrawable(ColorStateList.valueOf(softTeal), shape(Color.WHITE, 12).apply { setStroke(dp(1), line) }, shape(Color.WHITE, 12))
        glyph?.let { setCompoundDrawables(icon(it, teal, 20), null, null, null); compoundDrawablePadding=dp(8) }
    }
    private fun tile(title: String, glyph: Int, emphasis: Boolean, click: () -> Unit) = Button(this).apply {
        text = title; textSize = 15f; isAllCaps = false; minimumWidth = 0; minHeight = dp(96)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setTextColor(if (emphasis) red else navy)
        background = RippleDrawable(ColorStateList.valueOf(softTeal), shape(if (emphasis) softRed else Color.WHITE,16).apply { setStroke(dp(1), if (emphasis) Color.rgb(239, 207, 198) else line) }, shape(Color.WHITE,16))
        stateListAnimator = null; setPadding(dp(8), dp(16), dp(8), dp(14))
        setCompoundDrawables(null, icon(glyph, if (emphasis) red else teal, 27), null, null); compoundDrawablePadding = dp(9)
        setOnClickListener { click() }
    }
    private fun back(title:String,click:()->Unit)=Button(this).apply {
        text=title;isAllCaps=false;textSize=14f;minHeight=dp(48);minimumWidth=0;setTextColor(teal);background=ripple(Color.TRANSPARENT,8);stateListAnimator=null
        gravity=Gravity.START or Gravity.CENTER_VERTICAL;setPadding(0,dp(8),dp(8),dp(8));setOnClickListener { click() }
        if (title.startsWith("Back") || title == tr(R.string.ui_edit_details)) { setCompoundDrawables(icon(R.drawable.ux_arrow_left,teal,18),null,null,null);compoundDrawablePadding=dp(7) }
    }
    private fun disclosure(title: String, glyph: Int, initiallyExpanded: Boolean, changed: (Boolean) -> Unit): Button {
        var expanded = initiallyExpanded
        val button = back(title) {}.apply { textSize=17f; compoundDrawablePadding=dp(10) }
        fun update() {
            val state = if (expanded) tr(R.string.ui_expanded) else tr(R.string.ui_collapsed)
            if (Build.VERSION.SDK_INT >= 30) button.stateDescription = state
            else button.contentDescription = "$title, $state"
            val arrow = RotateDrawable().apply {
                drawable=icon(R.drawable.ux_chevron_right,teal,18); fromDegrees=0f; toDegrees=90f
                setBounds(0,0,dp(18),dp(18)); level=if(expanded) 10000 else 0
            }
            button.setCompoundDrawables(icon(glyph,teal,20),null,arrow,null)
        }
        button.setOnClickListener { expanded=!expanded;update();changed(expanded) }
        update();return button
    }
    private fun field(hintText:String,value:String,max:Int)=EditText(this).apply {
        hint=hintText;setText(value);textSize=16f;setTextColor(navy);setHintTextColor(muted);background=shape(cream,10).apply { setStroke(dp(1),line) }
        setPadding(dp(12),dp(12),dp(12),dp(12));filters=arrayOf(InputFilter.LengthFilter(max));minHeight=dp(52)
    }
    private fun notice(parent: LinearLayout, title: String, message: String) {
        val box = column().apply { background = shape(Color.rgb(255,246,225),12); setPadding(dp(14),dp(12),dp(14),dp(12)) }
        box.addView(label(title,14,amber,true)); box.addView(label(message,13,amber),spaced(5)); parent.addView(box,spaced(12))
    }
    private fun submissionFailure(parent: LinearLayout) {
        submissionError?.let { notice(parent, tr(R.string.ui_sos_was_not_saved), "$it\nYour draft is still here. Use the send button to try again.") }
    }
    private fun spinner(items:List<String>)=Spinner(this).apply { minimumHeight=dp(52);adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,items) }
    private fun spaced(top:Int=12)=LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(top) }
    private fun toast(value:String) {
        activeToast?.cancel()
        activeToast = Toast.makeText(this,value,Toast.LENGTH_LONG).also { it.show() }
    }
    private fun formatTime(time:Long)=SimpleDateFormat("dd MMM, HH:mm:ss",Locale.ENGLISH).format(Date(time))
}
