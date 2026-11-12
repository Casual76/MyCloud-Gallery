package dev.pampa.mycloudgallery.feature.update

import android.content.Context
import dev.antigravity.fluidengine.foundation.UpdateChannel

// LE SCELTE DELL'AGGIORNAMENTO SU QUESTO DISPOSITIVO (pacchetto U, 12/11/2026): il canale (Stabile di serie), l'ora dell'ultimo controllo e la versione
// gia' annunciata dall'avviso. Un file privato dell'app, come l'icona (I5): mai sincronizzato, mai in un backup (allowBackup="false").

interface UpdateStore {
  fun channel(): UpdateChannel

  fun setChannel(channel: UpdateChannel)

  fun lastCheckedAt(): Long?

  fun setLastCheckedAt(millis: Long)

  fun promptedVersion(): String?

  fun setPromptedVersion(version: String)
}

class PrefsUpdateStore(context: Context) : UpdateStore {
  private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  override fun channel(): UpdateChannel = channelOf(prefs.getString(KEY_CHANNEL, null))

  override fun setChannel(channel: UpdateChannel) {
    prefs.edit().putString(KEY_CHANNEL, codeOf(channel)).apply()
  }

  override fun lastCheckedAt(): Long? = prefs.getLong(KEY_LAST_CHECK, NEVER).takeIf { it != NEVER }

  override fun setLastCheckedAt(millis: Long) {
    prefs.edit().putLong(KEY_LAST_CHECK, millis).apply()
  }

  override fun promptedVersion(): String? = prefs.getString(KEY_PROMPTED, null)

  override fun setPromptedVersion(version: String) {
    prefs.edit().putString(KEY_PROMPTED, version).apply()
  }

  companion object {
    const val FILE: String = "mcg_updates"
    const val KEY_CHANNEL: String = "channel"
    const val KEY_LAST_CHECK: String = "last_check"
    const val KEY_PROMPTED: String = "prompted_version"
    private const val NEVER = Long.MIN_VALUE

    /** I codici salvati: stabili, indipendenti dal nome dell'enum dell'engine. Un valore sconosciuto vale Stabile, la scelta di serie. */
    fun codeOf(channel: UpdateChannel): String = when (channel) {
      UpdateChannel.STABLE -> "stable"
      UpdateChannel.BETA -> "beta"
    }

    fun channelOf(code: String?): UpdateChannel = if (code == "beta") UpdateChannel.BETA else UpdateChannel.STABLE
  }
}
