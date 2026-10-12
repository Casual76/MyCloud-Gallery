package dev.pampa.mycloudgallery.core.rules

import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * L'orologio di produzione: l'istante e' quello di sistema e il fuso orario e' SEMPRE quello attuale del
 * dispositivo, riletto a ogni uso.
 *
 * `Clock.systemDefaultZone()` non basta: si porta dietro il fuso del momento in cui e' stato creato, e un
 * viaggio all'estero (E-P5) lascerebbe la fascia notte e il cambio mese dei contatori sull'ora di casa
 * finche' il processo non muore.
 */
object SystemClockFollowingZone : Clock() {
  override fun getZone(): ZoneId = ZoneId.systemDefault()

  override fun withZone(zone: ZoneId): Clock = system(zone)

  override fun instant(): Instant = Instant.now()
}
