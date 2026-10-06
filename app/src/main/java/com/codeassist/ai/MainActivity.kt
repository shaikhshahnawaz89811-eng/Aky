package com.codeassist.ai

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import com.codeassist.ai.chats.ChatsFragment
import com.codeassist.ai.data.Store
import com.codeassist.ai.home.HomeFragment
import com.codeassist.ai.projects.ProjectsFragment
import com.codeassist.ai.settings.SettingsFragment
import com.codeassist.ai.service.HandsFree
import com.codeassist.ai.service.HealthCheck
import com.codeassist.ai.settings.BatteryDialogs
import com.codeassist.ai.settings.VoiceAiFragment
import com.codeassist.ai.voice.WakeCoordinator
import com.codeassist.ai.workspace.WorkspaceActivity

/**
 * Single-activity shell with a ChatGPT/Claude-style side drawer.
 *  - No bottom bar: navigation lives in the drawer (hamburger, top-left).
 *  - Chats open in-place on the home screen — sending a message never
 *    navigates the user away.
 *  - singleTask + persisted active chat: returning from background never
 *    "refreshes" the app — you land exactly where you left.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_CHAT = "open_chat_id"

        /** Set by the "Haan? Maine suna" notification: start listening as soon as the home screen is up. */
        const val EXTRA_WAKE = "wake_start"
        const val EXTRA_WAKE_TEXT = "wake_text"
        private const val TAG_HOME = "home"
    }

    private lateinit var drawer: DrawerLayout
    private lateinit var drawerRecent: LinearLayout
    private lateinit var labelRecent: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        setContentView(R.layout.activity_main)

        drawer = findViewById(R.id.drawerLayout)
        drawerRecent = findViewById(R.id.drawerRecent)
        labelRecent = findViewById(R.id.labelRecent)

        // Always show the freshest chat list when the drawer opens
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) = refreshDrawer()
        })

        styleRow(findViewById(R.id.rowNewChat), R.drawable.ic_edit, "New chat", accent = true)
        styleRow(findViewById(R.id.rowChats), R.drawable.ic_chat, "Chats")
        styleRow(findViewById(R.id.rowProjects), R.drawable.ic_folder, "Projects")
        styleRow(findViewById(R.id.rowSettings), R.drawable.ic_settings, "Settings")

        findViewById<View>(R.id.rowNewChat).setOnClickListener { newChat() }
        findViewById<View>(R.id.rowChats).setOnClickListener { openTab { ChatsFragment() } }
        findViewById<View>(R.id.rowProjects).setOnClickListener { openTab { ProjectsFragment() } }
        findViewById<View>(R.id.rowSettings).setOnClickListener { openTab { SettingsFragment() } }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    drawer.isDrawerOpen(GravityCompat.START) ->
                        drawer.closeDrawer(GravityCompat.START)
                    supportFragmentManager.backStackEntryCount > 0 ->
                        supportFragmentManager.popBackStack()
                    else -> finish()
                }
            }
        })

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, HomeFragment(), TAG_HOME)
                .commit()
        }
        handleIntent(intent)
        if (savedInstanceState == null &&
            intent.getStringExtra(EXTRA_OPEN_CHAT) == null &&
            Store.openLastProject
        ) {
            Store.lastProjectId?.let { id ->
                if (Store.project(id) != null) {
                    window.decorView.post {
                        startActivity(
                            Intent(this, WorkspaceActivity::class.java)
                                .putExtra(WorkspaceActivity.EXTRA_PROJECT_ID, id)
                        )
                    }
                } else {
                    Store.lastProjectId = null
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        WakeCoordinator.appVisible = true
        // wake word heard while another screen (settings, chats ...) is open: go back to the home screen
        WakeCoordinator.activityHook = { _ ->
            drawer.closeDrawer(GravityCompat.START)
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            }
        }
        // the user opened the app: first record whether Android killed the hands-free service while the app was
        // away (part 2B health check), then bring it back: this is the allowed moment for a microphone service
        val askBattery = HealthCheck.onAppStart(this)
        HandsFree.rearmIfNeeded(this)
        if (askBattery) BatteryDialogs.showKilledPrompt(this)
    }

    override fun onStop() {
        WakeCoordinator.appVisible = false
        WakeCoordinator.activityHook = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshDrawer()
    }

    private fun handleIntent(i: Intent?) {
        if (i?.getBooleanExtra(EXTRA_WAKE, false) == true) {
            WakeCoordinator.setPending(i.getStringExtra(EXTRA_WAKE_TEXT).orEmpty())
            i.removeExtra(EXTRA_WAKE)
            i.removeExtra(EXTRA_WAKE_TEXT)
            if (supportFragmentManager.backStackEntryCount > 0) {
                supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            }
        }
        val chatId = i?.getStringExtra(EXTRA_OPEN_CHAT) ?: return
        i.removeExtra(EXTRA_OPEN_CHAT)
        openChat(chatId)
    }

    // ---------- Navigation ----------

    fun openDrawer() = drawer.openDrawer(GravityCompat.START)

    /** Open an existing chat in-place on the home screen. */
    fun openChat(chatId: String) {
        drawer.closeDrawer(GravityCompat.START)
        Store.activeChatId = chatId
        val onHome = supportFragmentManager.backStackEntryCount == 0
        if (!onHome) {
            supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            // home view is recreated synchronously and restores the chat from Store
        } else {
            homeFragment()?.loadChat(chatId)
        }
    }

    /** Reset the home screen to a fresh greeting (new chat). */
    fun newChat() {
        drawer.closeDrawer(GravityCompat.START)
        Store.activeChatId = null
        Store.activeDraft = ""
        val onHome = supportFragmentManager.backStackEntryCount == 0
        if (!onHome) {
            supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        } else {
            homeFragment()?.startNewChat()
        }
    }

    fun onHistoryCleared() {
        Store.activeChatId = null
        Store.activeDraft = ""
        val home = homeFragment()
        if (home?.view != null) home.startNewChat()
        refreshDrawer()
    }

    /** Voice and AI screen. It stacks on top of whatever is showing, so Back returns there. */
    fun openVoiceAi() {
        drawer.closeDrawer(GravityCompat.START)
        if (supportFragmentManager.findFragmentById(R.id.fragmentContainer) is VoiceAiFragment) return
        supportFragmentManager.beginTransaction()
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            .replace(R.id.fragmentContainer, VoiceAiFragment())
            .addToBackStack("voiceai")
            .commit()
    }

    private fun openTab(factory: () -> Fragment) {
        drawer.closeDrawer(GravityCompat.START)
        supportFragmentManager.popBackStackImmediate(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
            .replace(R.id.fragmentContainer, factory())
            .addToBackStack("tab")
            .commit()
    }

    private fun homeFragment(): HomeFragment? =
        supportFragmentManager.findFragmentByTag(TAG_HOME) as? HomeFragment

    // ---------- Drawer ----------

    private fun styleRow(row: View, icon: Int, title: String, accent: Boolean = false) {
        val iv = row.findViewById<ImageView>(R.id.rowIcon)
        val tv = row.findViewById<TextView>(R.id.rowTitle)
        iv.setImageResource(icon)
        tv.text = title
        if (accent) {
            iv.setColorFilter(0xFF6EC1FF.toInt())
            tv.setTextColor(0xFF6EC1FF.toInt())
        }
    }

    fun refreshDrawer() {
        drawerRecent.removeAllViews()
        val recents = Store.chats().take(8)
        labelRecent.visibility = if (recents.isEmpty()) View.GONE else View.VISIBLE
        val inf = LayoutInflater.from(this)
        for (c in recents) {
            val row = inf.inflate(R.layout.item_drawer_row, drawerRecent, false)
            (row.findViewById<ImageView>(R.id.rowIcon)).apply {
                setImageResource(R.drawable.ic_chat)
                setColorFilter(0xFF5D6B7C.toInt())
            }
            row.findViewById<TextView>(R.id.rowTitle).apply {
                text = c.title
                setTextColor(0xFF93A1B3.toInt())
                textSize = 13.5f
            }
            row.setOnClickListener { openChat(c.id) }
            drawerRecent.addView(row)
        }
    }
}
