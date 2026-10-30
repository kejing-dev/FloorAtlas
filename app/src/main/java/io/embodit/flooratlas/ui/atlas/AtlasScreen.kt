package io.embodit.flooratlas.ui.atlas

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.flooratlas.platform.AtlasGraph
import io.embodit.flooratlas.platform.AtlasLog
import io.embodit.flooratlas.platform.Cues
import io.embodit.flooratlas.platform.Speech
import io.embodit.flooratlas.ui.atlas.components.AtlasStage
import io.embodit.flooratlas.ui.atlas.components.LocalAtlasResources
import io.embodit.flooratlas.ui.atlas.components.localizedResources
import io.embodit.flooratlas.ui.atlas.debug.Probe
import io.embodit.flooratlas.ui.atlas.scene.AtlasFrameSystem
import io.embodit.flooratlas.ui.atlas.scene.AtlasScene

/**
 * The one screen of the app, in a Mixed Stage (Full Space). It wires the ViewModel (state), the
 * [AtlasScene] (map, per-frame work, gestures), [AtlasStage] (panels) and the sounds, and owns nothing.
 */
@Composable
fun AtlasScreen() {
    val context = LocalContext.current
    val viewModel: AtlasViewModel = viewModel(factory = AtlasViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    remember(context) {
        Probe.enabled = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        true
    }
    val store = remember(context) { AtlasGraph.tiles(context) }
    val scene = remember(viewModel) { AtlasScene(store, viewModel::onEvent, viewModel::zh) }
    val cues = remember(context) { Cues(context) }
    val speech = remember(context) { Speech(context) }
    var voice by remember { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(scene, lifecycle) {
        registerSystem<AtlasFrameSystem>()
        Probe.host =
            object : Probe.Host {
                override fun viewModel(): AtlasViewModel = viewModel

                override fun scene(): AtlasScene = scene
            }
        // in the background every tracker stops, a quiz pauses, speech stops (design `container.lifecycle`)
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> {
                        viewModel.onEvent(AtlasEvent.Background)
                        speech.stop()
                        scene.pause()
                    }
                    Lifecycle.Event.ON_START -> scene.resume()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        AtlasLog.i("STAGE enter")
        onDispose {
            lifecycle.removeObserver(observer)
            Probe.host = null
            unregisterSystem<AtlasFrameSystem>()
            scene.detach()
            cues.release()
            speech.release()
            AtlasLog.i("STAGE disposed")
        }
    }

    LaunchedEffect(scene, state.phase) { scene.setAtlas(viewModel.atlas) }
    LaunchedEffect(scene, state) { scene.render(state) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                AtlasEffect.Exit -> {
                    AtlasLog.i("STAGE close requested")
                    runCatching { navigator.closeStage() }.onFailure { AtlasLog.e("STAGE close failed: $it", it) }
                }
                is AtlasEffect.Cue -> cues.play(
                    when (effect.kind) {
                        FeedbackKind.RIGHT -> Cues.Cue.RIGHT
                        FeedbackKind.WRONG -> Cues.Cue.WRONG
                        FeedbackKind.REVEAL -> Cues.Cue.REVEAL
                    }
                )
                is AtlasEffect.Speak -> speech.say(effect.text, effect.zh)
                AtlasEffect.StopSpeech -> speech.stop()
                AtlasEffect.Recenter -> scene.recenter()
                is AtlasEffect.FlashTarget -> Unit
            }
        }
    }

    // does this language have a voice? (needsCheck N3; the menu greys the switch out when not)
    LaunchedEffect(state.language, state.phase) { voice = speech.check(viewModel.zh()) || speech.available == null }

    val resources = remember(state.settings.language, context) { localizedResources(context, state.settings.language) }
    CompositionLocalProvider(LocalAtlasResources provides resources) {
        AtlasStage(state = state, atlas = viewModel.atlas, scene = scene, voice = voice, onEvent = viewModel::onEvent)
    }
}
