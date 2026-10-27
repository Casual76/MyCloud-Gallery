package dev.pampa.mycloudgallery.core.faces.chaos

import dev.pampa.mycloudgallery.core.faces.contract.AckState
import dev.pampa.mycloudgallery.core.faces.erase.ErasePart
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath

// GLI OTTO INVARIANTI DEL §5.5 (docs/fase8-progetto.md), controllati dopo ogni morte, a fine passo e in una rilettura finale:
//  1. nessun lavoro perso o rifatto;                      5. mai fuori da `faces/`, nessun media toccato;
//  2. nessun dato fuori posto, nessuna pubblicazione senza lo stato fresco;      6. istantanee coerenti;
//  3. generazione: dopo `off|G` nessuna pubblicazione di `G`;      7. prenotazioni;
//  4. cancellazione completa (i sei invarianti del §2.8);      8. il lavoro arriva in fondo.
// In piu' due del banco: il codice non lancia per un guasto previsto (10) e la ripresa a vuoto non cambia niente (11).
// Il verificatore guarda fatti, non oggetti vivi: il NAS (oracolo, senza colpi), i registri del mondo, lo stato su disco dei dispositivi.

internal enum class Invariant(val number: Int, val label: String) {
  WORK(1, "nessun lavoro perso o rifatto"),
  PLACE(2, "nessun dato fuori posto"),
  GENERATION(3, "dopo off|G nessuna pubblicazione di G"),
  ERASED(4, "cancellazione completa"),
  OUTSIDE(5, "mai fuori da faces/, nessun media toccato"),
  SNAPSHOTS(6, "istantanee coerenti"),
  LEASES(7, "prenotazioni"),
  CONVERGENCE(8, "il lavoro arriva in fondo"),
  CONTRACT(10, "il codice non lancia per un guasto previsto"),
  IDLE(11, "la ripresa a vuoto non cambia niente"),
  BENCH(12, "difetto del banco"),
}

internal class Violation(val invariant: Invariant, val phase: String, val detail: String) {
  override fun toString(): String = "VIOLAZIONE [${invariant.number}. ${invariant.label}] $phase: $detail"
}

internal class ChaosVerifier(private val world: ChaosWorld) {
  val found = ArrayList<Violation>()
  private val seen = HashSet<String>()

  private var resavedSeen = 0
  private var leaseSeen = 0
  private var staleSeen = 0
  private var attemptsSeen = 0
  private var deletedSeen = 0

  /** Quando ogni generazione e' nata (la prima voce `on` datata che la nomina), in ora del NAS. */
  private val born = HashMap<FaceGeneration, Long>()

  fun add(invariant: Invariant, phase: String, detail: String) {
    if (seen.add("${invariant.number}|$detail")) found += Violation(invariant, phase, detail)
  }

  /**
   * I controlli che valgono sempre, anche a meta' (dopo una morte). [phase] dice quando: `dopo la morte #N`, `passo N`, `rilettura finale`.
   * I mondi del NAS finto, del simulatore e del NAS vero si guardano nello stesso modo (oracolo del NAS, nessun colpo).
   */
  fun check(phase: String) {
    work(phase)
    place(phase)
    generation(phase)
    erased(phase)
    outside(phase)
    leases(phase)
  }

  // 1. Nessun lavoro rifatto: un corpo proprio salvato non si salva una seconda volta nella stessa generazione.
  private fun work(phase: String) {
    while (resavedSeen < world.resaved.size) add(Invariant.WORK, phase, world.resaved[resavedSeen++])
  }

  // 2. Niente sotto `faces/` dell'area comune; ogni scrittura di un dispositivo sta sotto la sua cartella (o le prenotazioni) della generazione; mai senza stato fresco.
  private fun place(phase: String) {
    for (path in world.nas.commonPaths()) {
      if (path.isWithin(NasLayout.FACES_DIR)) add(Invariant.PLACE, phase, "un file dei volti nell'area comune")
    }
    val attempts = synchronized(world.logs.attempts) { world.logs.attempts.toList() }
    while (attemptsSeen < attempts.size) {
      val (who, path) = attempts[attemptsSeen++]
      val generation = generationOf(path)
      val mine = generation != null && (path.isWithin(NasLayout.facesDeviceDir(generation, who)) || path.isWithin(NasLayout.facesLeaseRoot(generation)))
      if (!mine) add(Invariant.PLACE, phase, "${nameOf(who)} ha scritto fuori dalla sua cartella dei volti")
    }
    while (staleSeen < world.staleWrites.size) add(Invariant.PLACE, phase, world.staleWrites[staleSeen++])
  }

  // 3. Dopo `off|G` visto, nessuna scrittura sotto `faces/<G>/`; mentre un dispositivo non puo' pubblicare, nessuna scrittura.
  private fun generation(phase: String) {
    for ((key, count) in world.offSeen.toMap()) {
      val now = world.attemptsUnder(key.first, key.second)
      if (now > count) {
        val device = world.devices.firstOrNull { it.id == key.first }
        val state = device?.switch?.currentState()?.javaClass?.simpleName
        add(Invariant.GENERATION, phase, "${nameOf(key.first)} ha scritto sotto faces/<G>/ dopo aver visto 'non piu' G' ($count -> $now; stato $state; ${world.writesAfter(key.first, key.second, count)})")
      }
    }
    for ((who, count) in world.blockedAt.toMap()) {
      val now = world.attemptsUnderFaces(who)
      if (now > count) add(Invariant.GENERATION, phase, "${nameOf(who)} ha scritto sotto faces/ mentre Persone non era accesa qui ($count -> $now)")
    }
  }

