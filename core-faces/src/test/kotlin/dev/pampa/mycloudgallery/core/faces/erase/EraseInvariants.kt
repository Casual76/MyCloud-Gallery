package dev.pampa.mycloudgallery.core.faces.erase

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseReason
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout

/**
 * I sei invarianti del §2.8 (docs/fase8-progetto.md; il banco del §5.5 punto 4), controllati sul mondo dopo ogni passo di una storia:
 * 1. dopo `LOCAL_DONE` su un dispositivo, nessun dato e nessuna chiave delle generazioni cancellate su quel dispositivo, la chiave tolta prima dei
 *    file, nessun nome di quelle generazioni nella cronologia;
 * 2. nessuna testa personale dice "non piu' G" (una voce esplicita non piu' vecchia della nascita di G) mentre quel dispositivo ha ancora dati di G;
 * 3. dopo aver ricevuto la voce `off|G` (o una voce piu' nuova che non e' `on|G`), un dispositivo non scrive piu' niente sotto `faces/<G>/`;
 * 4. a cancellazione finita (diario `DONE`), nessun file sotto `faces/<G>/` delle generazioni cancellate ([final]);
 * 5. mai un file tolto fuori da `faces/`, mai un media o un altro file dell'app; una richiesta sul NAS solo per una generazione e un motivo che la
 *    regola di D31 ammette dal punto di vista di chi chiede;
 * 6. il lavoro arriva in fondo: `DONE`, o in attesa con un motivo dichiarato ([final]).
 */
internal class EraseInvariants(private val world: EraseWorld) {

  /** L'ora del NAS in cui ogni generazione e' nata (la prima voce `on` datata che la nomina). */
  private val born = HashMap<FaceGeneration, Long>()

  /** Per (dispositivo, generazione): quante scritture del dispositivo sotto `faces/<G>/` quando ha ricevuto la voce che la spegne. */
  private val writesAtOff = HashMap<Pair<String, FaceGeneration>, Int>()

  /** I nomi dati nelle persone di ogni generazione, per dispositivo (per la cronologia). */
  val names = HashMap<FaceGeneration, MutableSet<String>>()
  private val namedBy = HashMap<Pair<String, FaceGeneration>, MutableSet<String>>()

  fun named(generation: FaceGeneration, name: String, device: EraseDevice? = null) {
    names.getOrPut(generation) { HashSet() } += name
    if (device != null) namedBy.getOrPut(device.id to generation) { HashSet() } += name
  }

