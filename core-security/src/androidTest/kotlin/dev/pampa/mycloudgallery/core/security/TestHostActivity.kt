package dev.pampa.mycloudgallery.core.security

import android.os.Bundle
import android.view.View
import androidx.fragment.app.FragmentActivity

/**
 * L'activity di prova: una FragmentActivity (come dovra' diventare la MainActivity, perche'
 * BiometricPrompt la richiede) con una vista vuota. Si mostra anche sopra il blocco schermo.
 */
class TestHostActivity : FragmentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(View(this))
  }
}
