package com.amr3d.preview.pro

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.net.Uri
import android.view.View
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.activity.OnBackPressedCallback
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView
    private lateinit var soundPool: SoundPool
    private var navSoundId = 0
    private var soundLoaded = false

    // Fragments — منشأة مرة واحدة فقط
    private val viewerFragment      by lazy { ViewerFragment() }
    private val fileBrowserFragment by lazy { FileBrowserFragment() }
    private val slicerFragment      by lazy { SlicerFragment() }
    private val historyFragment     by lazy { HistoryFragment() }
    private val settingsFragment    by lazy { SettingsFragment() }

    private val navOrder = listOf(
        R.id.nav_viewer, R.id.nav_history, R.id.nav_slicer,
        R.id.nav_files, R.id.nav_settings
    )
    private var currentNavIndex = 0
    private var currentFragment: Fragment? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppDisplayMode.applySavedMode(this) // لازم برضو هنا: التطبيق ممكن يتفتح مباشرة (Share من واتساب) من غير ما يعدي بالـ Splash
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initSound()
        bottomNav = findViewById(R.id.bottomNav)
        applyThemeToNav()

        // إضافة كل الـ Fragments مرة واحدة — hide/show بدلاً من replace
        // (savedInstanceState == null) بيمنع تعارض/كراش لو Android حاول يرجّع
        // نفس الـ Fragments تلقائياً بعد إعادة إنشاء الـ Activity (recreate/تدوير الشاشة)
        if (savedInstanceState == null) {
            val tx = supportFragmentManager.beginTransaction()
            tx.add(R.id.fragmentContainer, viewerFragment, "viewer")
            tx.add(R.id.fragmentContainer, fileBrowserFragment, "files")
            tx.add(R.id.fragmentContainer, slicerFragment, "slicer")
            tx.add(R.id.fragmentContainer, historyFragment, "history")
            tx.add(R.id.fragmentContainer, settingsFragment, "settings")
            // إخفاء الكل ما عدا الـ viewer
            tx.hide(fileBrowserFragment)
            tx.hide(slicerFragment)
            tx.hide(historyFragment)
            tx.hide(settingsFragment)
            tx.commitNow()
            currentFragment = viewerFragment
        } else {
            currentFragment = supportFragmentManager.findFragmentByTag("viewer") ?: viewerFragment
        }

        setupNavigation()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (supportFragmentManager.findFragmentByTag("nesting") != null) {
                    if (fileBrowserFragment.isVisible && fileBrowserFragment.nestingFileSelectedListener != null) {
                        fileBrowserFragment.nestingFileSelectedListener = null
                        returnFromNestingFileBrowser()
                    } else {
                        closeNesting()
                    }
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // فتح من خارج التطبيق
        val fileUri = intent?.data
        if (fileUri != null) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                viewerFragment.loadFile(fileUri)
            }, 400)
        }
    }

    private fun applyThemeToNav() {
        val theme = AppTheme.getCurrent(this)
        val states = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val colors = intArrayOf(theme.accent, 0xFF888888.toInt())
        val csl = android.content.res.ColorStateList(states, colors)
        bottomNav.itemIconTintList = csl
        bottomNav.itemTextColor = csl
    }

    private fun initSound() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build()
        soundPool.setOnLoadCompleteListener { _, _, status -> soundLoaded = (status == 0) }
        navSoundId = soundPool.load(this, R.raw.nav_click, 1)
    }

    private fun playNavSound() {
        val soundEnabled = getSharedPreferences("amr3d_prefs", Context.MODE_PRIVATE)
            .getBoolean("sound_enabled", true)
        if (soundLoaded && soundEnabled) soundPool.play(navSoundId, 0.6f, 0.6f, 1, 0, 1.0f)
    }

    private fun setupNavigation() {
        // ربط listeners مرة واحدة
        fileBrowserFragment.fileSelectedListener =
            object : FileBrowserFragment.OnFileSelectedListener {
                override fun onFileSelected(file: File) {
                    HistoryFragment.addToHistory(this@MainActivity, file.absolutePath)
                    viewerFragment.loadFile(Uri.fromFile(file))
                    bottomNav.selectedItemId = R.id.nav_viewer
                }
            }
        historyFragment.fileSelectedListener =
            object : HistoryFragment.OnFileSelectedListener {
                override fun onFileSelected(file: File) {
                    viewerFragment.loadFile(Uri.fromFile(file))
                    bottomNav.selectedItemId = R.id.nav_viewer
                }
            }

        bottomNav.setOnItemSelectedListener { item ->
            val newIndex = navOrder.indexOf(item.itemId)
            if (newIndex == currentNavIndex) return@setOnItemSelectedListener false

            playNavSound()

            val targetFragment = when (item.itemId) {
                R.id.nav_viewer   -> viewerFragment
                R.id.nav_files    -> fileBrowserFragment
                R.id.nav_slicer   -> slicerFragment
                R.id.nav_history  -> historyFragment
                R.id.nav_settings -> settingsFragment
                else              -> viewerFragment
            }

            val goingRight = newIndex > currentNavIndex
            val animEnabled = getSharedPreferences("amr3d_prefs", Context.MODE_PRIVATE)
                .getBoolean("anim_enabled", true)
            val enterAnim = if (goingRight) R.anim.slide_in_right else R.anim.slide_in_left
            val exitAnim  = if (goingRight) R.anim.slide_out_left else R.anim.slide_out_right

            // hide/show — لا replace — النموذج يبقى في الذاكرة
            val navTx = supportFragmentManager.beginTransaction()
            if (animEnabled) navTx.setCustomAnimations(enterAnim, exitAnim)
            currentFragment?.let { navTx.hide(it) }
            navTx.show(targetFragment)
            navTx.commitAllowingStateLoss()

            currentFragment = targetFragment
            currentNavIndex = newIndex
            true
        }
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        intent?.data?.let { uri ->
            viewerFragment.loadFile(uri)
            bottomNav.selectedItemId = R.id.nav_viewer
        }
    }

    /**
     * يفتح شاشة Nesting فوق الـ Viewer/Slicer بدون إنشاء نسخة جديدة من ملف DXF.
     * الـ DxfModel الحالي موجود بالفعل في NestingSession.
     */
    fun openNesting() {
        val fm = supportFragmentManager
        val existing = fm.findFragmentByTag("nesting")
        val nesting = existing ?: NestingFragment.newInstance()
        val tx = fm.beginTransaction()
        if (existing == null) {
            tx.add(R.id.fragmentContainer, nesting, "nesting")
        } else {
            tx.show(nesting)
        }
        currentFragment?.let { tx.hide(it) }
        tx.commitNowAllowingStateLoss()
        bottomNav.visibility = View.GONE
    }

    /** Opens the app's own file browser for a one-shot Nesting file selection. */
    fun openNestingFileBrowser(listener: FileBrowserFragment.OnFileSelectedListener) {
        fileBrowserFragment.nestingFileSelectedListener = listener
        val fm = supportFragmentManager
        val nesting = fm.findFragmentByTag("nesting")
        val tx = fm.beginTransaction()
        nesting?.let { tx.hide(it) }
        currentFragment?.let { tx.hide(it) }
        tx.show(fileBrowserFragment)
        tx.commitNowAllowingStateLoss()
        bottomNav.visibility = View.GONE
    }

    fun returnFromNestingFileBrowser() {
        val fm = supportFragmentManager
        val nesting = fm.findFragmentByTag("nesting")
        val tx = fm.beginTransaction()
        nesting?.let { tx.show(it) }
        tx.hide(fileBrowserFragment)
        tx.commitNowAllowingStateLoss()
        bottomNav.visibility = View.GONE
    }

    fun closeNesting() {
        val fm = supportFragmentManager
        val nesting = fm.findFragmentByTag("nesting")
        val tx = fm.beginTransaction()
        nesting?.let { tx.remove(it) }
        currentFragment?.let { tx.show(it) }
        tx.commitNowAllowingStateLoss()
        NestingSession.clear()
        bottomNav.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        soundPool.release()
    }

    companion object {
        fun getUserName(context: Context): String =
            context.getSharedPreferences("amr3d_prefs", Context.MODE_PRIVATE)
                .getString("user_name", "") ?: ""

        fun saveUserName(context: Context, name: String) =
            context.getSharedPreferences("amr3d_prefs", Context.MODE_PRIVATE)
                .edit().putString("user_name", name).apply()
    }
}
