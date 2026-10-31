package dev.pampa.mycloudgallery.core.widget.ledger

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.widget.contract.WidgetCell
import dev.pampa.mycloudgallery.core.widget.contract.WidgetMode
import dev.pampa.mycloudgallery.core.widget.contract.WidgetPlan
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSize
import dev.pampa.mycloudgallery.core.widget.contract.WidgetSlot
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking

/**
 * Il processo figlio di `FileWidgetLedgerKillTest`: riscrive di continuo il registro (un widget, epoca crescente, sette celle tutte della stessa
 * epoca) finche' il padre non lo uccide. Parte dall'epoca che trova nel file, come un processo che si riavvia. Solo per le prove.
 */
fun main(args: Array<String>) {
  val dir = File(args[0])
  runBlocking {
    val ledger = FileWidgetLedger(dir)
    val slot = WidgetSlot(1)
    var epoch = ledger.epochOf(slot)
    var announced = false
    while (true) {
      epoch++
      val cells = List(7) { WidgetCell(it, MediaId(UUID(epoch, it.toLong()).toString()), MediaKind.PHOTO, 20261031) }
      try {
        ledger.record(WidgetPlan(slot, WidgetSize.LARGE, WidgetMode.Photos, cells, epoch))
        if (!announced) {
          announced = true
          println("scritto")
          System.out.flush()
        }
      } catch (e: java.io.IOException) {
        // Il padre sta leggendo il file proprio ora (Windows non lascia rinominare sopra un file aperto): si riprova con l'epoca dopo.
      }
    }
  }
}
