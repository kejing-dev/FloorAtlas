package io.embodit.flooratlas.platform

import android.app.Application
import com.pico.spatial.ui.foundation.dsl.launch
import io.embodit.flooratlas.mainApp

class SpatialApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        launch(::mainApp)
    }
}
