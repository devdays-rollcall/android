package ru.pereklichka.domain.time

/**
 * Возраст записи или события в миллисекундах.
 *
 * [approximate] стоит, если телефон перезагружался после получения записи и возраст оценён
 * по настенным часам. Экран тогда пишет «примерно 2 мин назад».
 */
data class Age(val ms: Long, val approximate: Boolean)
