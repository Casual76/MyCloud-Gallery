package dev.pampa.mycloudgallery.core.models.chaos

import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelLimits
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.testing.sha256Of
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import java.io.File

// Il VERIFICATORE: gli invarianti del §5.5 che riguardano lo scaricamento, letti dal disco, dal NAS e dai registri del mondo.
//  1  nessun modello non verificato caricato: ogni `open` che consegna qualcosa consegna un file con dimensione e SHA-256 del catalogo,
//     insieme al suo descrittore (`VerifiedModel.descriptor`: quello su cui la verifica e' stata fatta);
//  2  nessun parziale promosso: in `models/` solo file con dimensione e impronta del catalogo (e le cartelle di servizio);
//  3  ripresa vera: ogni richiesta (HTTPS o NAS) riparte dal confine di blocco sotto il parziale buono, e in tutto il giro i byte salvati
//     superano la dimensione del modello al piu' di un blocco per interruzione (piu' una dimensione per ogni ripartenza da zero
//     imposta dal server, da un'impronta sbagliata o da un file manomesso che si riscarica);
//  6  niente scritto fuori posto: sul NAS, oltre ai file messi dal banco, solo `devices/<io>/models/<sha256>` interi;
//  8  il lavoro arriva in fondo: a rete ripristinata il modello e' pronto, senza errori permanenti;
//  9  nessun temporaneo orfano: a lavoro finito niente parziali, validatori, temporanei del NAS.
// Ogni controllo restituisce violazioni (mai un'eccezione), cosi' un giro ne raccoglie tante.

class ModelChaosVerifier(private val world: ModelChaosWorld) {
  private var reported = 0

  private fun v(inv: ModelInvariant, at: CheckPoint, detail: String) = ModelViolation(inv, at, detail)

  /** Invarianti 2 e 3 (le richieste fin qui): lo si chiama dopo ogni morte e a fine passo. */
  fun cheap(at: CheckPoint): List<ModelViolation> {
    world.stats.checks++
    val out = ArrayList<ModelViolation>()
    out += directory(at)
    val seen = world.log.violations
    while (reported < seen.size) out += v(ModelInvariant.TRUE_RESUME, at, seen[reported++])
    return out
  }

  /** Invariante 2 e le parti dell'invariante 9 che valgono sempre: nessun file estraneo in `models/`, nessun parziale piu' lungo del modello. */
  private fun directory(at: CheckPoint): List<ModelViolation> {
    val out = ArrayList<ModelViolation>()
    val spec = world.spec
    world.dir.listFiles().orEmpty().forEach { entry ->
      if (entry.isDirectory) {
        if (entry.name !in SERVICE_DIRS) out += v(ModelInvariant.NO_PARTIAL, at, "cartella estranea in models/: ${entry.name}")
      } else if (entry.name != spec.sha256.hex) {
        out += v(ModelInvariant.NO_PARTIAL, at, "file estraneo in models/: ${entry.name}")
      } else if (entry.length() != spec.sizeBytes) {
        out += v(ModelInvariant.NO_PARTIAL, at, "in models/ un file lungo ${entry.length()} invece di ${spec.sizeBytes}")
      } else if (sha256Of(entry) != spec.sha256) {
        out += v(ModelInvariant.NO_PARTIAL, at, "in models/ un file della lunghezza giusta con un'impronta diversa")
      }
    }
    val partial = world.partialFile
    if (partial.isFile && partial.length() > spec.sizeBytes) out += v(ModelInvariant.NO_PARTIAL, at, "parziale piu' lungo del modello")
    return out
  }

  /**
   * Invariante 1: un caricamento ([ModelFiles.open]) consegna solo un file verificato. Il file consegnato si rilegge e deve avere
   * dimensione e impronta del catalogo; senza descrittore la verifica non e' stata fatta su quello che si carica.
   */
  suspend fun load(files: ModelFiles, at: CheckPoint): List<ModelViolation> {
    world.stats.loads++
    val model = files.open(world.spec.id) ?: return emptyList()
    return model.use {
      val out = ArrayList<ModelViolation>()
      if (it.descriptor == null || !it.descriptor!!.valid()) {
        out += v(ModelInvariant.VERIFIED_LOAD, at, "caricato senza un descrittore verificato")
      }
      if (!it.file.isFile || it.file.length() != world.spec.sizeBytes || sha256Of(it.file) != world.spec.sha256) {
        out += v(ModelInvariant.VERIFIED_LOAD, at, "caricato un file che non ha dimensione e impronta del catalogo")
      }
      out
    }
  }

