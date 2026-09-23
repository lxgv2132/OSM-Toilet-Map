package com.osm.toiletmap

import kotlin.math.*

class ClosestToiletFinder(
    private val routingService: OsmRoutingService
) {

    data class ClosestResult(
        val toilet: Toilet,
        val route: RouteResult,
        val remainingOpenSeconds: Long
    )

    suspend fun findClosestOpenToilet(
        startLat: Double,
        startLon: Double,
        toilets: List<Toilet>,
        mode: TransportMode
    ): ClosestResult? {
        // 1. Filter only currently open toilets
        val openCandidates = mutableListOf<Pair<Toilet, Long>>()
        for (toilet in toilets) {
            val eval = OpeningHoursEvaluator.evaluate(toilet.openingHours)
            if (eval.status == ToiletStatus.OPEN && eval.remainingOpenSeconds != null) {
                openCandidates.add(Pair(toilet, eval.remainingOpenSeconds))
            }
        }

        if (openCandidates.isEmpty()) return null

        // 2. Sort candidates by straight-line distance to evaluate nearest first
        val sortedCandidates = openCandidates.sortedBy { (toilet, _) ->
            calculateEuclideanDistanceSq(startLat, startLon, toilet.lat, toilet.lon)
        }

        // 3. Evaluate each candidate against the 10-minute buffer rule:
        // Remaining time until closing >= Route duration + 10 minutes (600 seconds)
        for ((toilet, remainingSec) in sortedCandidates) {
            val route = routingService.calculateRoute(
                startLat, startLon,
                toilet.lat, toilet.lon,
                mode
            ) ?: continue

            val requiredDuration = route.durationSeconds + 600.0 // route duration + 10 min

            if (remainingSec.toDouble() >= requiredDuration) {
                // Found qualifying toilet!
                return ClosestResult(toilet, route, remainingSec)
            }
            // Otherwise doesn't qualify, loop continues to the next candidate!
        }

        return null
    }

    private fun calculateEuclideanDistanceSq(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = lat2 - lat1
        val dLon = (lon2 - lon1) * cos(Math.toRadians(lat1))
        return dLat * dLat + dLon * dLon
    }
}