  // 4. I sei invarianti di §2.8 sul dispositivo e sul NAS.
  private fun erased(phase: String) {
    for (device in world.devices) {
      val setting = device.personal.dated?.let { FacesSettingCodec.parse(it.value) }
      if (setting is FacesSetting.On) born.merge(setting.generation, device.personal.dated!!.atNas, ::minOf)
    }
    for (device in world.devices) {
      val journal = device.journal.stored
      if (journal != null && journal.local) {
        for (g in journal.erased) {
          if (device.local.has(g)) add(Invariant.ERASED, phase, "${device.name} ha ancora dati di una generazione cancellata dopo LOCAL_DONE")
          val key = device.local.log.indexOf("key:${g.base32}")
          val files = device.local.log.indexOf("files:${g.base32}")
          if (files >= 0 && key !in 0 until files) add(Invariant.ERASED, phase, "${device.name} ha tolto i file prima della chiave")
          val gone = world.names[g].orEmpty()
          val left = device.history.entries.filter { e -> gone.any { e.contains(it, ignoreCase = true) } }
          if (journal.history == ErasePart.DONE && left.isNotEmpty()) add(Invariant.ERASED, phase, "nomi nella cronologia di ${device.name} dopo la cancellazione")
        }
      }
      // Anche chi cancella dal gancio di D30 toglie dalla cronologia i nomi che aveva dato.
      for ((key, given) in world.namedBy) {
        if (key.first != device.id || device.local.has(key.second)) continue
        val left = device.history.entries.filter { e -> given.any { e.contains(it, ignoreCase = true) } }
        if (left.isNotEmpty()) {
          add(
            Invariant.ERASED, phase,
            "nomi nella cronologia di ${device.name} dopo la cancellazione dei suoi dati (stato ${device.switch.currentState().javaClass.simpleName}, diario ${journal?.step?.code}, " +
              "storia locale ${device.localStore.local.value}, ${left.size} voci su ${device.history.entries.size}, nomi dati ${given.size})",
          )
        }
      }
      // Nessuna testa personale dice "non piu' G" (una voce esplicita non piu' vecchia della nascita di G) mentre quel dispositivo ha ancora dati di G.
      val entry = world.heads.of(device.id)?.entry
      if (entry != null) {
        val head = FacesSettingCodec.parse(entry.value)
        for ((g, at) in born) {
          val saysNotG = when (head) {
            is FacesSetting.Off -> entry.atNas >= at
            is FacesSetting.On -> head.generation != g && entry.atNas >= at
            else -> false
          }
          if (saysNotG && device.local.has(g)) add(Invariant.ERASED, phase, "la testa di ${device.name} dice 'non piu' G' con i dati di G ancora li'")
        }
      }
      // Un dispositivo che la pagina dice "ha cancellato" non ha davvero piu' niente.
      if (journal != null) {
        for (ack in device.eraser.status.value?.devices.orEmpty()) {
          if (ack.state != AckState.CONFIRMED) continue
          val other = world.devices.firstOrNull { it.id == ack.deviceId } ?: continue
          for (g in journal.erased) {
            if (other.local.has(g)) add(Invariant.ERASED, phase, "${device.name} dice che ${other.name} ha cancellato, ma ha ancora i dati")
          }
        }
      }
    }
  }

  // 5. Mai un file tolto fuori da `faces/`, mai un media o un file dell'app toccato.
  private fun outside(phase: String) {
    val deleted = synchronized(world.logs.deleted) { world.logs.deleted.toList() }
    while (deletedSeen < deleted.size) {
      val (who, path) = deleted[deletedSeen++]
      if (!(path.isWithin(NasLayout.FACES_DIR) && path != NasLayout.FACES_DIR)) add(Invariant.OUTSIDE, phase, "${nameOf(who)} ha tolto un file fuori da faces/")
    }
    for ((path, sha) in world.untouchable) {
      val bytes = world.nas.bytes(path)
      if (bytes == null) add(Invariant.OUTSIDE, phase, "un media o un file dell'app e' sparito")
      else if (ChaosWorld.sha256(bytes) != sha) add(Invariant.OUTSIDE, phase, "un media o un file dell'app e' cambiato")
    }
  }

  // 7. Prenotazioni: mai la stessa unita' a due dispositivi insieme.
  private fun leases(phase: String) {
    while (leaseSeen < world.leaseViolations.size) add(Invariant.LEASES, phase, world.leaseViolations[leaseSeen++])
  }

  /** A cancellazione finita (diario `DONE`): nessun file sotto `faces/<G>/` delle generazioni cancellate (invariante 4); altrimenti il motivo dichiarato (8). */
  fun erasedAtEnd(phase: String) {
    for (device in world.devices) {
      val journal = device.journal.stored ?: continue
      if (journal.done) {
        for (g in journal.erased) {
          val left = world.nas.paths().filter { it.isWithin(NasLayout.facesGenerationDir(g)) }
          if (left.isNotEmpty()) add(Invariant.ERASED, phase, "${left.size} file rimasti sotto faces/<G>/ a cancellazione finita (${device.name})")
        }
      } else if (device.eraser.status.value?.waiting == null) {
        add(Invariant.CONVERGENCE, phase, "la cancellazione di ${device.name} non e' finita e non dice perche'")
      }
    }
  }

  private fun generationOf(path: SharePath): FaceGeneration? =
    if (path.isWithin(NasLayout.FACES_DIR) && path.components.size > 2) FaceGeneration.parse(path.components[2]) else null

  private fun nameOf(deviceId: String): String = world.devices.firstOrNull { it.id == deviceId }?.name ?: "un dispositivo"
}
