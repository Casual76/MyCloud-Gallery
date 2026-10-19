package dev.pampa.mycloudgallery.root

import android.content.Context
import android.content.Intent

/**
 * Riavvia il processo dell'app (verifica funzionale, ON-11). Dopo un azzeramento la galleria e il NAS di questo processo
 * sono fermi per sempre (`GalleryGraph.shutdown`, revisione R7: meglio fermi che vivi su file che non esistono piu'), e
 * `GalleryShutdownTest` lo fissa come voluto. "Configura di nuovo" riparte quindi da un processo nuovo, che costruisce
 * tutti i grafi da capo: senza, la Libreria restava vuota e preferito e nascondi davano errore finche' Android non chiudeva
 * l'app.
 */
fun interface ProcessRestart {
  fun restart()
}

/** In produzione: riapre l'activity d'ingresso in un task nuovo e chiude questo processo. */
class AndroidProcessRestart(private val context: Context) : ProcessRestart {
  override fun restart() {
    val entry = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component ?: return
    context.startActivity(Intent.makeRestartActivityTask(entry))
    Runtime.getRuntime().exit(0)
  }
}
