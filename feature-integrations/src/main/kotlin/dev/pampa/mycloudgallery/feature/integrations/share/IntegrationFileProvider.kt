package dev.pampa.mycloudgallery.feature.integrations.share

import androidx.core.content.FileProvider

/**
 * Il `FileProvider` delle integrazioni (Fase 5B, D25, D26, C17; docs/fase5-progetto.md §2.7): serve solo `cacheDir/share/` e `cacheDir/edit/`
 * (`res/xml/integration_file_paths.xml`), non e' esportato e ogni permesso lo da' l'app uno per uno (`FLAG_GRANT_READ_URI_PERMISSION` e `ClipData`
 * sempre, Android 17 e 18) e lo revoca dopo l'uso. Una sottoclasse vuota di quella di androidx.core: ha un nome nostro, che il manifest, la policy
 * di release e i test possono nominare senza confonderlo con un altro `FileProvider` del grafo.
 *
 * P11 la usa per le copie "Modifica con…" (lettura e scrittura) e per le condivisioni senza posizione (sola lettura).
 */
class IntegrationFileProvider : FileProvider()
