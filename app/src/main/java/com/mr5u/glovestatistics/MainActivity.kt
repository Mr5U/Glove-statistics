package com.mr5u.glovestatistics

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                GloveApp()
            }
        }
    }
}

/** 底部导航的四个页面。 */
private enum class Tab(val label: String, val symbol: String) {
    TODAY("记工", "⌂"),
    RECORDS("记录", "▦"),
    LIBRARY("手套库", "◫"),
    SUMMARY("本月", "¥"),
}

@Composable
private fun GloveApp(vm: WorkViewModel = viewModel<WorkViewModel>()) {
    var tab by remember { mutableStateOf(Tab.TODAY) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val notify: (String) -> Unit = { message ->
        scope.launch { snackbar.showSnackbar(message) }
    }

    val fileActions = rememberFileActions(
        onMessage = notify,
        restore = { gloves, home, factory -> vm.replaceAll(gloves, home, factory) },
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Text(item.symbol) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val content = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(top = 8.dp)

        when (tab) {
            Tab.TODAY -> TodayScreen(
                vm = vm,
                onOpenCalendar = { tab = Tab.RECORDS },
                modifier = content,
            )

            Tab.RECORDS -> CalendarScreen(vm = vm, modifier = content)

            Tab.LIBRARY -> LibraryScreen(vm = vm, modifier = content)

            Tab.SUMMARY -> SummaryScreen(
                vm = vm,
                fileActions = fileActions,
                onMessage = notify,
                onOpenLibrary = { tab = Tab.LIBRARY },
                modifier = content,
            )
        }
    }
}
