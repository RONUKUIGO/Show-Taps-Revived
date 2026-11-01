package show.taps

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.skydoves.colorpickerview.ColorPickerDialog
import com.skydoves.colorpickerview.listeners.ColorEnvelopeListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import show.taps.databinding.ActivityMainBinding
import show.taps.databinding.ViewColorItemBinding
import show.taps.overlay.GlobalSettings
import show.taps.overlay.OverlayController
import show.taps.overlay.OverlayService
import show.taps.touch.RootTouchSource
import show.taps.touch.ShizukuTouchSource

private const val TAG = "MainActivity"

private const val REQ_CODE_SHIZUKU = 1001

private const val PKG_SHIZUKU = "moe.shizuku.privileged.api"

/**
 * 启动流程（Android 16 版）：
 *   1. 覆盖层由 App 自身进程绘制 → 需要「显示在其他应用上层」权限
 *   2. 读 /dev/input 需要提权 → 优先 Shizuku（免 root），否则 su/root 直启
 */
class MainActivity : AppCompatActivity(), Shizuku.OnRequestPermissionResultListener {

    private lateinit var binding: ActivityMainBinding

    private var safeDialog: AlertDialog? = null

    @Volatile
    private var rootAvailable = false

    @Volatile
    private var rootChecked = false

    /* ---------------- 生命周期 ---------------- */

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.MANUFACTURER.uppercase() == "HUAWEI" || Build.MANUFACTURER.uppercase() == "HONOR") {
            MaterialAlertDialogBuilder(this)
                .setTitle("Tip")
                .setMessage(getString(R.string.tip_huawei_compat))
                .setPositiveButton("OK") { dialog, _ -> dialog.cancel() }
                .show()
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyEdgeToEdge()

        binding.initPrefPanel()
        binding.initLink()
        binding.initColor()

        binding.layout1.btStart.setOnClickListener { onPrimaryClicked() }
        binding.layout1.btStep1.setOnClickListener { onStepClicked() }

        Shizuku.addRequestPermissionResultListener(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                App.running.collect { running ->
                    binding.layout1.btStart.text =
                        getString(if (running) R.string.bt_stop else R.string.bt_start)
                    if (running) showSafeDialog() else dismissSafeDialog()
                }
            }
        }

        // root 检测只做一次（会 fork su，可能弹 KernelSU 授权框），不要反复轮询
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (!rootChecked) {
                    withContext(Dispatchers.IO) {
                        rootAvailable = RootTouchSource.isAvailable()
                    }
                    rootChecked = true
                }
                refreshStepCard()
                // 只轮询 Shizuku 的状态（纯 binder 查询，无副作用）
                while (true) {
                    delay(1500)
                    refreshStepCard()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStepCard()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(this)
        dismissSafeDialog()
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        // 用用户实际按下的设备名作为下次启动的首选输入设备
        setLastDevName(ev?.device?.name)
        return super.dispatchTouchEvent(ev)
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        if (requestCode == REQ_CODE_SHIZUKU) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                toast(R.string.toast_shizuku_granted)
            } else {
                toast(R.string.open_shizuku_grant)
            }
            refreshStepCard()
        }
    }

    /* ---------------- 启动 / 停止 ---------------- */

    private fun onPrimaryClicked() {
        if (App.running.value) {
            OverlayService.stop(this)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        if (!ShizukuTouchSource.isReady() && !rootAvailable) {
            // 可能是刚才 KernelSU 还没授权，这里重测一次
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    RootTouchSource.resetAvailability()
                    rootAvailable = RootTouchSource.isAvailable()
                }
                rootChecked = true
                refreshStepCard()
                if (!rootAvailable && !ShizukuTouchSource.isReady()) {
                    toast(R.string.toast_no_privileged_channel)
                }
            }
            return
        }
        OverlayService.start(this, getLastDevName())
    }

    private fun onStepClicked() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        if (shizukuRunningButNotGranted()) {
            try {
                Shizuku.requestPermission(REQ_CODE_SHIZUKU)
            } catch (t: Throwable) {
                Log.e(TAG, "requestPermission failed", t)
            }
            return
        }
        openShizukuOrDownload()
    }

    private fun refreshStepCard() {
        val layout1 = binding.layout1
        val overlayOk = Settings.canDrawOverlays(this)
        val shizukuReady = ShizukuTouchSource.isReady()
        val channelOk = shizukuReady || rootAvailable

        if (!overlayOk || !channelOk) {
            layout1.tvStep1.visibility = View.VISIBLE
            layout1.btStep1.visibility = View.VISIBLE
            layout1.tvStartTip.visibility = View.GONE
            layout1.btStart.visibility = View.GONE

            when {
                !overlayOk -> {
                    layout1.tvStep1.setText(R.string.card_1_tip_overlay)
                    layout1.btStep1.setText(R.string.bt_request_overlay_permission)
                }

                shizukuRunningButNotGranted() -> {
                    layout1.tvStep1.setText(R.string.card_1_tip_shizuku_permission)
                    layout1.btStep1.setText(R.string.bt_request_shizuku_permission)
                }

                else -> {
                    layout1.tvStep1.setText(R.string.card_1_tip_install_shizuku)
                    layout1.btStep1.setText(R.string.card_1_open_shizuku)
                }
            }
        } else {
            layout1.tvStep1.visibility = View.GONE
            layout1.btStep1.visibility = View.GONE
            layout1.tvStartTip.visibility = View.VISIBLE
            layout1.btStart.visibility = View.VISIBLE
            layout1.tvStartTip.setText(
                if (rootAvailable && !shizukuReady) R.string.card_1_tip_start_root
                else R.string.card_1_tip_start
            )
        }
    }

    private fun shizukuReady(): Boolean = ShizukuTouchSource.isReady()

    private fun shizukuRunningButNotGranted(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    private fun requestOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        try {
            startActivity(intent)
            toast(R.string.toast_grant_overlay_then_back)
        } catch (t: Throwable) {
            Log.e(TAG, "open overlay settings failed", t)
            toast(R.string.toast_launch_fail)
        }
    }

    private fun openShizukuOrDownload() {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(PKG_SHIZUKU)
        val list = packageManager.queryIntentActivities(intent, 0)
        if (list.isEmpty()) {
            openWebBrowser("https://shizuku.rikka.app/")
            return
        }
        val activityName = list[0].activityInfo.name
        try {
            startActivity(
                Intent()
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .setComponent(ComponentName(PKG_SHIZUKU, activityName))
            )
        } catch (t: Throwable) {
            Log.e(TAG, "open shizuku failed", t)
        }
    }

    /* ---------------- 安全提示 ---------------- */

    private fun showSafeDialog() {
        if (safeDialog?.isShowing == true) return
        safeDialog = MaterialAlertDialogBuilder(this)
            .setMessage(R.string.tip_continue)
            .setCancelable(false)
            .setPositiveButton(getString(R.string._continue)) { dialog, _ ->
                OverlayService.confirm()
                dialog.dismiss()
            }
            .show()
    }

    private fun dismissSafeDialog() {
        safeDialog?.let { runCatching { it.dismiss() } }
        safeDialog = null
    }

    /* ---------------- Android 16：强制 edge-to-edge ---------------- */

    private fun applyEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    /* ---------------- 设置面板 ---------------- */

    private fun ActivityMainBinding.initPrefPanel() {

        fun updateRemoteInfo() {
            // 参数存在 App 进程的 GlobalSettings 里，绘制时直接读取，无需跨进程同步
            GlobalSettings.touchPointSize = getTouchPointSize()
            GlobalSettings.pathFadeTime = getTouchPathDisappearanceTime()
            GlobalSettings.strokeCircle = getStrokeWidthCircle()
            GlobalSettings.strokeLine = getStrokeWidthLine()
            GlobalSettings.colorAlpha = getAlpha()
            App.colorList.copyInto(GlobalSettings.colorArray)
            OverlayController.current?.alpha = GlobalSettings.colorAlpha / 100f
        }

        layout2.tvTouchDisappearanceTime.text = getString(R.string.tv_touch_line_time, 0)
        layout2.seekbarFadeTime.setOnSeekBarChangeListener(OnSeekBarChangeListener { progress, fromUser ->
            layout2.tvTouchDisappearanceTime.text = getString(R.string.tv_touch_line_time, progress)
            if (fromUser) {
                setTouchPathDisappearanceTime(progress)
                updateRemoteInfo()
            }
        })

        layout2.tvTouchPointSize.text = getString(R.string.tv_touch_point_size, 0)
        layout2.seekbarStokeSize.setOnSeekBarChangeListener(OnSeekBarChangeListener { progress, fromUser ->
            layout2.tvTouchPointSize.text = getString(R.string.tv_touch_point_size, progress)
            if (fromUser) {
                setTouchPointSize(progress)
                updateRemoteInfo()
            }
        })

        layout2.tvCircleStroke.text = getString(R.string.tv_touch_point_stroke, 0)
        layout2.seekbarCircleStroke.setOnSeekBarChangeListener(OnSeekBarChangeListener { progress, fromUser ->
            layout2.tvCircleStroke.text = getString(R.string.tv_touch_point_stroke, progress)
            if (fromUser) {
                setStrokeCircle(progress)
                updateRemoteInfo()
            }
        })

        layout2.tvLineStroke.text = getString(R.string.tv_touch_line_stroke, 0)
        layout2.seekbarLineStroke.setOnSeekBarChangeListener(OnSeekBarChangeListener { progress, fromUser ->
            layout2.tvLineStroke.text = getString(R.string.tv_touch_line_stroke, progress)
            if (fromUser) {
                setStrokeLine(progress)
                updateRemoteInfo()
            }
        })

        layout2.tvColorAlpha.text = getString(R.string.tv_touch_alpha, 30)
        layout2.seekbarColorAlpha.setOnSeekBarChangeListener(OnSeekBarChangeListener { progress, fromUser ->
            layout2.tvColorAlpha.text = getString(R.string.tv_touch_alpha, progress)
            if (fromUser) {
                setAlpha(progress)
                updateRemoteInfo()
            }
        })

        layout2.seekbarFadeTime.progress = getTouchPathDisappearanceTime()
        layout2.seekbarStokeSize.progress = getTouchPointSize()
        layout2.seekbarCircleStroke.progress = getStrokeWidthCircle()
        layout2.seekbarLineStroke.progress = getStrokeWidthLine()
        layout2.seekbarColorAlpha.progress = getAlpha()

        // 初始化绘制参数
        GlobalSettings.touchPointSize = getTouchPointSize()
        GlobalSettings.pathFadeTime = getTouchPathDisappearanceTime()
        GlobalSettings.strokeCircle = getStrokeWidthCircle()
        GlobalSettings.strokeLine = getStrokeWidthLine()
        updateRemoteInfo()
    }

    private fun ActivityMainBinding.initLink() {
        layout3.linkPlay.setOnClickListener {
            openGooglePlayStore()
        }
        layout3.linkPlay.setOnLongClickListener {
            copy("https://play.google.com/store/apps/details?id=$packageName")
            toast(R.string.toast_link_copied)
            true
        }

        layout3.linkGithub.setOnClickListener {
            openWebBrowser("https://github.com/k3x1n/Android-Show-Taps")
        }
        layout3.linkGithub.setOnLongClickListener {
            copy("https://github.com/k3x1n/Android-Show-Taps")
            toast(R.string.toast_link_copied)
            true
        }

        @Suppress("SetTextI18n")
        layout3.tvVersion.text = "version ${BuildConfig.VERSION_NAME}"
    }

    private fun ActivityMainBinding.initColor() {

        fun saveColor(i: Int, color: Int) =
            pref().edit().putInt("$keyColorPrefix$i", color).apply()

        fun applyColor() {
            App.colorList.copyInto(GlobalSettings.colorArray)
        }

        applyColor()

        for (i in 0 until GlobalSettings.COLOR_ARRAY_LENGTH) {
            val itemBinding = ViewColorItemBinding.inflate(layoutInflater, layout2.colorList, false)
            itemBinding.imgColor.setImageDrawable(ColorDrawable(App.colorList[i]))
            itemBinding.root.setOnClickListener {
                ColorPickerDialog.Builder(this@MainActivity)
                    .setPositiveButton("OK", ColorEnvelopeListener { envelope, fromUser ->
                        if (fromUser) {
                            App.colorList[i] = envelope.color
                            saveColor(i, App.colorList[i])
                            applyColor()
                            itemBinding.imgColor.setImageDrawable(ColorDrawable(envelope.color))
                        }
                    })
                    .setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
                    .setTitle(getString(R.string.tv_title_color_picker, i + 1))
                    .attachAlphaSlideBar(false)
                    .also {
                        it.colorPickerView.post {
                            runCatching { it.colorPickerView.selectByHsvColor(App.colorList[i]) }
                        }
                    }
                    .show()
            }
            layout2.colorList.addView(itemBinding.root)
        }
    }
}
