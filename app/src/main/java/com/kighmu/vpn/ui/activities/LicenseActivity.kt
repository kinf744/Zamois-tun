package com.kighmu.vpn.ui.activities

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.kighmu.vpn.BuildConfig
import com.kighmu.vpn.R

/**
 * LicenseActivity — Affiche le Contrat de Licence d'Utilisation Finale (CLUF)
 * de KIGHMU VPN. Accessible depuis le menu principal (⋮).
 */
class LicenseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_license)

        // Toolbar avec bouton retour
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar_license)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        // Afficher la version de l'application dans le pied de page
        val tvVersion = findViewById<TextView>(R.id.tv_license_version)
        tvVersion.text = "© 2024 KIGHMU VPN v${BuildConfig.VERSION_NAME} — Tous droits réservés"

        // Bouton "Accepter" — ferme simplement l'écran
        findViewById<Button>(R.id.btn_accept_license).setOnClickListener {
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
