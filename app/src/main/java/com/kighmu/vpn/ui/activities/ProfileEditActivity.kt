package com.kighmu.vpn.ui.activities

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.gson.Gson
import com.kighmu.vpn.R
import com.kighmu.vpn.profiles.SlowDnsProfile
import android.widget.EditText

class ProfileEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE = "extra_profile"
        const val EXTRA_RESULT  = "extra_result"
        const val REQUEST_CODE  = 1001

        fun start(context: Context, profile: SlowDnsProfile? = null) {
            val intent = Intent(context, ProfileEditActivity::class.java)
            if (profile != null) intent.putExtra(EXTRA_PROFILE, Gson().toJson(profile))
            (context as? Activity)?.startActivityForResult(intent, REQUEST_CODE)
        }
    }

    private lateinit var etName      : EditText
    private lateinit var etSshHost   : EditText
    private lateinit var etSshPort   : EditText
    private lateinit var etSshUser   : EditText
    private lateinit var etSshPass   : EditText
    private lateinit var etDns       : EditText
    private lateinit var etNs        : EditText
    private lateinit var etPubKey    : EditText
    private lateinit var etProxyHost : EditText
    private lateinit var etProxyPort : EditText
    private lateinit var etPayload   : EditText
    private lateinit var tvTunnelCount: TextView
    private lateinit var seekTunnel  : SeekBar
    private lateinit var btnSave     : Button
    private lateinit var btnCancel   : Button

    private var profile = SlowDnsProfile()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_edit)

        // Charger profil existant si édition
        intent.getStringExtra(EXTRA_PROFILE)?.let {
            profile = Gson().fromJson(it, SlowDnsProfile::class.java)
        }

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = if (intent.hasExtra(EXTRA_PROFILE)) "Modifier profil" else "Nouveau profil"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        etName       = findViewById(R.id.et_name)
        etSshHost    = findViewById(R.id.et_ssh_host)
        etSshPort    = findViewById(R.id.et_ssh_port)
        etSshUser    = findViewById(R.id.et_ssh_user)
        etSshPass    = findViewById(R.id.et_ssh_pass)
        etDns        = findViewById(R.id.et_dns)
        etNs         = findViewById(R.id.et_ns)
        etPubKey     = findViewById(R.id.et_pubkey)
        etProxyHost  = findViewById(R.id.et_proxy_host)
        etProxyPort  = findViewById(R.id.et_proxy_port)
        etPayload    = findViewById(R.id.et_payload)
        tvTunnelCount = findViewById(R.id.tv_tunnel_count)
        seekTunnel   = findViewById(R.id.seek_tunnel)
        btnSave      = findViewById(R.id.btn_save)
        btnCancel    = findViewById(R.id.btn_cancel)

        // Remplir les champs
        etName.setText(profile.profileName)
        etSshHost.setText(profile.sshHost)
        etSshPort.setText(profile.sshPort.toString())
        etSshUser.setText(profile.sshUser)
        etSshPass.setText(profile.sshPass)
        etDns.setText(profile.dnsServer)
        etNs.setText(profile.nameserver)
        etPubKey.setText(profile.publicKey)
        etProxyHost.setText(profile.proxyHost)
        etProxyPort.setText(profile.proxyPort.toString())
        etPayload.setText(profile.customPayload)
        seekTunnel.progress = profile.tunnelCount.coerceIn(1, 4) - 1
        tvTunnelCount.text = "Flux simultanes : ${profile.tunnelCount}"

        seekTunnel.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, v: Int, u: Boolean) {
                tvTunnelCount.text = "Flux simultanes : ${v + 1}"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnSave.setOnClickListener {
            val updated = profile.copy(
                profileName   = etName.text.toString().ifEmpty { "Profil" },
                sshHost       = etSshHost.text.toString(),
                sshPort       = etSshPort.text.toString().toIntOrNull() ?: 22,
                sshUser       = etSshUser.text.toString(),
                sshPass       = etSshPass.text.toString(),
                dnsServer     = etDns.text.toString().ifEmpty { "8.8.8.8" },
                nameserver    = etNs.text.toString(),
                publicKey     = etPubKey.text.toString(),
                proxyHost     = etProxyHost.text.toString(),
                proxyPort     = etProxyPort.text.toString().toIntOrNull() ?: 22,
                customPayload = etPayload.text.toString(),
                tunnelCount   = seekTunnel.progress + 1
            )
            val result = Intent()
            result.putExtra(EXTRA_RESULT, Gson().toJson(updated))
            setResult(Activity.RESULT_OK, result)
            finish()
        }

        btnCancel.setOnClickListener { finish() }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
