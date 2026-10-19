package dev.pampa.mycloudgallery.core.backup.settings

import dev.pampa.mycloudgallery.core.backup.contract.DeviceFolder
import dev.pampa.mycloudgallery.core.backup.plan.BackupPaths
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.registry.LookAlike

// LA CARTELLA DEL DISPOSITIVO (Fase 5A, P4; docs/fase5-progetto.md D7, E-B6, E-B7, E-B16).
//
//     Backup/<cartella del dispositivo>/...
//
// Si sceglie UNA volta, al primo backup, dal nome del dispositivo ripulito per `SharePath` ("S25 di Mario"), e si salva nelle
// impostazioni del backup. Il nome del dispositivo non si guarda piu': rinominarlo non sposta niente (E-B7), e l'id del dispositivo
// (che cambia a ogni reinstallazione) non c'entra. Funzioni pure: l'elenco di `Backup/` lo legge chi chiama (la pagina del primo backup).

/** Cosa fare quando il nome scelto c'e' gia' in `Backup/` (E-B6, E-B16). */
enum class FolderChoice {
  /** "Continua li'": si usa la cartella che c'e'; la deduplica collega cio' che e' gia' presente, senza ricaricare. */
  CONTINUE_THERE,

  /** "Nuova cartella": si usa [FolderDecision.AskExisting.newFolder]. */
  NEW_FOLDER,
}

/** La decisione sul nome della cartella del dispositivo. */
sealed interface FolderDecision {

  /** Nessuna cartella con quel nome in `Backup/`: si crea e si usa [folder]. */
  data class Create(val folder: DeviceFolder) : FolderDecision

  /**
   * Il nome c'e' gia' (un altro dispositivo dello stesso account, E-B6; o questo stesso dopo una reinstallazione o su un telefono nuovo,
   * E-B16): si chiede "Continua li'" ([existing], con la grafia che c'e' sul NAS) o "Nuova cartella" ([newFolder], il primo "nome (2)"
   * libero). [suggested] e' la scelta da preselezionare: continuare, a meno che un altro dispositivo vivo la usi.
   */
  data class AskExisting(val existing: DeviceFolder, val newFolder: DeviceFolder, val suggested: FolderChoice) : FolderDecision
}

object DeviceFolderPolicy {

  /** Il nome di un dispositivo senza un nome utilizzabile. */
  const val FALLBACK_NAME: String = "Dispositivo"

  /** Il nome del dispositivo ripulito per diventare **un** componente valido di `SharePath` (D7). Mai vuoto. */
  fun folderFor(deviceName: String): DeviceFolder {
    val collapsed = deviceName.trim().replace(WHITESPACE, " ")
    return DeviceFolder(BackupPaths.component(collapsed, FALLBACK_NAME))
  }

  /**
   * Sceglie la cartella.
   *
   * @param existingFolders i nomi delle cartelle che ci sono in `Backup/` adesso (come le elenca il NAS). SMB non distingue le maiuscole:
   *   "S25 di mario" e "S25 di Mario" sono la stessa cartella ([LookAlike.key]).
   * @param usedByOtherDevice un altro dispositivo vivo dello stesso account (registro della famiglia) usa gia' questo nome: allora si
   *   preseleziona "Nuova cartella" (E-B6); altrimenti il nome e' di un'installazione precedente di questo dispositivo o di un
   *   telefono sostituito e si preseleziona "Continua li'" (E-B16).
   */
  fun decide(deviceName: String, existingFolders: Collection<String>, usedByOtherDevice: Boolean = false): FolderDecision {
    val wanted = folderFor(deviceName)
    val taken = existingFolders.mapTo(HashSet()) { LookAlike.key(it) } + RESERVED_KEYS
    val key = LookAlike.key(wanted.name)
    // Il nome della cartella delle copie del guardiano (D9) non e' un dispositivo: si cambia sempre.
    if (key in RESERVED_KEYS) return FolderDecision.Create(freeName(wanted.name, taken))
    val there = existingFolders.firstOrNull { LookAlike.key(it) == key } ?: return FolderDecision.Create(wanted)
    // Una cartella che `SharePath` non accetta non si puo' continuare: si crea una nuova (con un nome libero).
    val existing = runCatching { DeviceFolder(there) }.getOrNull() ?: return FolderDecision.Create(freeName(wanted.name, taken))
    val suggested = if (usedByOtherDevice) FolderChoice.NEW_FOLDER else FolderChoice.CONTINUE_THERE
    return FolderDecision.AskExisting(existing, freeName(wanted.name, taken), suggested)
  }

  /** Il primo "nome (2)", "nome (3)"... che non c'e' fra i [taken] ([LookAlike.key]); il nome base si accorcia se serve. */
  fun freeName(base: String, taken: Collection<String>): DeviceFolder {
    val takenKeys = taken.toSet()
    var n = 2
    while (true) {
      val suffix = " ($n)"
      val candidate = shorten(base, TrustLimits.MAX_COMPONENT_UTF8_BYTES - suffix.toByteArray(Charsets.UTF_8).size) + suffix
      if (LookAlike.key(candidate) !in takenKeys) return DeviceFolder(candidate)
      n++
    }
  }

  /** Il nome accorciato a [maxBytes] byte a un confine di carattere: la stessa regola di `BackupPaths.alternateName` (R1, 5A). */
  private fun shorten(base: String, maxBytes: Int): String = BackupPaths.cutToBytes(base, maxBytes).trimEnd()

  private val WHITESPACE = Regex("\\s+")
  private val RESERVED_KEYS: Set<String> = setOf(LookAlike.key(BackupPaths.COMMON_AREA_FOLDER))
}