  fun check(label: String) {
    // Le nascite: dalle voci datate che i dispositivi hanno.
    for (device in world.devices) {
      val entry = device.personal.dated ?: continue
      val setting = FacesSettingCodec.parse(entry.value)
      if (setting is FacesSetting.On) born.merge(setting.generation, entry.atNas, ::minOf)
    }
    for (device in world.devices) {
      // 1.
      val journal = device.journal.stored
      if (journal != null && journal.local) {
        for (g in journal.erased) {
          assertWithMessage("$label: ${device.name} ha ancora dati di una generazione cancellata dopo LOCAL_DONE").that(device.local.has(g)).isFalse()
          val key = device.local.log.indexOf("key:${g.base32}")
          val files = device.local.log.indexOf("files:${g.base32}")
          if (files >= 0) assertWithMessage("$label: ${device.name} ha tolto i file prima della chiave").that(key in 0 until files).isTrue()
          val gone = names[g].orEmpty()
          val left = device.history.entries.filter { e -> gone.any { e.contains(it, ignoreCase = true) } }
          if (journal.history == ErasePart.DONE) assertWithMessage("$label: nomi nella cronologia di ${device.name}").that(left).isEmpty()
        }
      }
      // 1 bis: anche chi cancella dal gancio di D30 toglie dalla cronologia i nomi che aveva dato.
      for ((key, given) in namedBy) {
        if (key.first != device.id || device.local.has(key.second)) continue
        val left = device.history.entries.filter { e -> given.any { e.contains(it, ignoreCase = true) } }
        assertWithMessage("$label: nomi nella cronologia di ${device.name} dopo la cancellazione dei suoi dati").that(left).isEmpty()
      }
      // 2.
      val head = synchronized(world.heads) { world.heads[device.id] }
      val entry = head?.entry
      if (entry != null) {
        val setting = FacesSettingCodec.parse(entry.value)
        for ((g, at) in born) {
          val saysNotG = when (setting) {
            is FacesSetting.Off -> entry.atNas >= at
            is FacesSetting.On -> setting.generation != g && entry.atNas >= at
            else -> false
          }
          if (saysNotG) assertWithMessage("$label: la testa di ${device.name} dice 'non piu' G' con i dati di G ancora li'").that(device.local.has(g)).isFalse()
        }
      }
      // 3.
      val mine = device.personal.dated
      if (mine != null) {
        val setting = FacesSettingCodec.parse(mine.value)
        for ((g, at) in born) {
          val off = setting is FacesSetting.Off && mine.atNas >= at || setting is FacesSetting.On && setting.generation != g && mine.atNas >= at
          if (off) writesAtOff.putIfAbsent(device.id to g, writesUnder(device, g))
        }
      }
      for ((key, count) in writesAtOff) {
        if (key.first != device.id) continue
        assertWithMessage("$label: ${device.name} ha scritto sotto faces/<G>/ dopo aver ricevuto off").that(writesUnder(device, key.second)).isEqualTo(count)
      }
      // 10 (D30, la pagina): un dispositivo che la cancellazione dice "ha cancellato" non ha davvero piu' niente delle generazioni cancellate.
      val erasing = device.journal.stored
      if (erasing != null) {
        for (ack in device.eraser.status.value?.devices.orEmpty()) {
          if (ack.state != dev.pampa.mycloudgallery.core.faces.contract.AckState.CONFIRMED) continue
          val other = world.devices.firstOrNull { it.id == ack.deviceId } ?: continue
          for (g in erasing.erased) {
            assertWithMessage("$label: ${device.name} dice che ${other.name} ha cancellato, ma ha ancora i dati").that(other.local.has(g)).isFalse()
          }
        }
      }
      // 5. (le richieste)
      for ((g, target, reason) in device.ops.calls) {
        assertWithMessage("$label: ${device.name} ha chiesto la generazione con un motivo sbagliato").that(reason == NasEraseReason.DEVICE_REVOKED && target == null).isFalse()
        assertWithMessage("$label: ${device.name} ha chiesto la propria generazione come non corrente").that(target == null && reason == NasEraseReason.NOT_CURRENT && g == currentOf(device) && currentOf(device) != null).isFalse()
      }
    }
    // 5. (i file)
    for ((_, path) in world.deleted) {
      assertWithMessage("$label: tolto un file fuori da faces/").that(path.isWithin(NasLayout.FACES_DIR) && path != NasLayout.FACES_DIR).isTrue()
    }
    for (path in world.untouchable) assertWithMessage("$label: toccato un media o un file dell'app").that(world.share.bytes(path)).isNotNull()
  }

  /** 4 e 6: alla fine della storia. */
  fun final(label: String, eraser: EraseDevice) {
    check(label)
    val journal = eraser.journal.stored ?: return
    val status = eraser.eraser.status.value
    if (journal.done) {
      for (g in journal.erased) assertWithMessage("$label: file rimasti sotto faces/<G>/ a cancellazione finita").that(world.facesFiles(g)).isEmpty()
    } else {
      assertWithMessage("$label: la cancellazione non e' finita e non dice perche'").that(status?.waiting).isNotNull()
    }
  }

  private fun currentOf(device: EraseDevice): FaceGeneration? = (device.personal.current() as? FacesSetting.On)?.generation

  private fun writesUnder(device: EraseDevice, g: FaceGeneration): Int =
    world.written.count { it.first == device.id && it.second.isWithin(NasLayout.facesGenerationDir(g)) }
}
