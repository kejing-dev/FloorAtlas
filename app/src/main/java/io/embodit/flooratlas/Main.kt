package io.embodit.flooratlas

import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import io.embodit.flooratlas.ui.atlas.AtlasScreen
import io.embodit.flooratlas.ui.theme.AtlasTheme

/**
 * App entry. One Mixed Stage (AndroidManifest: `pico.spatial.stage.style=1`, immersion 0/0/0): Full Space with
 * passthrough holds the whole product; there is no WindowContainer and no Shared Space version (design `container`).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultStage {
            AtlasTheme {
                AtlasScreen()
            }
        }
    }
