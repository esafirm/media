package androidx.media3.demo.transformer.duet

import android.widget.Button
import androidx.camera.view.PreviewView
import androidx.media3.demo.transformer.R
import androidx.media3.ui.PlayerView

class DuetViewBinding(private val activity: DuetCameraActivity) {

    val actionButton by lazy { activity.findViewById<Button>(R.id.action_button) }
    val pickButton by lazy { activity.findViewById<Button>(R.id.pick_video_button) }

    val viewFinder by lazy { activity.findViewById<PreviewView>(R.id.viewFinder) }
    val inputPlayerView by lazy { activity.findViewById<PlayerView>(R.id.input_player_view) }

    fun inflate() {
        activity.setContentView(R.layout.activity_duet_camera)
    }
}
