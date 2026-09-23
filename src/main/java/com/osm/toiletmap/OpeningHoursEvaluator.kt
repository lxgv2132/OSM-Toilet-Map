package com.osm.toiletmap

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

object OpeningHoursEvaluator {

    private val DAYS_MAP = mapOf(
        "mo" to DayOfWeek.MONDAY,
        "tu" to DayOfWeek.TUESDAY,
        "we" to DayOfWeek.WEDNESDAY,
        "th" to DayOfWeek.THURSDAY,
        "fr" to DayOfWeek.FRIDAY,
        "sa" to DayOfWeek.SATURDAY,
        "su" to DayOfWeek.SUNDAY
    )

    data class EvaluationResult(
        val status: ToiletStatus,
        val remainingOpenSeconds: Long? = null
    )

    fun evaluate(openingHours: String?, now: LocalDateTime = LocalDateTime.now()): EvaluationResult {
        if (openingHours.isNullOrBlank()) {
            return EvaluationResult(ToiletStatus.UNKNOWN, null)
        }

        val raw = openingHours.trim().lowercase(Locale.ROOT)

        if (raw == "24/7" || raw == "yes" || raw == "always" || raw == "open") {
            return EvaluationResult(ToiletStatus.OPEN, Long.MAX_VALUE)
        }

        if (raw == "no" || raw == "closed" || raw == "off") {
            return EvaluationResult(ToiletStatus.CLOSED, 0L)
        }

        // Try parsing multiple rules separated by semicolon or comma
        val rules = raw.split(";").map { it.trim() }
        var hasApplicableRule = false
        var isOpen = false
        var remainingSeconds: Long? = null

        val currentDay = now.dayOfWeek
        val currentTime = now.toLocalTime()

        for (rule in rules) {
            val ruleResult = evaluateSingleRule(rule, currentDay, currentTime) ?: continue
            hasApplicableRule = true
            if (ruleResult.status == ToiletStatus.OPEN) {
                isOpen = true
                remainingSeconds = ruleResult.remainingOpenSeconds
                break
            }
        }

        return when {
            isOpen -> EvaluationResult(ToiletStatus.OPEN, remainingSeconds)
            hasApplicableRule -> EvaluationResult(ToiletStatus.CLOSED, 0L)
            else -> {
                // If it's just a time like "08:00-20:00" without day prefix
                val timeRangeResult = parseTimeRange(raw, currentTime)
                if (timeRangeResult != null) {
                    timeRangeResult
                } else {
                    EvaluationResult(ToiletStatus.UNKNOWN, null)
                }
            }
        }
    }

    private fun evaluateSingleRule(rule: String, currentDay: DayOfWeek, currentTime: LocalTime): EvaluationResult? {
        val parts = rule.split(Regex("\\s+"), limit = 2)
        if (parts.isEmpty()) return null

        val firstPart = parts[0]
        val days = parseDays(firstPart)

        return if (days.isNotEmpty()) {
            if (days.contains(currentDay)) {
                if (parts.size > 1) {
                    val timePart = parts[1]
                    if (timePart.contains("off") || timePart.contains("closed")) {
                        EvaluationResult(ToiletStatus.CLOSED, 0L)
                    } else {
                        parseTimeRange(timePart, currentTime)
                    }
                } else {
                    // Open whole day for these days
                    val secondsToMidnight = (24 * 3600) - currentTime.toSecondOfDay()
                    EvaluationResult(ToiletStatus.OPEN, secondsToMidnight.toLong())
                }
            } else {
                null // Not applicable for today
            }
        } else {
            // Might be a pure time range without day
            parseTimeRange(rule, currentTime)
        }
    }

    private fun parseDays(str: String): Set<DayOfWeek> {
        val result = mutableSetOf<DayOfWeek>()
        val dayTokens = str.split(",")
        val dayList = listOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY
        )

        for (token in dayTokens) {
            if (token.contains("-")) {
                val rangeParts = token.split("-")
                if (rangeParts.size == 2) {
                    val startDay = DAYS_MAP[rangeParts[0].trim()]
                    val endDay = DAYS_MAP[rangeParts[1].trim()]
                    if (startDay != null && endDay != null) {
                        val startIndex = dayList.indexOf(startDay)
                        val endIndex = dayList.indexOf(endDay)
                        if (startIndex <= endIndex) {
                            for (i in startIndex..endIndex) {
                                result.add(dayList[i])
                            }
                        } else {
                            // Wraps around e.g. Fr-Mo
                            for (i in startIndex until dayList.size) result.add(dayList[i])
                            for (i in 0..endIndex) result.add(dayList[i])
                        }
                    }
                }
            } else {
                val day = DAYS_MAP[token.trim()]
                if (day != null) result.add(day)
            }
        }
        return result
    }

    private fun parseTimeRange(timeStr: String, currentTime: LocalTime): EvaluationResult? {
        val regex = Regex("(\\d{1,2}):(\\d{2})\\s*-\\s*(\\d{1,2}):(\\d{2})")
        val match = regex.find(timeStr) ?: return null

        val (startH, startM, endH, endM) = match.destructured
        val startTime = LocalTime.of(startH.toInt().coerceIn(0, 23), startM.toInt().coerceIn(0, 59))
        val endHourRaw = endH.toInt()
        val endMinute = endM.toInt().coerceIn(0, 59)

        val isOvernight = endHourRaw < startTime.hour || (endHourRaw == startTime.hour && endMinute < startTime.minute) || endHourRaw == 24

        val currentSeconds = currentTime.toSecondOfDay()
        val startSeconds = startTime.toSecondOfDay()
        val endSeconds = if (endHourRaw == 24) 24 * 3600 else LocalTime.of(endHourRaw.coerceIn(0, 23), endMinute).toSecondOfDay()

        if (!isOvernight) {
            if (currentSeconds in startSeconds..endSeconds) {
                val remaining = (endSeconds - currentSeconds).coerceAtLeast(0).toLong()
                return EvaluationResult(ToiletStatus.OPEN, remaining)
            } else {
                return EvaluationResult(ToiletStatus.CLOSED, 0L)
            }
        } else {
            // Overnight: open if >= startTime OR <= endTime
            if (currentSeconds >= startSeconds) {
                val remaining = (24 * 3600 - currentSeconds + endSeconds).toLong()
                return EvaluationResult(ToiletStatus.OPEN, remaining)
            } else if (currentSeconds <= endSeconds) {
                val remaining = (endSeconds - currentSeconds).toLong()
                return EvaluationResult(ToiletStatus.OPEN, remaining)
            } else {
                return EvaluationResult(ToiletStatus.CLOSED, 0L)
            }
        }
    }
}
