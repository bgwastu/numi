package net.wastu.numi.ui

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.wastu.numi.R
import net.wastu.numi.SettingsActivity
import org.fossify.commons.compose.theme.AppTheme
import org.fossify.commons.extensions.getProperBackgroundColor

/** Reuse the native inbox's theme, including its saved appearance preferences. */
@Composable
fun NumiTheme(content: @Composable () -> Unit) = AppTheme(content)

fun ComponentActivity.setNumiContent(content: @Composable () -> Unit) {
    val dark = Color(getProperBackgroundColor()).luminance() < 0.5f
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
        navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
    )
    setContent { NumiTheme(content) }
}

enum class Destination(val label: String, @param:DrawableRes val icon: Int) {
    MESSAGES("Messages", org.fossify.commons.R.drawable.ic_sms_vector),
    CALLS("Calls", org.fossify.commons.R.drawable.ic_phone_vector),
    SETTINGS("Settings", org.fossify.commons.R.drawable.ic_settings_cog_vector);

    fun open(activity: Activity) {
        val target = when (this) {
            MESSAGES -> org.fossify.messages.activities.MainActivity::class.java
            CALLS -> org.fossify.phone.activities.MainActivity::class.java
            SETTINGS -> SettingsActivity::class.java
        }
        activity.startActivity(
            Intent(activity, target).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            ActivityOptions.makeCustomAnimation(activity, 0, 0).toBundle()
        )
    }
}

/** The forks own their screens; the host supplies the same navigation to both. */
fun attachNavigation(activity: Activity, container: ViewGroup, destination: String) {
    val navigation = container.getChildAt(0) as? ComposeView ?: ComposeView(activity).also {
        container.addView(it)
    }
    navigation.apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { NumiTheme { NumiNavigation(Destination.valueOf(destination), activity) } }
    }
}

@Composable
fun NumiNavigation(selected: Destination, activity: Activity) {
    NavigationBar(windowInsets = WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)) {
        Destination.entries.forEach { destination ->
            NavigationBarItem(
                selected = selected == destination,
                onClick = { if (selected != destination) destination.open(activity) },
                icon = { NumiIcon(destination.icon) },
                label = { Text(destination.label) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NumiPage(
    title: String,
    back: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(title) }, navigationIcon = {
                back?.let { IconButton(onClick = it) { NumiIcon(R.drawable.numi_ic_back, "Back") } }
            }, actions = actions)
        },
        bottomBar = bottomBar,
        snackbarHost = snackbarHost
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 600.dp).fillMaxSize()) { content(PaddingValues()) }
        }
    }
}

@Composable
fun NumiIcon(@DrawableRes icon: Int, description: String? = null) {
    Icon(painterResource(icon), description, Modifier.size(24.dp))
}

@Composable
fun SettingRow(
    title: String,
    value: String? = null,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
    valueMaxLines: Int = Int.MAX_VALUE,
    onClick: (() -> Unit)? = null
) {
    ListItem(
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier),
        headlineContent = { Text(title) },
        supportingContent = value?.let { { Text(it, maxLines = valueMaxLines, overflow = TextOverflow.Clip) } },
        leadingContent = icon?.let { { NumiIcon(it) } },
        trailingContent = trailing ?: onClick?.let { { NumiIcon(R.drawable.numi_ic_chevron) } }
    )
}

@Composable
fun ToggleRow(title: String, checked: Boolean, enabled: Boolean = true, value: String? = null, change: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = change),
        headlineContent = { Text(title) },
        supportingContent = value?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, enabled = enabled, onCheckedChange = null) }
    )
}

@Composable
fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(title, Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        content()
    }
}

/** Poll only while the screen is visible, and publish snapshots on the UI thread. */
@Composable
fun <T> rememberStatus(read: () -> T): State<T?> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState<T?>(null, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = withContext(Dispatchers.IO) { read() }
                delay(2000)
            }
        }
    }
}
