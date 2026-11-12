package dev.pampa.mycloudgallery.feature.update

import android.content.Context
import android.content.SharedPreferences
import dev.antigravity.fluidengine.foundation.AvailableAppUpdate
import dev.antigravity.fluidengine.foundation.UpdateChannel

// LE SCELTE E LA MEMORIA DELL'AGGIORNAMENTO SU QUESTO DISPOSITIVO (pacchetto U, 12/11/2026; giro 1 delle critiche, UU-2/UU-3/UU-4). Un file privato
// dell'app, come l'icona (I5): mai sincronizzato, mai in un backup (allowBackup="false"). Dentro:
//  - il canale (Stabile di serie) e l'ora dell'ultimo controllo;
//  - l'ultima versione trovata ([SavedOffer]): la pagina e il segno sulla voce la mostrano subito anche dopo un riavvio, senza aspettare la rete;
//  - il promemoria dell'avviso ([Reminder]): «Dopo» rimanda di tre giorni, al piu' tre volte per la versione installata;
//  - il tentativo in corso ([Attempt]): il permesso chiesto (se Android chiude l'app per darlo, al ritorno si riprende) o l'installazione partita (alla
//    prima apertura con la versione nuova l'app dice «Aggiornata»). Il consenso ai dati mobili e' del tentativo, non di tutti (US-2).

/** L'ultima versione trovata sul canale [channel], come l'ha pulita `UpdateRules.cleanOffer`. */
data class SavedOffer(val channel: UpdateChannel, val update: AvailableAppUpdate)

/** Il promemoria dell'avviso: rimandato fino a [snoozedUntil]; [declines] «Dopo» detti con la versione installata [forInstalled]. */
data class Reminder(val snoozedUntil: Long? = null, val declines: Int = 0, val forInstalled: String? = null)

/** A che punto e' un tentativo d'installazione salvato. */
enum class AttemptStage(val code: String) {
  /** Si aspetta il permesso «installa app sconosciute». */
  PERMISSION("permission"),

  /** La sessione di Android e' partita: se l'app riparte con [Attempt.version], l'aggiornamento e' riuscito. */
  INSTALLING("installing"),
}

/**
 * Un tentativo partito da un tocco su «Aggiorna»: la versione, il passo, se l'utente ha detto «Scarica lo stesso» sui dati mobili ([meteredConsent],
 * solo per questo tentativo), quando ([startedAt]) e da quale versione installata ([from]).
 */
data class Attempt(val version: String, val stage: AttemptStage, val meteredConsent: Boolean, val startedAt: Long, val from: String)

interface UpdateStore {
  fun channel(): UpdateChannel

  fun setChannel(channel: UpdateChannel)

  fun lastCheckedAt(): Long?

  fun setLastCheckedAt(millis: Long)

  fun offer(): SavedOffer?

  fun setOffer(offer: SavedOffer?)

  fun reminder(): Reminder

  fun setReminder(reminder: Reminder)

  fun attempt(): Attempt?

  fun setAttempt(attempt: Attempt?)
}

class PrefsUpdateStore(context: Context) : UpdateStore {
  private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  override fun channel(): UpdateChannel = channelOf(prefs.getString(KEY_CHANNEL, null))

  override fun setChannel(channel: UpdateChannel) {
    prefs.edit().putString(KEY_CHANNEL, codeOf(channel)).apply()
  }

  override fun lastCheckedAt(): Long? = prefs.long(KEY_LAST_CHECK)

  override fun setLastCheckedAt(millis: Long) {
    prefs.edit().putLong(KEY_LAST_CHECK, millis).apply()
  }

  override fun offer(): SavedOffer? {
    val version = prefs.getString(KEY_OFFER_VERSION, null) ?: return null
    return SavedOffer(
      channel = channelOf(prefs.getString(KEY_OFFER_CHANNEL, null)),
      update = AvailableAppUpdate(
        version = version,
        changelog = prefs.getString(KEY_OFFER_NOTES, null).orEmpty(),
        releaseTag = prefs.getString(KEY_OFFER_TAG, null).orEmpty(),
        apkAsset = prefs.getString(KEY_OFFER_ASSET, null).orEmpty(),
        downloadUrl = prefs.getString(KEY_OFFER_URL, null).orEmpty(),
        sizeBytes = prefs.getLong(KEY_OFFER_SIZE, 0L),
        sha256 = prefs.getString(KEY_OFFER_SHA, null).orEmpty(),
      ),
    )
  }

  override fun setOffer(offer: SavedOffer?) {
    val edit = prefs.edit()
    if (offer == null) {
      OFFER_KEYS.forEach(edit::remove)
    } else {
      edit.putString(KEY_OFFER_CHANNEL, codeOf(offer.channel))
        .putString(KEY_OFFER_VERSION, offer.update.version)
        .putString(KEY_OFFER_NOTES, offer.update.changelog)
        .putString(KEY_OFFER_TAG, offer.update.releaseTag)
        .putString(KEY_OFFER_ASSET, offer.update.apkAsset)
        .putString(KEY_OFFER_URL, offer.update.downloadUrl)
        .putLong(KEY_OFFER_SIZE, offer.update.sizeBytes)
        .putString(KEY_OFFER_SHA, offer.update.sha256)
    }
    edit.apply()
  }

