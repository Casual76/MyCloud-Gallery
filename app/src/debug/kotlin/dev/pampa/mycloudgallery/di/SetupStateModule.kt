package dev.pampa.mycloudgallery.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.family.contract.SetupState
import dev.pampa.mycloudgallery.core.family.testing.FakeFamilySetupRepository
import javax.inject.Singleton

/**
 * SOLO debug: lo stato del dispositivo e' un fake che il pannello "Sviluppo" (Impostazioni) cambia, cosi'
 * sul telefono si percorrono tutte le destinazioni dello scaffold (onboarding, attesa, galleria, azzerato)
 * prima che P4 esista. Parte dalla galleria. La versione release di questo file lega il null-object.
 *
 * Quando P9 lega lo stato vero, questo file e il suo gemello di release si riducono a una riga sola in main.
 */
@Module
@InstallIn(SingletonComponent::class)
object SetupStateModule {

  @Provides
  @Singleton
  fun fakeSetupRepository(): FakeFamilySetupRepository = FakeFamilySetupRepository(SetupState.Ready(admin = true))

  @Provides
  fun familySetupRepository(fake: FakeFamilySetupRepository): FamilySetupRepository = fake
}
