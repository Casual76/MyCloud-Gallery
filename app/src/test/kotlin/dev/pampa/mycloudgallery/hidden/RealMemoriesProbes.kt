package dev.pampa.mycloudgallery.hidden

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.Collections
import java.util.WeakHashMap

// Fase 9 (P9, D31, E-MEM5, E-H8, E-H10; 30/10): le SONDE della riga `MEMORIES` del registro dei Nascosti. Il motore vero dei ricordi (P6, con la scelta delle foto
// di P7) e le schermate vere (P9: sezione, pagina, "Tutti i ricordi", presentazione) girano sul catalogo vero del mondo casuale, **tre volte** (il mese prima, gli
// anniversari e un giorno di sole persone: `MemoriesDay`); dopo la pubblicazione il catalogo nasconde alcuni elementi (`RealMemoriesSurfaces`) e ogni superficie deve toglierli, dire i conti giusti
// e non mostrare mai un nascosto. Le quattro righe del registro (`memories-section`, `memories-page`, `memories-player`, `memories-people`) leggono lo stesso giro:
// si fa una volta per mondo (l'identita' dell'oggetto `BuiltWorld`).

internal object RealMemoriesProbes {

  /** Il giro di un mondo, ricordato finche' il mondo esiste: le quattro sonde lo condividono (il registro le chiama una dopo l'altra sullo stesso mondo). */
  private val cache: MutableMap<BuiltWorld, MemoriesReport> = Collections.synchronizedMap(WeakHashMap())

  /**
   * Il giro su una **copia privata** di [original] (stesso seme, stesso catalogo vero, un database suo: la prova scrive i nascosti dell'ultimo momento e le altre
   * superfici del registro non devono accorgersene, come per i viaggi), con le tre "oggi" e il filtro finale ([finalFilter], falso solo per la prova al contrario).
   * Quello che le superfici hanno mostrato torna con gli id del mondo di [original]. Il secondo giro vede nel catalogo i nascosti del primo: non sono mai fra gli
   * elementi del secondo (il motore li ha gia' tolti alla pubblicazione), quindi il suo oracolo non cambia.
   */
  suspend fun report(original: BuiltWorld, finalFilter: Boolean = true, remember: Boolean = true): MemoriesReport {
    if (remember) cache[original]?.let { return it }
    val context = ApplicationProvider.getApplicationContext<Context>()
    val own = HiddenWorldBuilder.build(context, original.spec)
    val merged = MemoriesReport(SurfaceReport(), SurfaceReport(), SurfaceReport(), SurfaceReport())
    try {
      for (day in MemoriesDay.entries) {
        RealMemoriesStack(own, day).use { stack ->
          val one = RealMemoriesSurfaces.evaluate(stack, finalFilter)
          merge(merged.section, one.section, own, original)
          merge(merged.page, one.page, own, original)
          merge(merged.player, one.player, own, original)
          merge(merged.people, one.people, own, original)
        }
      }
    } finally {
      own.close()
    }
    if (remember) cache[original] = merged
    return merged
  }

  /** Aggiunge [from] a [into], con gli id della copia privata [own] tradotti in quelli del mondo [original]. */
  private fun merge(into: SurfaceReport, from: SurfaceReport, own: BuiltWorld, original: BuiltWorld) {
    into.shown += from.shown.map { original.ids[own.indexOf(it)] }
    into.problems += from.problems
  }

  private fun probed(report: SurfaceReport, world: BuiltWorld) = Probed(shown = report.shown, within = world.liveShowable, problems = report.problems)

  /** La sezione Ricordi delle Raccolte: voci, conti, copertine, codici. */
  val section = SurfaceProbe { world -> probed(report(world).section, world) }

  /** La pagina di ogni ricordo e "Tutti i ricordi": elementi, copertina, conti. */
  val page = SurfaceProbe { world -> probed(report(world).page, world) }

  /** La presentazione di ogni ricordo (i primi tre): gli elementi, la copertina del titolo, quello che si precarica e la carta finale. */
  val player = SurfaceProbe { world -> probed(report(world).player, world) }

  /** I ricordi con le persone: nessun ricordo di una persona nascosta, nessun nascosto nei ricordi di una persona (E-H10). */
  val people = SurfaceProbe { world -> probed(report(world).people, world) }
}
