package dev.pampa.mycloudgallery.core.memories.pick

import dev.pampa.mycloudgallery.core.memories.contract.PhotoPicker
import dev.pampa.mycloudgallery.core.memories.contract.PickerContract

/** La suite di contratto della scelta delle foto sulla scelta vera di `pick/` (P7, §5.2): le stesse prove del riferimento. */
class MemoryPhotoPickerContractTest : PickerContract() {
  override fun picker(): PhotoPicker = MemoryPhotoPicker()
}
