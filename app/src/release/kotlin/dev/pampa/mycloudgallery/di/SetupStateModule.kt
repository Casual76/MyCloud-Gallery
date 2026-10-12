package dev.pampa.mycloudgallery.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.family.noop.NotConfiguredSetupRepository

/**
 * Release: lo stato del dispositivo e' il null-object (mai configurato) finche' P9 non lega quello vero di
 * P4. Nessun fake: i package `testing` non devono arrivare all'APK di release (tools/check.ps1).
 */
@Module
@InstallIn(SingletonComponent::class)
object SetupStateModule {
  @Provides
  fun familySetupRepository(): FamilySetupRepository = NotConfiguredSetupRepository
}
