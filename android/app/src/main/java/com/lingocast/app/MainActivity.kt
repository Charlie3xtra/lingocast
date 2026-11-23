package com.lingocast.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lingocast.app.ui.screens.CourseDetailScreen
import com.lingocast.app.ui.screens.ImportCenterScreen
import com.lingocast.app.ui.screens.LibraryScreen
import com.lingocast.app.ui.screens.SearchScreen
import com.lingocast.app.ui.screens.StudyPlayerScreen
import com.lingocast.app.ui.screens.VocabNotebookScreen
import com.lingocast.app.ui.theme.LingoCastTheme

private enum class TopTab(val route: String, val label: String, val icon: ImageVector) {
    LIBRARY("library", "课程", Icons.AutoMirrored.Rounded.LibraryBooks),
    VOCAB("vocab", "生词", Icons.Rounded.Bookmark),
    SEARCH("search", "检索", Icons.Rounded.Search),
    IMPORT("import", "导入", Icons.Rounded.CloudDownload)
}

private val TOP_ROUTES = TopTab.entries.map { it.route }.toSet()

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as LingoApplication).container

        setContent {
            val prefs by container.preferences.state.collectAsStateWithLifecycle()
            val windowSizeClass = calculateWindowSizeClass(this)

            LaunchedEffect(Unit) {
                container.importer.scanAdbPacksDir()
                container.importer.ensureStarterPackLoaded()
            }

            LingoCastTheme(
                themeMode = prefs.themeMode,
                dynamicColor = prefs.dynamicColor
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize()
                ) {
                    LingoCastAppRoot(
                        container = container,
                        widthSizeClass = windowSizeClass.widthSizeClass
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun LingoCastAppRoot(
    container: AppContainer,
    widthSizeClass: WindowWidthSizeClass
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: TopTab.LIBRARY.route

    val seriesList by container.repository.observeSeries().collectAsStateWithLifecycle(emptyList())
    val allEpisodes by container.repository.observeAllEpisodes().collectAsStateWithLifecycle(emptyList())
    val lastPlayed by container.repository.observeLastPlayedEpisode().collectAsStateWithLifecycle(null)
    val savedWords by container.repository.observeSavedWords().collectAsStateWithLifecycle(emptyList())
    val dueWords by container.repository.observeDueWords().collectAsStateWithLifecycle(emptyList())

    val showChrome = currentRoute in TOP_ROUTES
    val adaptiveInfo = currentWindowAdaptiveInfo()
    val layoutType = if (showChrome) {
        NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo)
    } else {
        NavigationSuiteType.None
    }

    fun navigateToPlayer(episodeKey: String, posMs: Long? = null) {
        val encoded = Uri.encode(episodeKey)
        val query = if (posMs != null) "?posMs=$posMs" else ""
        navController.navigate("player/$encoded$query")
    }

    SharedTransitionLayout {
        NavigationSuiteScaffold(
            layoutType = layoutType,
            navigationSuiteItems = {
                TopTab.entries.forEach { tab ->
                    item(
                        selected = currentRoute == tab.route,
                        onClick = {
                            if (currentRoute != tab.route) {
                                navController.navigate(tab.route) {
                                    popUpTo(TopTab.LIBRARY.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        ) {
            NavHost(
                navController = navController,
                startDestination = TopTab.LIBRARY.route,
                enterTransition = {
                    val isTabSwitch = initialState.destination.route in TOP_ROUTES &&
                        targetState.destination.route in TOP_ROUTES
                    if (isTabSwitch) {
                        fadeIn(tween(220)) + scaleIn(
                            initialScale = 0.97f,
                            animationSpec = tween(240, easing = FastOutSlowInEasing)
                        )
                    } else {
                        slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { (it * 0.22f).toInt() } +
                            scaleIn(initialScale = 0.95f, animationSpec = tween(320, easing = FastOutSlowInEasing)) +
                            fadeIn(tween(240))
                    }
                },
                exitTransition = {
                    val isTabSwitch = initialState.destination.route in TOP_ROUTES &&
                        targetState.destination.route in TOP_ROUTES
                    if (isTabSwitch) {
                        fadeOut(tween(150))
                    } else {
                        slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { -(it * 0.08f).toInt() } +
                            scaleOut(targetScale = 0.96f, animationSpec = tween(320, easing = FastOutSlowInEasing)) +
                            fadeOut(tween(200))
                    }
                },
                // Seekable tween specs enable Android 14-17 Predictive Back edge-swipe scrubbing
                popEnterTransition = {
                    scaleIn(initialScale = 0.93f, animationSpec = tween(300, easing = FastOutSlowInEasing)) +
                        slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { -(it * 0.08f).toInt() } +
                        fadeIn(tween(240))
                },
                popExitTransition = {
                    scaleOut(
                        targetScale = 0.88f,
                        transformOrigin = TransformOrigin(0.5f, 0.5f),
                        animationSpec = tween(300, easing = FastOutSlowInEasing)
                    ) +
                        slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { (it * 0.14f).toInt() } +
                        fadeOut(tween(220))
                }
            ) {
                composable(TopTab.LIBRARY.route) {
                    LibraryScreen(
                        seriesList = seriesList,
                        episodes = allEpisodes,
                        lastPlayed = lastPlayed,
                        savedWordCount = savedWords.size,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@composable,
                        onOpenSeries = { sid ->
                            navController.navigate("series/${Uri.encode(sid)}")
                        },
                        onOpenEpisode = { epKey -> navigateToPlayer(epKey) },
                        onOpenImport = {
                            navController.navigate(TopTab.IMPORT.route) {
                                popUpTo(TopTab.LIBRARY.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }

                composable(TopTab.VOCAB.route) {
                    VocabNotebookScreen(
                        allWords = savedWords,
                        dueWords = dueWords,
                        container = container,
                        onJumpToEpisode = { epKey, posMs -> navigateToPlayer(epKey, posMs) }
                    )
                }

                composable(TopTab.SEARCH.route) {
                    SearchScreen(
                        repository = container.repository,
                        onJumpToTimestamp = { epKey, posMs -> navigateToPlayer(epKey, posMs) }
                    )
                }

                composable(TopTab.IMPORT.route) {
                    ImportCenterScreen(container = container)
                }

                composable(
                    route = "series/{seriesId}",
                    arguments = listOf(navArgument("seriesId") { type = NavType.StringType })
                ) { entry ->
                    val sid = Uri.decode(entry.arguments?.getString("seriesId").orEmpty())
                    val series by container.repository.observeSeriesById(sid).collectAsStateWithLifecycle(null)
                    val seriesEps by container.repository.observeEpisodesBySeries(sid).collectAsStateWithLifecycle(emptyList())
                    CourseDetailScreen(
                        series = series,
                        episodes = seriesEps,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = this@composable,
                        onBack = { navController.popBackStack() },
                        onOpenEpisode = { epKey -> navigateToPlayer(epKey) }
                    )
                }

                composable(
                    route = "player/{episodeKey}?posMs={posMs}",
                    arguments = listOf(
                        navArgument("episodeKey") { type = NavType.StringType },
                        navArgument("posMs") {
                            type = NavType.LongType
                            defaultValue = -1L
                        }
                    )
                ) { entry ->
                    val epKey = Uri.decode(entry.arguments?.getString("episodeKey").orEmpty())
                    val rawPos = entry.arguments?.getLong("posMs") ?: -1L
                    StudyPlayerScreen(
                        episodeKey = epKey,
                        startPositionMs = rawPos.takeIf { it >= 0L },
                        widthSizeClass = widthSizeClass,
                        container = container,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
