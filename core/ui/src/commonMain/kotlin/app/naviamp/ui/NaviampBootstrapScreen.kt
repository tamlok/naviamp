package app.naviamp.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.naviamp.ui.generated.resources.Res
import app.naviamp.ui.generated.resources.bootstrap_preparing
import org.jetbrains.compose.resources.stringResource

/** Shared transitional surface shown while a thin host assembles the real Core environment. */
@Composable
fun NaviampBootstrapScreen(modifier: Modifier = Modifier) {
    NaviampTheme(LocalNaviampTheme.current) {
        Surface(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(Res.string.bootstrap_preparing))
            }
        }
    }
}
