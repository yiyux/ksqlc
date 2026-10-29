package ksqlc.app

import ksqlc.core.tr
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import ksqlc.core.DataSource
import ksqlc.drivers.HostKeyDecider
import ksqlc.drivers.Tunnels
import ksqlc.storage.ConnectionStore
import ksqlc.storage.HistoryStore
import ksqlc.storage.SettingsStore

/** The open consoles: each is an [AppState] with its own connection, editor and results; saved data sources and settings are shared. */
class Workspace(
    private val scope: CoroutineScope,
    private val store: ConnectionStore,
    private val settingsStore: SettingsStore = SettingsStore.default(),
    private val history: HistoryStore? = null,
) {
    private val shared = Shared(store, settingsStore)
    private fun create(preload: Boolean) = AppState(scope, store, settingsStore, history, shared, preload)

    private val used = HashSet<AppState>()

    val consoles = mutableStateListOf(create(true))
    var activeIndex by mutableStateOf(0)
        private set
    val active: AppState get() = consoles[activeIndex]

    fun activate(i: Int) {
        if (i !in consoles.indices) return
        activeIndex = i
        // the SSH host-key question belongs to the console the user is looking at
        Tunnels.hostKeyDecider = HostKeyDecider(consoles[i]::askHostKey)
    }

    /** Shows the console already open on [ds]; else reuses the active one if it is idle; else opens a new one. */
    fun open(ds: DataSource) {
        val at = consoles.indexOfFirst { it.form.currentId == ds.id }
        if (at >= 0) { activate(at); return }
        val a = active
        // connecting starts asynchronously, so a console already handed a data source is not idle yet
        if (a !in used && a.explorer == null && a.consoleSession == null && !a.hasUnsaved && !a.connecting) { used += a; a.selectAndConnect(ds) } else newConsole(ds)
    }

    /** A new console on [ds], even when it already has one. */
    fun newConsole(ds: DataSource) {
        val c = create(false)
        consoles.add(c)
        used += c
        activate(consoles.lastIndex)
        c.selectAndConnect(ds)
    }

    fun closeConsole(i: Int) {
        val c = consoles.getOrNull(i)?.takeIf { consoles.size > 1 } ?: return
        val prev = active
        activate(i)   // its confirmation is drawn over the active console only
        c.confirmDiscard(c.hasUnsaved, tr("Hay cambios sin enviar o transacciones sin confirmar.")) {
            consoles.remove(c)
            c.close()
            activate(consoles.indexOf(prev).takeIf { it >= 0 } ?: (i - 1).coerceAtLeast(0))
        }
    }

    /** "console [name]", numbered from the second console on the same data source. */
    fun title(i: Int): String {
        val name = consoles[i].form.name
        val n = consoles.take(i + 1).count { it.form.currentId == consoles[i].form.currentId && it.form.name == name }
        return if (n > 1) "console $n [$name]" else "console [$name]"
    }

    /** Asks to confirm leaving, then about each console with unsent work, one after the other, then exits. */
    fun requestExit(exit: () -> Unit) {
        fun next(i: Int) {
            if (i >= consoles.size) { exit(); return }
            activate(i)
            consoles[i].requestExit { next(i + 1) }
        }
        active.confirmation = Confirmation(tr("Salir de ksqlc"), tr("¿Seguro que quieres cerrar la aplicación?"), tr("Salir")) { active.confirmation = null; next(0) }
    }

    fun dispose() { consoles.forEach { it.close() }; Tunnels.closeAll() }
}
