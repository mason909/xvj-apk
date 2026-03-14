package com.xvj.app

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.xvj.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { getSharedPreferences("xvj_prefs", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()
        
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSettings()
        setupListeners()
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
    }

    private fun loadSettings() {
        // Load settings
        val autoPlay = prefs.getBoolean("auto_play", true)
        binding.switchAutoPlay.isChecked = autoPlay

        val startOnBoot = prefs.getBoolean("start_on_boot", true)
        binding.switchStartOnBoot.isChecked = startOnBoot

        val keepScreenOn = prefs.getBoolean("keep_screen_on", true)
        binding.switchKeepScreenOn.isChecked = keepScreenOn

        val loopMode = prefs.getInt("loop_mode", 0)
        binding.tvLoopMode.text = when (loopMode) {
            0 -> "列表循环"
            1 -> "单曲循环"
            else -> "关闭循环"
        }

        val volume = prefs.getInt("volume", 100)
        binding.seekBarVolume.progress = volume
        binding.tvVolume.text = "音量: $volume%"

        val scanDepth = prefs.getInt("scan_depth", 2)
        binding.tvScanDepth.text = "扫描深度: $scanDepth"
    }

    private fun setupListeners() {
        // Auto play switch
        binding.switchAutoPlay.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_play", isChecked).apply()
        }

        // Start on boot switch
        binding.switchStartOnBoot.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("start_on_boot", isChecked).apply()
        }

        // Keep screen on switch
        binding.switchKeepScreenOn.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("keep_screen_on", isChecked).apply()
            if (isChecked) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        // Loop mode
        binding.layoutLoopMode.setOnClickListener {
            val modes = arrayOf("列表循环", "单曲循环", "关闭循环")
            var currentMode = prefs.getInt("loop_mode", 0)
            
            AlertDialog.Builder(this)
                .setTitle("循环模式")
                .setSingleChoiceItems(modes, currentMode) { dialog, which ->
                    prefs.edit().putInt("loop_mode", which).apply()
                    binding.tvLoopMode.text = modes[which]
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // Volume seekbar
        binding.seekBarVolume.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                binding.tvVolume.text = "音量: $progress%"
                prefs.edit().putInt("volume", progress).apply()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        // Scan depth
        binding.layoutScanDepth.setOnClickListener {
            val depths = arrayOf("1层", "2层", "3层", "4层", "5层")
            var currentDepth = prefs.getInt("scan_depth", 2)
            
            AlertDialog.Builder(this)
                .setTitle("扫描深度")
                .setSingleChoiceItems(depths, currentDepth - 1) { dialog, which ->
                    prefs.edit().putInt("scan_depth", which + 1).apply()
                    binding.tvScanDepth.text = depths[which]
                    dialog.dismiss()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // Clear folder cache
        binding.layoutClearCache.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清除缓存")
                .setMessage("确定要清除文件夹缓存吗？")
                .setPositiveButton("清除") { _, _ ->
                    prefs.edit().remove("custom_folders").apply()
                    prefs.edit().remove("current_folder").apply()
                    prefs.edit().remove("scan_depth").apply()
                    recreate()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        // Back button
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Reset all settings
        binding.layoutResetSettings.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("重置设置")
                .setMessage("确定要恢复默认设置吗？")
                .setPositiveButton("重置") { _, _ ->
                    prefs.edit().clear().apply()
                    recreate()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
    }
}
