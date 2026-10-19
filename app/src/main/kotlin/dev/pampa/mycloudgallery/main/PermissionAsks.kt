package dev.pampa.mycloudgallery.main

import android.content.Context
import android.content.SharedPreferences

/**
 * I permessi che l'app ha gia' chiesto con il dialogo di sistema (GA-4). Serve a una cosa sola: capire se un permesso e' stato
 * **negato per sempre**. `shouldShowRequestPermissionRationale` dice falso sia a chi non ha mai chiesto sia a chi ha rifiutato due
 * volte: la differenza la fa il ricordo di aver chiesto. Prima il ricordo stava in `remember`, quindi si perdeva con una rotazione o
 * con un riavvio del processo, e il primo tocco su "Consenti" lanciava una richiesta che Android scarta in silenzio: sembrava che
 * il tasto non facesse niente. Ora sta su un file, in modo che un nuovo avvio sappia com'era andata.
 *
 * Il file contiene solo i nomi dei permessi gia' chiesti (`android.permission.*`): niente che identifichi persone o media.
 * [preferences] si apre alla prima richiesta, non nella composizione ([warm] la porta su un thread di lavoro).
 */
internal class PermissionAsks(private val opener: () -> SharedPreferences) {

  constructor(context: Context) : this({
    context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
  })

  private val preferences: SharedPreferences by lazy(opener)

  /** Apre il file adesso (da un thread di lavoro), cosi' il tocco dell'utente non lo legge dal thread principale. */
  fun warm() {
    preferences.all
  }

  fun asked(permission: String): Boolean = preferences.getBoolean(permission, false)

  /** Si e' appena aperto il dialogo di sistema per [permissions]. */
  fun markAsked(permissions: Collection<String>) {
    preferences.edit().apply { permissions.forEach { putBoolean(it, true) } }.apply()
  }

  /**
   * Un permesso concesso non e' piu' "negato": se l'utente lo toglie dalle Impostazioni, il dialogo di sistema torna ad aprirsi,
   * e il ricordo di una vecchia richiesta lo farebbe passare per negato per sempre.
   */
  fun forgetGranted(permissions: Collection<String>) {
    val known = permissions.filter { asked(it) }
    if (known.isNotEmpty()) preferences.edit().apply { known.forEach { remove(it) } }.apply()
  }

  companion object {
    const val FILE = "permission_asks"

    /**
     * Android non aprira' piu' il dialogo: manca almeno un permesso e, per ognuno di quelli che mancano, l'app l'aveva gia' chiesto e
     * il sistema non propone piu' di spiegare ([shouldExplain] falso). Con [shouldExplain] nullo (nessuna activity) non si sa: no.
     */
    fun deniedForGood(missing: List<String>, asked: (String) -> Boolean, shouldExplain: (String) -> Boolean?): Boolean =
      missing.isNotEmpty() && missing.all { asked(it) && shouldExplain(it) == false }
  }
}
