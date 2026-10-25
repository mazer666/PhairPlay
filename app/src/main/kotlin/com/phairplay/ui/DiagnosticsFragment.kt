package com.phairplay.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.phairplay.BuildConfig
import com.phairplay.R
import com.phairplay.diagnostics.DiagnosticProtocol
import com.phairplay.diagnostics.DiagnosticsLabels
import com.phairplay.diagnostics.DiagnosticsPlatform
import com.phairplay.diagnostics.DiagnosticsPort
import com.phairplay.diagnostics.DiagnosticsPorts
import com.phairplay.diagnostics.DiagnosticsReport
import com.phairplay.diagnostics.DiagnosticsReportBuilder
import com.phairplay.diagnostics.SelfTest
import com.phairplay.diagnostics.SelfTestResult
import com.phairplay.dlna.DlnaNetworkAddress
import com.phairplay.dlna.SsdpMessages
import com.phairplay.service.PhairPlayService
import com.phairplay.service.ProtocolState
import com.phairplay.service.ServiceState
import com.phairplay.settings.SettingsRepository
import com.phairplay.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DiagnosticsFragment — the screen that shows the state of the receiver.
 *
 * WHY: the owner of the television is not an Android developer and has no logcat.
 * When something does not work, the only thing that helps is a screen that shows
 * the address of the device, the state of every protocol, the ports that are in use
 * and the result of a self test — with numbers instead of guesses.
 *
 * HOW: the screen binds to [PhairPlayService] and follows the published states, so
 * the report is always current. The self test runs on a background thread and writes
 * its results into the report. The whole report is one text block that can be copied
 * into the clipboard, because the fastest way to get help is to paste the report
 * into a message.
 *
 * Navigation: reached from the "Diagnostics" row of the settings screen.
 */
class DiagnosticsFragment : Fragment() {

    // View references — bound in onViewCreated
    private lateinit var textReport: TextView
    private lateinit var textStatus: TextView
    private lateinit var buttonCopy: Button
    private lateinit var buttonSelfTest: Button

    // The labels of the report — read once from the resources.
    private var labels: DiagnosticsLabels? = null

    // Service binding — gives access to the published states of the receivers.
    private var service: PhairPlayService? = null
    private var isBound = false

