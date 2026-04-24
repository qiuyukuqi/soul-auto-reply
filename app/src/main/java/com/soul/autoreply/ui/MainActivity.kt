package com.soul.autoreply.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.soul.autoreply.R
import com.soul.autoreply.api.AutoReplyApi
import com.soul.autoreply.databinding.ActivityMainBinding
import com.soul.autoreply.service.MonitorService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("soul_reply_prefs", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUI()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun setupUI() {
        binding.btnToggleService.setOnClickListener {
            if (!isAccessibilityEnabled()) {
                showToast("请先开启无障碍权限")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            toggleService()
        }

        binding.btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnOpenSoul.setOnClickListener {
            try {
                val pkg = prefs.getString("soul_package", "com.soulskill.app")
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    startActivity(intent)
                } else {
                    showToast("未找到Soul App，尝试手动打开")
                    val webIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("soul://"))
                    if (webIntent.resolveActivity(packageManager) != null) {
                        startActivity(webIntent)
                    }
                }
            } catch (e: Exception) {
                showToast("未找到Soul App")
            }
        }

        binding.btnConfig.setOnClickListener {
            showConfigDialog()
        }

        binding.btnTest.setOnClickListener {
            if (!AutoReplyApi.hasApiKey(this)) {
                showToast("请先配置API Key")
                showConfigDialog()
                return@setOnClickListener
            }
            binding.btnTest.isEnabled = false
            binding.btnTest.text = "测试中..."
            AutoReplyApi.sendReply(this, "你好，介绍一下你自己") { reply ->
                Handler(Looper.getMainLooper()).post {
                    binding.btnTest.isEnabled = true
                    binding.btnTest.text = "测试回复"
                    showDialog("AI 回复", reply)
                }
            }
        }
    }

    private fun toggleService() {
        val enabled = prefs.getBoolean("service_enabled", false)
        if (!enabled) {
            if (!isAccessibilityEnabled()) {
                showToast("请先开启无障碍权限")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return
            }
            val serviceIntent = Intent(this, MonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            prefs.edit().putBoolean("service_enabled", true).apply()
            binding.btnToggleService.text = "关闭自动回复"
            showToast("自动回复已开启")
        } else {
            stopService(Intent(this, MonitorService::class.java))
            prefs.edit().putBoolean("service_enabled", false).apply()
            binding.btnToggleService.text = "开启自动回复"
            showToast("自动回复已关闭")
        }
        updateStatus()
    }

    private fun updateStatus() {
        val accessibilityEnabled = isAccessibilityEnabled()
        val serviceEnabled = prefs.getBoolean("service_enabled", false)
        val hasApiKey = AutoReplyApi.hasApiKey(this)
        val model = AutoReplyApi.getCurrentModel(this)

        binding.tvAccessibilityStatus.text = if (accessibilityEnabled) "✅ 已开启" else "❌ 未开启"
        binding.tvServiceStatus.text = if (serviceEnabled) "✅ 运行中" else "⏸️ 已停止"
        binding.tvApiKeyStatus.text = if (hasApiKey) "✅ ${model.displayName}" else "❌ 未配置"
        binding.btnToggleService.text = if (serviceEnabled) "关闭自动回复" else "开启自动回复"
        binding.btnToggleService.isEnabled = accessibilityEnabled
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains("com.soul.autoreply/com.soul.autoreply.service.SoulAccessibilityService") == true
    }

    private fun showConfigDialog() {
        val currentModel = AutoReplyApi.getCurrentModel(this)
        val currentKey = AutoReplyApi.getApiKey(this)
        val currentGroupId = AutoReplyApi.getGroupId(this)
        val selectedIndex = AutoReplyApi.ALL_MODELS.indexOfFirst { it.id == currentModel.id }.takeIf { it >= 0 } ?: 0

        val modelNames = AutoReplyApi.ALL_MODELS.map { it.displayName }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(56, 32, 56, 0)
        }

        // API Key
        val keyLabel = android.widget.TextView(this).apply {
            text = "API Key"
            setTextColor(0xFF888888.toInt())
            textSize = 12f
        }
        val keyInput = android.widget.EditText(this).apply {
            hint = "输入你的 API Key"
            setText(currentKey)
            setSingleLine()
        }
        container.addView(keyLabel)
        container.addView(keyInput)

        // Model selection
        val modelLabel = android.widget.TextView(this).apply {
            text = "选择模型"
            setTextColor(0xFF888888.toInt())
            textSize = 12f
            setPadding(0, 24, 0, 0)
        }
        val spinner = android.widget.Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modelNames)
        spinner.setSelection(selectedIndex)
        container.addView(modelLabel)
        container.addView(spinner)

        // GroupId (for Minimax)
        val groupIdLabel = android.widget.TextView(this).apply {
            text = "Group ID（Minimax模型专用，其他模型可留空）"
            setTextColor(0xFF888888.toInt())
            textSize = 12f
            setPadding(0, 24, 0, 0)
        }
        val groupIdInput = android.widget.EditText(this).apply {
            hint = "Minimax Group ID"
            setText(currentGroupId)
            setSingleLine()
        }
        container.addView(groupIdLabel)
        container.addView(groupIdInput)

        // API URL hint
        val urlHint = android.widget.TextView(this).apply {
            text = "API: ${currentModel.baseUrl}"
            setTextColor(0xFF555555.toInt())
            textSize = 10f
            setPadding(0, 8, 0, 0)
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val model = AutoReplyApi.ALL_MODELS[position]
                urlHint.text = "API: ${model.baseUrl}"
                // Show/hide GroupId based on model type
                val isMinimax = model.authType == AutoReplyApi.AuthType.MINIMAX
                groupIdLabel.visibility = if (isMinimax) View.VISIBLE else View.GONE
                groupIdInput.visibility = if (isMinimax) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        container.addView(urlHint)

        AlertDialog.Builder(this)
            .setTitle("配置 API")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val key = keyInput.text.toString().trim()
                val groupId = groupIdInput.text.toString().trim()
                val model = AutoReplyApi.ALL_MODELS[spinner.selectedItemPosition]

                if (key.isEmpty()) {
                    showToast("API Key 不能为空")
                    return@setPositiveButton
                }

                AutoReplyApi.saveApiKey(this, key)
                AutoReplyApi.saveModel(this, model.id)
                if (groupId.isNotBlank()) {
                    AutoReplyApi.saveGroupId(this, groupId)
                }

                showToast("已保存: ${model.displayName}")
                updateStatus()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showDialog(title: String, msg: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("确定", null)
            .show()
    }
}