  /** A fine giro, con la rete sana: l'invariante 8, il 9, il 6 sul NAS e il tetto dei byte riscaricati (3). */
  suspend fun end(at: CheckPoint, stateOf: () -> ModelState?): List<ModelViolation> {
    val out = ArrayList<ModelViolation>()
    out += cheap(at)
    val spec = world.spec
    val state = stateOf()
    if (state !is ModelState.Ready) out += v(ModelInvariant.REACHES_END, at, "a rete sana il modello non e' pronto: stato $state")
    if (!world.finalFile.isFile) out += v(ModelInvariant.REACHES_END, at, "a rete sana models/<sha> non c'e'")
    // 9: niente di rimasto a lavoro finito.
    val leftovers = File(world.dir, "partial").listFiles().orEmpty()
    if (leftovers.isNotEmpty()) out += v(ModelInvariant.NO_ORPHANS, at, "${leftovers.size} file in partial/ a lavoro finito")
    File(world.dir, "meta").listFiles().orEmpty().forEach {
      if (it.name != spec.sha256.hex && it.name != "strikes") out += v(ModelInvariant.NO_ORPHANS, at, "nota di provenienza estranea in meta/")
    }
    out += nas(at)
    out += resumeBound(at)
    return out
  }

  /** Invariante 6 (e il 9 sul NAS): oltre ai file del banco solo la copia propria, intera, e nessun temporaneo. */
  private suspend fun nas(at: CheckPoint): List<ModelViolation> {
    val out = ArrayList<ModelViolation>()
    val ownPath = NasLayout.modelCopy(world.device, world.spec.sha256.hex)
    for ((path, size) in world.nas.listAll()) {
      if (path in world.nas.external) continue
      when {
        path.contains("/tmp/") -> out += v(ModelInvariant.NO_ORPHANS, at, "temporaneo sul NAS in ${NasOwn.label(path)}")
        path != ownPath.value -> out += v(ModelInvariant.OUT_OF_PLACE, at, "scritto fuori da devices/<io>/models/: ${NasOwn.label(path)}")
        size != world.spec.sizeBytes -> out += v(ModelInvariant.OUT_OF_PLACE, at, "copia sul NAS lunga $size invece di ${world.spec.sizeBytes}")
        else -> world.nas.contentOf(ownPath)?.let {
          if (Sha256.of(it) != world.spec.sha256) out += v(ModelInvariant.OUT_OF_PLACE, at, "copia sul NAS con un'impronta diversa")
        }
      }
    }
    return out
  }

  /** Invariante 3, il tetto: un'interruzione costa al piu' un blocco, una ripartenza da zero costa una dimensione. */
  private fun resumeBound(at: CheckPoint): List<ModelViolation> {
    val s = world.stats
    val restarts = s.serverRestarts + world.counters.mismatch + s.tampered
    val allowed = world.spec.sizeBytes * (1 + restarts) + ModelLimits.BLOCK_BYTES.toLong() * world.interruptions()
    return if (s.recordedBytes > allowed) {
      listOf(v(ModelInvariant.TRUE_RESUME, at, "byte salvati ${s.recordedBytes} oltre il tetto $allowed (${world.interruptions()} interruzioni, $restarts ripartenze)"))
    } else {
      emptyList()
    }
  }

  private companion object {
    val SERVICE_DIRS = setOf("partial", "meta", "cache")
  }
}

/** Come si scrive un percorso nei rapporti. */
internal object NasOwn {
  /** La cartella senza il nome del file: i nomi di file non finiscono nei rapporti. */
  fun label(path: String): String = path.substringBeforeLast('/', "").let { if (it.isEmpty()) "(radice)" else it }
}
