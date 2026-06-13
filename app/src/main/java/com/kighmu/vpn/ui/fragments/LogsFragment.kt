package com.kighmu.vpn.ui.fragments

import android.graphics.Color
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.kighmu.vpn.R
import com.kighmu.vpn.models.LogEntry
import com.kighmu.vpn.ui.MainViewModel
import kotlinx.coroutines.launch

class LogsFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var tvLogs: TextView
    private lateinit var scrollView: ScrollView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_logs, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvLogs = view.findViewById(R.id.tv_logs)
        scrollView = view.findViewById(R.id.scroll_logs)
        val btnClear = view.findViewById<Button>(R.id.btn_clear_logs)

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.logs.collect { logs ->
                val sb = SpannableStringBuilder()
                logs.forEach { entry ->
                    val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date(entry.timestamp))
                    
                    val start = sb.length
                    sb.append("[$time] ")
                    
                    // Support HTML pour le message
                    val htmlMsg = if (entry.message.contains("<font") || entry.message.contains("<b>")) {
                        android.text.Html.fromHtml(entry.message, android.text.Html.FROM_HTML_MODE_COMPACT)
                    } else {
                        entry.message
                    }
                    sb.append(htmlMsg)
                    sb.append("\n")
                    
                    // Couleur de ligne par défaut si pas de HTML
                    if (!entry.message.contains("<font")) {
                        val color = getLineColor(entry)
                        sb.setSpan(
                            ForegroundColorSpan(color),
                            start, sb.length,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
                tvLogs.text = sb
                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
            }
        }

        btnClear.setOnClickListener { viewModel.clearLogs() }
    }



    private fun getLineColor(entry: LogEntry): Int {
        val msg = entry.message.lowercase()
        return when {
            // Vert - connecté
            msg.contains("connecté") || msg.contains("connected") ||
            msg.contains("✅") || msg.contains("running") ||
            msg.contains("prêt") || msg.contains("tunnel actif") -> Color.parseColor("#00C853")

            // Rouge - erreur/déconnexion
            msg.contains("déconnect") || msg.contains("disconnect") ||
            msg.contains("error") || msg.contains("erreur") ||
            msg.contains("❌") || msg.contains("failed") ||
            msg.contains("refused") || msg.contains("crash") ||
            msg.contains("timeout") -> Color.parseColor("#FF1744")

            // Orange - avertissement/reconnexion
            msg.contains("reconnect") || msg.contains("attente") ||
            msg.contains("retry") || msg.contains("warning") -> Color.parseColor("#FF9100")

            // Niveau ERROR du logger
            entry.level == LogEntry.LogLevel.ERROR -> Color.parseColor("#FF1744")
            entry.level == LogEntry.LogLevel.WARNING -> Color.parseColor("#FF9100")

            // Blanc par défaut
            else -> Color.WHITE
        }
    }
}
