package tech.medina.wolfssl_kt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import tech.medina.wolfssl_kt.ui.dashboard.DashboardScreen
import tech.medina.wolfssl_kt.ui.home.HomeScreen
import tech.medina.wolfssl_kt.ui.notifications.NotificationsScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                WolfSslApp()
            }
        }
    }
}

private enum class BottomNavDestination(
    val route: String,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int
) {
    HOME("home", R.string.title_home, R.drawable.ic_home_black_24dp),
    DASHBOARD("dashboard", R.string.title_dashboard, R.drawable.ic_dashboard_black_24dp),
    NOTIFICATIONS("notifications", R.string.title_notifications, R.drawable.ic_notifications_black_24dp)
}

@Composable
private fun WolfSslApp() {
    val navController = rememberNavController()
    val items = BottomNavDestination.entries
    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                items.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (!selected) {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                painter = painterResource(destination.iconRes),
                                contentDescription = stringResource(destination.labelRes)
                            )
                        },
                        label = { Text(text = stringResource(destination.labelRes)) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = BottomNavDestination.HOME.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(BottomNavDestination.HOME.route) {
                HomeScreen()
            }
            composable(BottomNavDestination.DASHBOARD.route) {
                DashboardScreen()
            }
            composable(BottomNavDestination.NOTIFICATIONS.route) {
                NotificationsScreen()
            }
        }
    }
}
