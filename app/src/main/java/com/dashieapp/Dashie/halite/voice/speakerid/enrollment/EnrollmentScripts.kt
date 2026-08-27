package com.dashieapp.Dashie.halite.voice.speakerid.enrollment

/**
 * Lines read aloud during guided voice enrollment. 5 phonetically varied
 * sentences + 2 assistant-style commands (matching real probe phrasing) —
 * mirrors Apple's ~5-utterance explicit enrollment, plus command-shaped
 * audio because that's what identification will actually score.
 *
 * Keep lines short enough to fit one breath at a natural pace (~4 s
 * recording window) and readable by kids.
 */
object EnrollmentScripts {

    val LINES: List<String> = listOf(
        "The quick brown fox jumps over the lazy dog near the river.",
        "My favorite season is autumn, when the leaves turn orange and red.",
        "We should visit grandma on Sunday and bring her some fresh flowers.",
        "Yesterday I learned how to make pancakes with blueberries and honey.",
        "Please remember to close the garage door before it starts to rain.",
        "Hey Dashie, what time is my next meeting today?",
        "Hey Dashie, set a timer for ten minutes.",
    )

    const val MIN_GOOD_TAKES = 5
}
