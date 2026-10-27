package io.github.helliott20.muo

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView

/**
 * Stereo (TWS) pairing is handled entirely by the speakers' own firmware. The official app only
 * ever showed these steps; it sent nothing to the speakers. Wording follows the original guide.
 */
class StereoGuideActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Stereo pairing guide"
        val root = column()
        root.add(
            text("Two MUOs can link to each other wirelessly. Your phone connects to one; it relays audio to the other."),
            heading("1. First speaker (Left)"),
            text("Turn on one MUO and connect it to your phone as normal."),
            text("Press and hold the POWER and PAIRING buttons together for 2 seconds. It starts searching for a " +
                "second MUO and becomes the Left channel."),
            heading("2. Second speaker (Right)"),
            text("Turn on the second MUO. Make sure it is NOT connected to your phone. Turn off Bluetooth on " +
                "other nearby phones or tablets it knows, too."),
            text("Press and hold the POWER and PAIRING buttons together for 2 seconds. It searches for the first MUO " +
                "and becomes the Right channel."),
            heading("3. Wait"),
            text("Within 10 to 15 seconds the two speakers connect to each other."),
            heading("Stereo or party"),
            text("Stand both speakers upright (vertical) for Stereo: left and right channels."),
            text("Lay them flat (horizontal) for Party: both play the same full mix."),
            text("You can also press POWER and PAIRING together once on either speaker to toggle between Stereo and Party."),
            heading("Back to one speaker"),
            text("Hold POWER and PAIRING on either MUO for 2 seconds."),
            heading("If it won't link"),
            text("• Charge both speakers, and keep them within a couple of metres while pairing.\n" +
                "• Disconnect the second speaker from every phone first. It must be free to link.\n" +
                "• Turn both off and on, then start again from step 1.\n" +
                "• If one speaker was linked to a different MUO before, a factory reset clears it. See KEF's MUO manual."),
        )
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
