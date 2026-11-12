package app.kinjar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import app.kinjar.data.security.AppLock
import app.kinjar.data.security.LockPurpose
import app.kinjar.ui.security.LockPrompter
import app.kinjar.ui.security.LockScreen
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.graphics.Color
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.view.WindowCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import app.kinjar.data.rates.RateRefresh
import app.kinjar.ui.settings.ExchangeRatesScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.compose.animation.EnterTransition
import androidx.compose.runtime.remember
import app.kinjar.ui.navigation.navEnter
import app.kinjar.ui.navigation.navExit
import app.kinjar.ui.navigation.navPopExit
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.Lazy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.isSystemInDarkTheme
import app.kinjar.data.BudgetRepository
import app.kinjar.data.ThemeMode
import app.kinjar.data.ThemeStore
import app.kinjar.ui.Routes
import app.kinjar.ui.Tab
import app.kinjar.ui.add.AddScreen
import app.kinjar.ui.scan.ScanScreen
import app.kinjar.ui.home.HomeScreen
import app.kinjar.ui.goals.EditGoalScreen
import app.kinjar.ui.insights.InsightsScreen
import app.kinjar.ui.projects.ProjectsScreen
import app.kinjar.ui.projects.projectRoutes
import app.kinjar.ui.settings.AccountsScreen
import app.kinjar.ui.settings.CategoriesScreen
import app.kinjar.ui.settings.EditAccountScreen
import app.kinjar.ui.settings.EditCategoryScreen
import app.kinjar.ui.settings.EditRecurringScreen
import app.kinjar.ui.settings.RecurringScreen
import app.kinjar.ui.settings.SettingsScreen
import app.kinjar.ui.setup.SetupScreen
import app.kinjar.ui.theme.BodyStyle
import app.kinjar.ui.theme.KinjarTheme
import app.kinjar.ui.theme.LocalColors
import app.kinjar.ui.theme.sans
import app.kinjar.ui.update.UpdateHost
import app.kinjar.ui.update.UpdateViewModel
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val updates: UpdateViewModel by viewModels()
    private val updatePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updates.permissionReturned(packageManager.canRequestPackageInstalls())
    }
    private val updateInstaller = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(app.kinjar.ui.withSerbianLatin(newBase))
    }

    @Inject lateinit var appLock: AppLock
    @Inject lateinit var sync: dagger.Lazy<app.kinjar.data.sync.SyncCoordinator>
    private lateinit var prompter: LockPrompter
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            appLock.onScreenOff()
            prompter.cancel()
            updates.onBackground()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.FIREBASE_SYNC_ENABLED) lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { sync.get().foreground() }
        }
        // Main.immediate collects synchronously, so FLAG_SECURE is set before the first frame.
        lifecycleScope.launch {
            appLock.state.map { it.enabled }.distinctUntilChanged().collect { secure ->
                if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        prompter = LockPrompter(this, appLock)
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        enableEdgeToEdge()
        setContent {
            val root: RootViewModel = hiltViewModel()
            val mode by root.theme.collectAsStateWithLifecycle()
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Observe lock changes without waiting for the lifecycle-aware collector to restart.
            val lock by appLock.state.collectAsState()
            val screens = rememberSaveableStateHolder()
            KinjarTheme(dark) {
                if (lock.locked) LockScreen(lock) { appLock.request(LockPurpose.UNLOCK) }
                else screens.SaveableStateProvider("kinjar") {
                    KinjarRoot(root, updates)
                    UpdateHost(updates, ::installUpdate)
                }
            }
        }
    }

    override fun onStart() {
        appLock.onForeground()
        super.onStart()
    }

    override fun onResume() {
        super.onResume()
        appLock.consumeAutoPrompt()
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            appLock.onBackground()
            prompter.cancel()
            updates.onBackground()
        }
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        super.onDestroy()
    }

    private fun installUpdate(file: File) {
        if (appLock.state.value.locked || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        try {
            if (!packageManager.canRequestPackageInstalls()) {
                updatePermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
            updateInstaller.launch(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = ClipData.newRawUri("update", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            updates.installationFailed()
        }
    }
}

@HiltViewModel
class RootViewModel @Inject constructor(private val repo: Lazy<BudgetRepository>, themeStore: ThemeStore, private val rateRefresh: Lazy<RateRefresh>) : ViewModel() {
    private val member = MutableStateFlow<Boolean?>(null)
    val hasMember: StateFlow<Boolean?> = member
    private val failure = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = failure
    private var loading = false
    val theme: StateFlow<ThemeMode> = themeStore.mode

    init { load() }

    suspend fun refreshRates() {
        if (member.value != null) withContext(Dispatchers.IO) { rateRefresh.get().refresh() }
    }

    fun load() {
        if (loading || member.value != null) return
        loading = true
        failure.value = false
        viewModelScope.launch {
            try {
                member.value = withContext(Dispatchers.IO) { repo.get().members.first().isNotEmpty() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failure.value = true
            } finally {
                loading = false
            }
        }
    }
}

@Composable
fun KinjarRoot(root: RootViewModel, updates: UpdateViewModel) {
    val hasMember by root.hasMember.collectAsStateWithLifecycle()
    val failed by root.failed.collectAsStateWithLifecycle()
    if (hasMember == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (failed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.startup_failed), color = LocalColors.current.text)
                androidx.compose.material3.TextButton(onClick = root::load) { Text(stringResource(R.string.startup_retry)) }
            } else androidx.compose.material3.CircularProgressIndicator()
        }
        return
    }
    val c = LocalColors.current
    val nav = rememberNavController()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, root) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                root.refreshRates()
                delay(60_000)
            }
        }
    }
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    var scanEditing by rememberSaveable { mutableStateOf(false) }
    val cameraSurface = route == Tab.SCAN.route && !scanEditing
    val activity = LocalActivity.current
    LaunchedEffect(c.dark, cameraSurface) {
        activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView).isAppearanceLightStatusBars = !c.dark && !cameraSurface }
    }
    val selectTab = remember(nav) {
        { tab: Tab ->
            nav.navigate(tab.route) {
                popUpTo(Tab.HOME.route) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // The bottom bar lives inside each tab page, so content padding never changes between routes and pages do not remeasure mid-transition.
    Scaffold(containerColor = if (cameraSurface) (if (c.dark) Color.Black else Color(0xFF111315)) else c.bg) { padding ->
        NavHost(
            nav,
            startDestination = if (hasMember == true) Tab.HOME.route else Routes.SETUP,
            modifier = Modifier.padding(padding),
            enterTransition = { navEnter() },
            exitTransition = { navExit() },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { navPopExit() },
        ) {
            composable(Tab.HOME.route) {
                TabPage(Tab.HOME, selectTab) {
                    HomeScreen(
                        onAdd = { nav.navigate(Routes.add()) },
                        onActivity = { nav.navigate(Routes.ACTIVITY) },
                        onGoal = { nav.navigate(Routes.editGoal(it)) },
                        onSettings = { nav.navigate(Routes.SETTINGS) },
                        onEditTransaction = { nav.navigate(Routes.editTransaction(it)) },
                    )
                }
            }
            page(Routes.ACTIVITY, arguments = listOf(navArgument("activity") { defaultValue = true })) {
                app.kinjar.ui.home.ActivityScreen(onBack = { nav.popBackStack() }, onEdit = { nav.navigate(Routes.editTransaction(it)) })
            }
            page(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onCategories = { nav.navigate(Routes.CATEGORIES) },
                    onProfile = { nav.navigate(Routes.PROFILE) },
                    onRecurring = { nav.navigate(Routes.RECURRING) },
                    onRates = { nav.navigate(Routes.EXCHANGE_RATES) },
                    onFamily = { nav.navigate(Routes.family()) },
                    onBackup = { nav.navigate(Routes.BACKUP) },
                    updates = updates,
                )
            }
            page(Routes.PROFILE) { app.kinjar.ui.settings.ProfileScreen(onBack = { nav.popBackStack() }) }
            page(Routes.FAMILY, arguments = listOf(navArgument("signIn") { type = androidx.navigation.NavType.BoolType; defaultValue = false })) { entry ->
                app.kinjar.ui.sync.FamilyScreen(onBack = { nav.popBackStack() },
                    onContinue = { nav.navigate(Tab.HOME.route) { popUpTo(nav.graph.id) { inclusive = false }; launchSingleTop = true } },
                    onBackup = { nav.navigate(Routes.BACKUP) },
                    startSignIn = entry.arguments?.getBoolean("signIn") == true,
                    onCreate = { nav.navigate(Routes.CLOUD_SETUP) { launchSingleTop = true } })
            }
            page(Routes.CLOUD_SETUP) {
                app.kinjar.ui.setup.FamilySetupScreen(onBack = { nav.popBackStack() },
                    onDone = { nav.navigate(Tab.HOME.route) { popUpTo(nav.graph.id) { inclusive = false }; launchSingleTop = true } })
            }
            page(Routes.BACKUP) {
                app.kinjar.ui.sync.BackupScreen(onBack = { nav.popBackStack() },
                    onContinue = { nav.navigate(Tab.HOME.route) { popUpTo(nav.graph.id) { inclusive = false }; launchSingleTop = true } })
            }
            page(Routes.RECURRING) {
                RecurringScreen(
                    onBack = { nav.popBackStack() },
                    onNew = { nav.navigate(Routes.NEW_RECURRING) },
                    onEdit = { nav.navigate(Routes.editRecurring(it)) },
                    onPay = { ruleId, due -> nav.navigate(Routes.addBill(ruleId, due)) },
                )
            }
            page(Routes.EXCHANGE_RATES) { ExchangeRatesScreen(onBack = { nav.popBackStack() }) }
            page(Routes.NEW_RECURRING) { EditRecurringScreen(onClose = { nav.popBackStack() }) }
            page(Routes.EDIT_RECURRING) { EditRecurringScreen(onClose = { nav.popBackStack() }) }
            page(Routes.CATEGORIES) {
                CategoriesScreen(onBack = { nav.popBackStack() }, onNew = { nav.navigate(Routes.newCategory(it)) }, onEdit = { nav.navigate(Routes.editCategory(it)) })
            }
            page(Routes.NEW_CATEGORY, arguments = listOf(navArgument("kind") { defaultValue = "EXPENSE" })) {
                EditCategoryScreen(onClose = { nav.popBackStack() })
            }
            page(Routes.EDIT_CATEGORY) { EditCategoryScreen(onClose = { nav.popBackStack() }) }
            composable(Tab.ACCOUNTS.route) {
                TabPage(Tab.ACCOUNTS, selectTab) {
                    AccountsScreen(onNew = { nav.navigate(Routes.NEW_ACCOUNT) }, onEdit = { nav.navigate(Routes.editAccount(it)) },
                        onNewLoan = { nav.navigate(Routes.NEW_LOAN) }, onLoan = { nav.navigate(Routes.loan(it)) })
                }
            }
            page(Routes.NEW_LOAN) { app.kinjar.ui.loans.LoanEditorScreen(onBack = { nav.popBackStack() }) }
            page(Routes.EDIT_LOAN) { app.kinjar.ui.loans.LoanEditorScreen(onBack = { nav.popBackStack() }) }
            page(Routes.LOAN) {
                app.kinjar.ui.loans.LoanDetailScreen(onBack = { nav.popBackStack() }, onEdit = { nav.navigate(Routes.editLoan(it)) })
            }
            page(Routes.NEW_ACCOUNT) { EditAccountScreen(onClose = { nav.popBackStack() }) }
            page(Routes.EDIT_ACCOUNT) { EditAccountScreen(onClose = { nav.popBackStack() }) }
            composable(Tab.INSIGHTS.route) { TabPage(Tab.INSIGHTS, selectTab) { InsightsScreen() } }
            composable(Tab.SCAN.route) {
                ScanScreen(
                    onEditingChanged = { scanEditing = it },
                    onClose = { nav.popBackStack() },
                    onSaved = { nav.navigate(Tab.HOME.route) { popUpTo(Tab.HOME.route) { inclusive = false }; launchSingleTop = true } },
                    onManual = { nav.navigate(Routes.add()) },
                )
            }
            composable(Tab.GOALS.route) {
                TabPage(Tab.GOALS, selectTab) {
                    ProjectsScreen(
                        onNewProject = { nav.navigate(Routes.NEW_PROJECT) },
                        onProject = { nav.navigate(Routes.project(it)) },
                        onNewGoal = { nav.navigate(Routes.NEW_GOAL) },
                        onGoal = { nav.navigate(Routes.editGoal(it)) },
                    )
                }
            }
            projectRoutes(nav)
            page(Routes.NEW_GOAL) { EditGoalScreen(onClose = { nav.popBackStack() }) }
            page(Routes.EDIT_GOAL) { EditGoalScreen(onClose = { nav.popBackStack() }) }
            page(Routes.EDIT_TRANSACTION) { AddScreen(onClose = { nav.popBackStack() }) }
            page(Routes.SETUP) {
                SetupScreen(onDone = { nav.navigate(Tab.HOME.route) { popUpTo(Routes.SETUP) { inclusive = true } } },
                    onFamily = { nav.navigate(Routes.family(signIn = true)) }, onBackup = { nav.navigate(Routes.BACKUP) })
            }
            page(
                Routes.ADD,
                arguments = listOf(
                    navArgument("rule") { nullable = true; defaultValue = null },
                    navArgument("due") { nullable = true; defaultValue = null },
                ),
            ) { AddScreen(onClose = { nav.popBackStack() }) }
        }
    }
}

private fun NavGraphBuilder.page(route: String, arguments: List<NamedNavArgument> = emptyList(), content: @Composable (NavBackStackEntry) -> Unit) =
    composable(route, arguments) { entry ->
        Box(Modifier.fillMaxSize().background(LocalColors.current.bg)) { content(entry) }
    }

@Composable
private fun TabPage(tab: Tab, onSelect: (Tab) -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(LocalColors.current.bg)) {
        Box(Modifier.weight(1f)) { content() }
        BottomBar(tab.route, onSelect)
    }
}

@Composable
private fun BottomBar(route: String?, onSelect: (Tab) -> Unit) {
    val c = LocalColors.current
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.matchParentSize().padding(top = 24.dp).background(c.bg)) {
            HorizontalDivider(color = c.border, thickness = 1.dp)
        }
        Row(Modifier.fillMaxWidth().height(104.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
            Tab.entries.forEach { tab ->
                val active = route == tab.route
                val tint = if (active) c.primary else c.muted
                Column(
                    Modifier.weight(1f).fillMaxHeight().semantics { selected = active }
                        .clickable(interactionSource = null, indication = null, role = Role.Tab) { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
                ) {
                    if (tab == Tab.SCAN) {
                        Box(
                            Modifier.size(72.dp).background(c.primary, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(tab.icon, contentDescription = null, tint = c.onPrimary, modifier = Modifier.size(26.dp)) }
                        Text(stringResource(tab.label), style = sans(11, FontWeight.Medium), color = tint)
                    } else {
                        Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                        Text(stringResource(tab.label), style = sans(11, FontWeight.Medium), color = tint)
                    }
                }
            }
        }
    }
}

@Composable
private fun Stub(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text, style = BodyStyle, color = LocalColors.current.muted) }
}