  override fun reminder(): Reminder = Reminder(
    snoozedUntil = prefs.long(KEY_SNOOZED_UNTIL),
    declines = prefs.getInt(KEY_DECLINES, 0),
    forInstalled = prefs.getString(KEY_DECLINES_FOR, null),
  )

  override fun setReminder(reminder: Reminder) {
    val edit = prefs.edit()
    if (reminder.snoozedUntil == null) edit.remove(KEY_SNOOZED_UNTIL) else edit.putLong(KEY_SNOOZED_UNTIL, reminder.snoozedUntil)
    edit.putInt(KEY_DECLINES, reminder.declines)
    if (reminder.forInstalled == null) edit.remove(KEY_DECLINES_FOR) else edit.putString(KEY_DECLINES_FOR, reminder.forInstalled)
    edit.apply()
  }

  override fun attempt(): Attempt? {
    val version = prefs.getString(KEY_ATTEMPT_VERSION, null) ?: return null
    val stage = AttemptStage.entries.firstOrNull { it.code == prefs.getString(KEY_ATTEMPT_STAGE, null) } ?: return null
    return Attempt(
      version = version,
      stage = stage,
      meteredConsent = prefs.getBoolean(KEY_ATTEMPT_METERED, false),
      startedAt = prefs.getLong(KEY_ATTEMPT_AT, 0L),
      from = prefs.getString(KEY_ATTEMPT_FROM, null).orEmpty(),
    )
  }

  override fun setAttempt(attempt: Attempt?) {
    val edit = prefs.edit()
    if (attempt == null) {
      ATTEMPT_KEYS.forEach(edit::remove)
    } else {
      edit.putString(KEY_ATTEMPT_VERSION, attempt.version)
        .putString(KEY_ATTEMPT_STAGE, attempt.stage.code)
        .putBoolean(KEY_ATTEMPT_METERED, attempt.meteredConsent)
        .putLong(KEY_ATTEMPT_AT, attempt.startedAt)
        .putString(KEY_ATTEMPT_FROM, attempt.from)
    }
    // commit: il tentativo dell'installazione si scrive prima che Android chiuda l'app per installare (apply potrebbe non arrivare su disco).
    edit.commit()
  }

  private fun SharedPreferences.long(key: String): Long? = if (contains(key)) getLong(key, 0L) else null

  companion object {
    const val FILE: String = "mcg_updates"
    const val KEY_CHANNEL: String = "channel"
    const val KEY_LAST_CHECK: String = "last_check"
    private const val KEY_OFFER_CHANNEL = "offer_channel"
    private const val KEY_OFFER_VERSION = "offer_version"
    private const val KEY_OFFER_NOTES = "offer_notes"
    private const val KEY_OFFER_TAG = "offer_tag"
    private const val KEY_OFFER_ASSET = "offer_asset"
    private const val KEY_OFFER_URL = "offer_url"
    private const val KEY_OFFER_SIZE = "offer_size"
    private const val KEY_OFFER_SHA = "offer_sha256"
    private const val KEY_SNOOZED_UNTIL = "snoozed_until"
    private const val KEY_DECLINES = "declines"
    private const val KEY_DECLINES_FOR = "declines_for"
    private const val KEY_ATTEMPT_VERSION = "attempt_version"
    private const val KEY_ATTEMPT_STAGE = "attempt_stage"
    private const val KEY_ATTEMPT_METERED = "attempt_metered"
    private const val KEY_ATTEMPT_AT = "attempt_at"
    private const val KEY_ATTEMPT_FROM = "attempt_from"
    private val OFFER_KEYS =
      listOf(KEY_OFFER_CHANNEL, KEY_OFFER_VERSION, KEY_OFFER_NOTES, KEY_OFFER_TAG, KEY_OFFER_ASSET, KEY_OFFER_URL, KEY_OFFER_SIZE, KEY_OFFER_SHA)
    private val ATTEMPT_KEYS = listOf(KEY_ATTEMPT_VERSION, KEY_ATTEMPT_STAGE, KEY_ATTEMPT_METERED, KEY_ATTEMPT_AT, KEY_ATTEMPT_FROM)

    /** I codici salvati: stabili, indipendenti dal nome dell'enum dell'engine. Un valore sconosciuto vale Stabile, la scelta di serie. */
    fun codeOf(channel: UpdateChannel): String = when (channel) {
      UpdateChannel.STABLE -> "stable"
      UpdateChannel.BETA -> "beta"
    }

    fun channelOf(code: String?): UpdateChannel = if (code == "beta") UpdateChannel.BETA else UpdateChannel.STABLE
  }
}
