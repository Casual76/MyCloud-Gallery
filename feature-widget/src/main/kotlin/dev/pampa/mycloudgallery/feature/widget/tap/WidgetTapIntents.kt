package dev.pampa.mycloudgallery.feature.widget.tap

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.glance.action.Action
import androidx.glance.appwidget.action.actionStartActivity
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTapRequest
import dev.pampa.mycloudgallery.core.widget.contract.WidgetTapUri

// IL TOCCO SUL WIDGET, DALLA PARTE DEL WIDGET (Fase 10, P3, docs/fase10-progetto.md §2.2, D9, C17, C433). **L'unico posto** di feature-widget che costruisce l'azione verso
// `MainActivity` (Phase10ArchitectureTest: un `PendingIntent` si nomina solo qui, e nessun altro modulo nomina l'azione o lo schema del widget insieme a un `PendingIntent`).
//
// Ogni cella ha il suo intent: **esplicito** (il componente e' `MainActivity` del nostro pacchetto: nessun'altra app puo' intercettarlo), con l'**azione fissa**
// [WidgetTapUri.ACTION] e **solo due numeri** in un URI opaco (`mcg-widget:<id>/<cella>`: il widget e la cella, mai un id di media, un titolo o un nome), nessun extra, nessuna
// categoria, nessun flag. L'URI e' diverso per ogni cella, quindi i `PendingIntent` sono distinti (Android li confronta anche dall'URI: una richiesta distinta per cella,
// nessuna che ne sovrascriva un'altra). Il `PendingIntent` lo costruisce Glance (`actionStartActivity`): **immutabile** (`FLAG_IMMUTABLE`, il valore di base di Glance 1.1.1:
// verificato sul bytecode e dal test `WidgetTapIntentsTest`) e aggiornabile solo da noi con lo stesso intent. Chi riceve (MainActivity) riconosce l'azione, rilegge i due
// numeri con `WidgetTap.parse`, butta l'intent e controlla tutto sul registro locale (E-W7, E-W8): un intent inventato non apre piu' di una foto che il widget mostra gia'.

/** Dove porta un tocco: la `MainActivity` dell'app (il suo nome qui, perche' feature-widget non vede `:app`; un test di `:app` lo confronta con la classe vera). */
object WidgetTapTargets {
  /** La classe di `MainActivity`: il namespace dell'app, uguale in debug e in release (il suffisso `.debug` e' solo dell'id dell'applicazione). */
  const val MAIN_ACTIVITY_CLASS: String = "dev.pampa.mycloudgallery.MainActivity"
}

internal object WidgetTapIntents {

  /** L'intent del tocco sulla cella [cell] del widget [slot]: azione fissa, URI con i due numeri, componente esplicito. Niente altro. */
  fun cellIntent(context: Context, slot: WidgetSlot, cell: Int): Intent =
    Intent(WidgetTapUri.ACTION)
      .setComponent(mainActivity(context))
      .setData(Uri.parse(WidgetTapUri.format(WidgetTapRequest(slot.appWidgetId, cell))))

  /** L'intent di "Apri MyCloud Gallery": l'app come dall'icona (lo stesso che usa `MainActivity.launcherIntent`), esplicito. */
  fun openAppIntent(context: Context): Intent =
    Intent(Intent.ACTION_MAIN)
      .addCategory(Intent.CATEGORY_LAUNCHER)
      .setComponent(mainActivity(context))

  /** L'azione di Glance per la cella [cell] di [slot]. */
  fun cellAction(context: Context, slot: WidgetSlot, cell: Int): Action = actionStartActivity(cellIntent(context, slot, cell))

  /** L'azione di Glance per aprire l'app. */
  fun openAppAction(context: Context): Action = actionStartActivity(openAppIntent(context))

  private fun mainActivity(context: Context): ComponentName = ComponentName(context.packageName, WidgetTapTargets.MAIN_ACTIVITY_CLASS)
}
