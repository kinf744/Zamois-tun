package com.kighmu.vpn.ui.fragments

import android.graphics.Color
import android.text.SpannableString
import android.text.Spannable
import android.text.style.ForegroundColorSpan
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.kighmu.vpn.R
import com.kighmu.vpn.models.ConnectionStatus
import com.kighmu.vpn.models.TunnelMode
import com.kighmu.vpn.ui.MainViewModel
import com.kighmu.vpn.ui.activities.MainActivity
import kotlinx.coroutines.launch

class HomeFragment : Fragment() {

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var btnConnect: com.google.android.material.button.MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var spinnerMode: Spinner
    private lateinit var viewGlowRing: View
    private lateinit var cardUserMessage: androidx.cardview.widget.CardView
    private lateinit var tvUserMessage: TextView
    private lateinit var tvDownloadSpeed: TextView
    private lateinit var tvUploadSpeed: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
            btnConnect = view.findViewById(R.id.btn_connect)
            tvStatus = view.findViewById(R.id.tv_connection_status)
            viewGlowRing = view.findViewById(R.id.view_glow_ring)
            cardUserMessage = view.findViewById(R.id.card_user_message)
            tvUserMessage = view.findViewById(R.id.tv_user_message)
            tvDownloadSpeed = view.findViewById(R.id.tv_download_speed)
            tvUploadSpeed = view.findViewById(R.id.tv_upload_speed)


            // --- Message config exportée ---
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.config.collect { cfg ->
                    val msg = cfg.exportConfig?.userMessage ?: ""
                    if (msg.isNotBlank()) {
                        tvUserMessage.text = msg
                        cardUserMessage.visibility = View.VISIBLE
                    } else {
                        cardUserMessage.visibility = View.GONE
                    }
                }
            }

            // --- Spinner tunnel mode ---
            spinnerMode = view.findViewById(R.id.spinner_tunnel_mode)
            val cfg = viewModel.config.value
            val allModes = TunnelMode.values().toList()
            val filteredModes = if (cfg.enabledTunnels.isEmpty()) allModes
                                else allModes.filter { it.id in cfg.enabledTunnels }
            val displayModes = filteredModes.ifEmpty { allModes }

            val adapter = object : ArrayAdapter<String>(
                requireContext(),
                R.layout.spinner_item,
                displayModes.map { it.label }
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getView(position, convertView, parent)
                    (v as TextView).setTextColor(Color.WHITE)
                    v.setPadding(32, 0, 32, 0)
                    return v
                }
            }
            adapter.setDropDownViewResource(R.layout.spinner_item)
            spinnerMode.adapter = adapter

            val currentIdx = displayModes.indexOfFirst { it == cfg.tunnelMode }.takeIf { it >= 0 } ?: 0
            spinnerMode.setSelection(currentIdx)

            spinnerMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    val selectedMode = displayModes[position]
                    if (selectedMode != viewModel.config.value.tunnelMode) {
                        viewModel.updateTunnelMode(selectedMode)
                    }
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
            }

            // --- Bouton connexion ---
            btnConnect.setOnClickListener {
                val activity = requireActivity() as MainActivity
                when (viewModel.connectionStatus.value) {
                    ConnectionStatus.CONNECTED,
                    ConnectionStatus.CONNECTING,
                    ConnectionStatus.RECONNECTING,
                    ConnectionStatus.ERROR -> activity.requestVpnDisconnect()
                    else -> activity.requestVpnConnect()
                }
            }

            // --- Statut connexion + couleur anneau ---
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.connectionStatus.collect { status ->
                    tvStatus.text = when (status) {
                        ConnectionStatus.CONNECTED -> "CONNECTED"
                        ConnectionStatus.CONNECTING,
                        ConnectionStatus.RECONNECTING -> "CONNECTING"
                        else -> "DISCONNECTED"
                    }
                    val color = when (status) {
                        ConnectionStatus.CONNECTED ->
                            Color.parseColor("#4CAF50")
                        ConnectionStatus.CONNECTING,
                        ConnectionStatus.RECONNECTING,
                        ConnectionStatus.ERROR ->
                            Color.parseColor("#F44336")
                        else ->
                            Color.parseColor("#1F6FEB")
                    }
                    viewGlowRing.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(color)
                    btnConnect.setStrokeColor(
                        android.content.res.ColorStateList.valueOf(color)
                    )
                }
            }

            // --- Sync spinner avec config ---
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.config.collect { cfg ->
                    val idx = displayModes.indexOfFirst { it == cfg.tunnelMode }.coerceAtLeast(0)
                    spinnerMode.setSelection(idx)
                }
            }

            // --- Stats réseau temps réel ---
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.stats.collect { stats ->
                    tvDownloadSpeed.text = stats.formatDownload()
                    tvUploadSpeed.text = stats.formatUpload()
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
