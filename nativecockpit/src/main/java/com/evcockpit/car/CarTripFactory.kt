package com.evcockpit.car

import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import java.util.TimeZone

/** Android for Cars models built ONLY from validated CarGuidance. */
internal object CarTripFactory {
    /** Avoid publishing an old maneuver while route match is uncertain. */
    fun loading(): Trip = Trip.Builder().setLoading(true).build()

    fun destinationEstimate(g: CarGuidance): TravelEstimate =
        TravelEstimate.Builder(
            Distance.create(g.remainingMeters, Distance.UNIT_METERS),
            DateTimeWithZone.create(g.destinationArrivalEpochMs, TimeZone.getDefault())
        ).setRemainingTimeSeconds(g.remainingSeconds).build()

    fun step(g: CarGuidance): Step? {
        val cue = g.nextCue ?: return null
        if (g.nextMeters == null || g.nextStepArrivalEpochMs == null ||
            g.nextStepSeconds == null) return null
        return Step.Builder(cue).apply {
            carManeuver(g.nextTurn)?.let { setManeuver(it) }
        }.build()
    }

    fun stepEstimate(g: CarGuidance): TravelEstimate? {
        val meters = g.nextMeters ?: return null
        val epoch = g.nextStepArrivalEpochMs ?: return null
        val seconds = g.nextStepSeconds ?: return null
        return TravelEstimate.Builder(
            Distance.create(meters, Distance.UNIT_METERS),
            DateTimeWithZone.create(epoch, TimeZone.getDefault())
        ).setRemainingTimeSeconds(seconds).build()
    }

    fun trip(g: CarGuidance): Trip {
        val builder = Trip.Builder()
            .addDestination(
                Destination.Builder().setName(g.destination).build(),
                destinationEstimate(g)
            )
        val step = step(g)
        val estimate = stepEstimate(g)
        if (step != null && estimate != null) builder.addStep(step, estimate)
        return builder.build()
    }

    private fun carManeuver(turn: CarTurn?): Maneuver? {
        val kind = when (turn) {
            CarTurn.LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
            CarTurn.RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            CarTurn.SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
            CarTurn.SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
            CarTurn.SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
            CarTurn.SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
            CarTurn.CONTINUE -> Maneuver.TYPE_STRAIGHT
            CarTurn.FORK_LEFT -> Maneuver.TYPE_FORK_LEFT
            CarTurn.FORK_RIGHT -> Maneuver.TYPE_FORK_RIGHT
            CarTurn.MERGE_LEFT -> Maneuver.TYPE_MERGE_LEFT
            CarTurn.MERGE_RIGHT -> Maneuver.TYPE_MERGE_RIGHT
            CarTurn.RAMP_LEFT -> Maneuver.TYPE_ON_RAMP_NORMAL_LEFT
            CarTurn.RAMP_RIGHT -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
            CarTurn.U_TURN, CarTurn.ROUNDABOUT, CarTurn.ARRIVE, null -> return null
        }
        return Maneuver.Builder(kind).build()
    }
}
