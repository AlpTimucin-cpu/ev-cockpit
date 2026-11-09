package com.evcockpit.car

/** Stable, Android-independent semantics for the limited OSRM maneuvers we actually parse.
 * Unknown types are never promoted to a guessed turn direction.
 */
enum class CarTurn {
    LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, SHARP_LEFT, SHARP_RIGHT,
    U_TURN, CONTINUE, FORK_LEFT, FORK_RIGHT, MERGE_LEFT, MERGE_RIGHT,
    RAMP_LEFT, RAMP_RIGHT, ROUNDABOUT, ARRIVE
}

object CarTurnMapping {
    fun fromOsrm(type: String?, modifier: String?): CarTurn? = when (type) {
        "turn", "end of road" -> when (modifier) {
            "left" -> CarTurn.LEFT
            "right" -> CarTurn.RIGHT
            "slight left" -> CarTurn.SLIGHT_LEFT
            "slight right" -> CarTurn.SLIGHT_RIGHT
            "sharp left" -> CarTurn.SHARP_LEFT
            "sharp right" -> CarTurn.SHARP_RIGHT
            "uturn" -> CarTurn.U_TURN
            "straight" -> CarTurn.CONTINUE
            else -> null
        }
        "continue", "new name" -> CarTurn.CONTINUE
        "fork" -> when (modifier) {
            "left", "slight left" -> CarTurn.FORK_LEFT
            "right", "slight right" -> CarTurn.FORK_RIGHT
            else -> null
        }
        "merge" -> when (modifier) {
            "left", "slight left" -> CarTurn.MERGE_LEFT
            "right", "slight right" -> CarTurn.MERGE_RIGHT
            else -> null
        }
        "on ramp", "off ramp" -> when (modifier) {
            "left", "slight left" -> CarTurn.RAMP_LEFT
            "right", "slight right" -> CarTurn.RAMP_RIGHT
            else -> null
        }
        "roundabout", "rotary", "exit roundabout", "exit rotary" -> CarTurn.ROUNDABOUT
        "arrive" -> CarTurn.ARRIVE
        else -> null
    }
}
