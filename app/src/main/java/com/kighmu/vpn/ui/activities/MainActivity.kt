package com.kighmu.vpn.ui.activities

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.kighmu.vpn.R
import com.kighmu.vpn.ui.MainViewModel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    val viewModel: MainViewModel by viewModels()

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.startVpn(this)
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.importConfig(this, it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        val navView = findViewById<BottomNavigationView>(R.id.bottom_nav)
        navView.setupWithNavController(navController)
        lifecycleScope.launch {
            viewModel.importResult.collect { message ->
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        handleIncomingIntent(intent)
        val tvToolbarTitle = findViewById<android.widget.TextView>(R.id.tv_toolbar_title)
        val titleFull = "KIGHMU VPN"
        val spannable = android.text.SpannableString(titleFull)
        spannable.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#E0F2FE")), 0, 6, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#1F6FEB")), 6, titleFull.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        tvToolbarTitle.text = spannable
        supportActionBar?.setDisplayShowTitleEnabled(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        handleIncomingIntent(intent)
        val tvToolbarTitle = findViewById<android.widget.TextView>(R.id.tv_toolbar_title)
        val titleFull = "KIGHMU VPN"
        val spannable = android.text.SpannableString(titleFull)
        spannable.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#E0F2FE")), 0, 6, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#1F6FEB")), 6, titleFull.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        tvToolbarTitle.text = spannable
        supportActionBar?.setDisplayShowTitleEnabled(false)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_import -> { startActivity(Intent(this, ImportActivity::class.java)); true }
            R.id.action_export -> {
                startActivity(Intent(this, ExportActivity::class.java))
                true
            }
            R.id.action_reset -> {
                viewModel.resetConfig()
                Toast.makeText(this, "Application réinitialisée", Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_license -> {
                startActivity(Intent(this, LicenseActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun requestVpnConnect() {
        val permIntent = viewModel.startVpn(this)
        if (permIntent != null) vpnPermissionLauncher.launch(permIntent)
    }

    fun requestVpnDisconnect() { viewModel.stopVpn(this) }

    fun showMessage(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return
            viewModel.importConfig(this, uri)
        }
    }
}