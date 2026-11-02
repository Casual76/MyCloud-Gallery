package dev.pampa.mycloudgallery.feature.widget.host

import android.graphics.Bitmap
import dev.antigravity.fluidengine.foundation.EngineSettings
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.widget.contract.WidgetClock
import dev.pampa.mycloudgallery.core.widget.contract.WidgetLedger
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPeriod
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlanner
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPrefs

// LE PORTE DEL WIDGET VERSO L'APP (Fase 10, P3, docs/fase10-progetto.md §1.4 "WidgetGraph", §2.1). `feature-widget` non conosce il grafo di `:app`: chiede al
// `WidgetHost` le **porte vere** che `:app` monta (P13: pianificatore sopra `RotatingWidgetFeed`, registro su file, miniature di core-imaging, blocco, preferenze,
// orologio). Finche' `:app` non ne mette (un processo freddo, o P13 non ancora fatto) il widget **non disegna niente di nuovo**: resta quello che il launcher ha.

/**
 * Le miniature **locali** da decodificare per il widget (D7, C431): la miniatura gia' sul telefono di [media] (replica di un media solo-NAS o miniatura del telefono),
 * al piu' [longSidePx] pixel sul lato lungo. **Mai il NAS, mai la rete.** Null se non c'e' (il pianificatore le ha gia' scelte con `WidgetThumbs.local`: qui e' un
 * caso raro, come una miniatura tolta nel frattempo) o se la decodifica non riesce. La bitmap e' del chiamante: il widget non la tiene oltre il disegno.
 */
fun interface WidgetThumbnails {
  suspend fun load(media: MediaId, longSidePx: Int): Bitmap?
}

/**
 * Il nome di un luogo per il titolo del ricordo ("Un anno fa · Oslo"), da `PlaceNames` offline (core-places): mai la rete. Null se l'id non si conosce: il titolo
 * dice solo gli anni. Mai un nome di persona (il piano non ne porta: D4).
 */
fun interface WidgetPlaceNames {
  fun name(placeId: Int): String?
}

/**
 * Le impostazioni d'aspetto dell'app (tema, accento, modalita' scura) con cui il kit del widget risolve la palette (E-W10): le **stesse** che prende `MyCloudGalleryTheme`.
 * `:app` le lega al flusso delle impostazioni d'aspetto (`appearanceChanged()` ridisegna quando cambiano); senza, il widget segue il tema del sistema.
 */
fun interface WidgetAppearance {
  fun settings(): EngineSettings
}

/**
 * Tutto cio' che serve a un giro del widget (§2.1), montato da `:app` (P13, `WidgetGraph`). Ogni porta fallisce **chiusa** (i null-object di `core-widget`: nessuna
 * foto, blocco acceso). [planner] fa gia' il filtro finale (`visibleOrThrow` e `hiddenAmongOrThrow`) come ultimo passo.
 */
interface WidgetServices {
  val planner: WidgetPlanner
  val ledger: WidgetLedger
  val thumbnails: WidgetThumbnails
  val prefs: WidgetPrefs
  val clock: WidgetClock
  val places: WidgetPlaceNames
  val appearance: WidgetAppearance
}

/**
 * Chi monta le porte di [WidgetServices] e le consegna **entro un tempo**. In un processo freddo (WorkManager, il sistema dei widget, un tocco) il grafo di `:app`
 * non c'e' ancora: [await] ne monta **solo istantanea, maschera dei nascosti e scaffale** e aspetta, al piu' `budgetMs`, che siano pronti (§2.1, passo 2;
 * `WidgetRules.COLD_START_BUDGET_MS`): la lezione di NT-1 della Fase 9, il lavoro non si fida di un grafo montato da altri. Null = non pronti nel tempo (E-W4): il
 * widget lascia il contenuto che ha (era verificato) e riprova al giro dopo. Mai un contenuto non verificato.
 */
interface WidgetServicesProvider {

  /**
   * L'orologio, **subito** e senza montare niente: serve al lavoro periodico per le ore di quiete (D6: dentro le 23-7 esce subito, senza svegliare il grafo) e per il
   * calcolo del prossimo giro. E' lo stesso che finisce in [WidgetServices.clock].
   */
  val clock: WidgetClock

  /**
   * La frequenza scelta in Impostazioni -> Widget (WG-2), **senza montare le porte**: il ricevitore la usa quando programma il lavoro periodico (un widget che nasce dopo
   * che l'ultimo e' stato tolto, o una Home che si ridisegna): senza, il lavoro ripartirebbe sempre dalla frequenza di serie. Di serie [WidgetPeriod.DEFAULT]. Puo' aprire
   * un file di preferenze: non va chiamata dal thread principale.
   */
  val period: WidgetPeriod get() = WidgetPeriod.DEFAULT

  suspend fun await(budgetMs: Long): WidgetServices?
}