    // The values of the report.
    private var displayName: String = ""
    private var localAddress: String = ""
    private var serviceState: ServiceState = ServiceState.Stopped
    private val protocolStates = mutableMapOf<DiagnosticProtocol, String>()
    private var selfTestResults: List<SelfTestResult> = emptyList()
    private var descriptionPort: Int? = null
    private var selfTestRunning = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? PhairPlayService.LocalBinder)?.getService()
            isBound = true
            Logger.d("DiagnosticsFragment: bound to PhairPlayService")
            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
            Logger.d("DiagnosticsFragment: unbound from PhairPlayService")
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_diagnostics, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        labels = DiagnosticsLabelsFactory.create(requireContext())
        configureButtons()
        loadDisplayName()
        loadLocalAddress()
        renderReport()
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(requireContext(), PhairPlayService::class.java)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            requireContext().unbindService(serviceConnection)
            isBound = false
        }
    }

    // ─── View Setup ──────────────────────────────────────────────────────────

    private fun bindViews(view: View) {
        textReport = view.findViewById(R.id.text_report)
        textStatus = view.findViewById(R.id.text_status)
        buttonCopy = view.findViewById(R.id.btn_copy_report)
        buttonSelfTest = view.findViewById(R.id.btn_run_self_test)
    }

    /** Installs the click listeners of the two buttons. */
    private fun configureButtons() {
        buttonCopy.setOnClickListener { copyReportToClipboard() }
        buttonSelfTest.setOnClickListener { runSelfTest() }
    }

    /**
     * Reads the name the television shows in the sender pickers.
     *
     * WHY the setting and not the system name: the setting is what a sender sees.
     * A report that names another device would send the owner looking for the
     * wrong entry in the picker.
     */
    private fun loadDisplayName() {
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = SettingsRepository(requireContext()).settingsFlow.first()
            displayName = settings.effectiveDisplayName
            renderReport()
        }
    }

    /**
     * Reads the IPv4 address the receiver advertises.
     *
     * WHY it runs in the background: the lookup walks the network interfaces of the
     * device. It is fast, but it is I/O and does not belong on the main thread.
     */
    private fun loadLocalAddress() {
        viewLifecycleOwner.lifecycleScope.launch {
            val address = withContext(Dispatchers.IO) { DlnaNetworkAddress.localIpv4Address() }
            localAddress = address
            renderReport()
        }
    }

    // ─── Service State Observation ──────────────────────────────────────────

    /** Follows the published states of the service and rebuilds the report on a change. */
    private fun observeServiceState() {
        val svc = service ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            svc.serviceState.collectLatest { state ->
                serviceState = state
                renderReport()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            svc.airPlayState.collectLatest { state -> updateProtocol(DiagnosticProtocol.AIRPLAY, state) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            svc.miracastState.collectLatest { state -> updateProtocol(DiagnosticProtocol.MIRACAST, state) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            svc.castState.collectLatest { state -> updateProtocol(DiagnosticProtocol.CAST, state) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            svc.dlnaState.collectLatest { state -> updateProtocol(DiagnosticProtocol.DLNA, state) }
        }
    }

    /** Stores the state of one protocol and rebuilds the report. */
    private fun updateProtocol(protocol: DiagnosticProtocol, state: ProtocolState) {
        protocolStates[protocol] = DiagnosticsLabelsFactory.protocolStateText(requireContext(), state)
        renderReport()
    }

    // ─── The Report ──────────────────────────────────────────────────────────

    /** Builds the report from the current values and writes it into the text field. */
    private fun renderReport() {
        val labels = labels ?: return
        val context = context ?: return
        val report = DiagnosticsReport(
            deviceName = displayName,
            localAddress = localAddress,
            appVersion = appVersion(),
            platform = platform(),
            serviceState = DiagnosticsLabelsFactory.serviceStateText(context, serviceState),
            protocolStates = protocolStates.toMap(),
            ports = ports(labels),
            selfTestResults = selfTestResults
        )
        textReport.text = DiagnosticsReportBuilder.build(report, labels)
    }

    /**
     * The version of the build.
     *
     * WHY the version code is added: the version name carries the flavour suffix,
     * so "1.0.0-alpha.1-googletv" alone does not say which build of the release the
     * owner is running. The version code does.
     */
    private fun appVersion(): String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    /**
     * The platform of the build.
     *
     * WHY the flavour decides and not the device: the flavour is fixed when the app
     * is built. A device that reports what it thinks it is would name a platform
     * the build does not have.
     */
    private fun platform(): String = when (BuildConfig.FLAVOR) {
        FIRE_TV_FLAVOR -> DiagnosticsPlatform.FIRE_TV
        else -> DiagnosticsPlatform.GOOGLE_TV
    }

    /** The ports the receiver listens on. */
    private fun ports(labels: DiagnosticsLabels): List<DiagnosticsPort> = listOf(
        DiagnosticsPort(labels.portAirPlay, DiagnosticsPorts.AIRPLAY),
        DiagnosticsPort(labels.portDlnaDescription, descriptionPort ?: 0),
        DiagnosticsPort(labels.portSsdp, SsdpMessages.PORT)
    )

    // ─── The Buttons ─────────────────────────────────────────────────────────

    /**
     * Copies the report into the clipboard.
     *
     * WHY the whole report: the fastest way to get help is to paste the report into
     * a message. The report carries the address of the television and its name —
     * both of which the owner has to share anyway — and nothing else.
     */
    private fun copyReportToClipboard() {
        val text = textReport.text?.toString().orEmpty()
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard == null) {
            Toast.makeText(requireContext(), R.string.diagnostics_copy_failed, Toast.LENGTH_SHORT).show()
            return
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.diagnostics_title), text))
        Toast.makeText(requireContext(), R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * Runs the self test and writes its results into the report.
     *
     * WHY it runs in the background: the self test opens sockets and waits for
     * answers of the device itself. It must not block the screen.
     */
    private fun runSelfTest() {
        val labels = labels ?: return
        if (selfTestRunning) return
        selfTestRunning = true
        buttonSelfTest.isEnabled = false
        textStatus.setText(R.string.diagnostics_running)
        viewLifecycleOwner.lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { SelfTest(labels).run() }
            selfTestResults = outcome.results
            descriptionPort = outcome.dlnaDescriptionPort
            selfTestRunning = false
            buttonSelfTest.isEnabled = true
            renderReport()
            val failed = outcome.results.count { !it.ok }
            textStatus.text = resources.getQuantityString(
                R.plurals.diagnostics_finished,
                outcome.results.size,
                failed,
                outcome.results.size
            )
        }
    }

    companion object {
        /** The name of the product flavour that builds for Amazon Fire TV. */
        private const val FIRE_TV_FLAVOR = "firetv"
    }
}
