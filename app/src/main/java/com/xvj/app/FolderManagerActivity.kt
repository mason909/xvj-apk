package com.xvj.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.xvj.app.databinding.ActivityFolderManagerBinding
import java.io.File

class FolderManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFolderManagerBinding
    private val prefs by lazy { getSharedPreferences("xvj_prefs", MODE_PRIVATE) }
    private val customFolders = mutableListOf<VideoFolder>()
    private lateinit var adapter: FolderAdapter

    companion object {
        private const val REQUEST_PERMISSION = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()
        
        binding = ActivityFolderManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        checkPermissions()
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

    private fun setupRecyclerView() {
        adapter = FolderAdapter(customFolders) { folder, position ->
            // Delete folder
            AlertDialog.Builder(this)
                .setTitle("删除文件夹")
                .setMessage("确定要删除 \"${folder.name}\" 吗？")
                .setPositiveButton("删除") { _, _ ->
                    customFolders.removeAt(position)
                    saveFolders()
                    adapter.notifyDataSetChanged()
                    updateEmptyState()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    private fun setupListeners() {
        // Add folder button
        binding.btnAddFolder.setOnClickListener {
            showFolderPicker()
        }

        // Scan folders button
        binding.btnScanFolders.setOnClickListener {
            scanAvailableFolders()
        }

        // Back button
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Clear all
        binding.btnClearAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空文件夹")
                .setMessage("确定要清空所有自定义文件夹吗？")
                .setPositiveButton("清空") { _, _ ->
                    customFolders.clear()
                    saveFolders()
                    adapter.notifyDataSetChanged()
                    updateEmptyState()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun checkPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val notGranted = permissions.filter { 
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
        }

        if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), REQUEST_PERMISSION)
        } else {
            loadFolders()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadFolders()
            } else {
                Toast.makeText(this, "需要存储权限才能管理文件夹", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun loadFolders() {
        customFolders.clear()
        val savedFolders = prefs.getStringSet("custom_folders", null)
        if (savedFolders != null) {
            savedFolders.forEach { folderPath ->
                val file = File(folderPath)
                if (file.exists() && file.isDirectory) {
                    val videoCount = countVideos(file)
                    customFolders.add(VideoFolder(file.name, folderPath, videoCount))
                }
            }
        }
        
        adapter.notifyDataSetChanged()
        updateEmptyState()
    }

    private fun countVideos(dir: File): Int {
        return dir.listFiles()?.count { 
            it.isFile && (it.extension.lowercase() in listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm"))
        } ?: 0
    }

    private fun saveFolders() {
        val folderPaths = customFolders.map { it.path }.toSet()
        prefs.edit().putStringSet("custom_folders", folderPaths).apply()
    }

    private fun updateEmptyState() {
        binding.tvEmptyState.visibility = if (customFolders.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerView.visibility = if (customFolders.isEmpty()) View.GONE else View.VISIBLE
        binding.btnClearAll.isEnabled = customFolders.isNotEmpty()
    }

    private fun showFolderPicker() {
        val rootPath = Environment.getExternalStorageDirectory().absolutePath
        val rootDir = File(rootPath)
        
        val items = mutableListOf<String>()
        val paths = mutableListOf<String>()
        
        // Add common directories
        val commonDirs = listOf(
            "DCIM" to "$rootPath/DCIM",
            "Movies" to "$rootPath/Movies",
            "Video" to "$rootPath/Video",
            "Download" to "$rootPath/Download",
            "Documents" to "$rootPath/Documents"
        )
        
        commonDirs.forEach { (name, path) ->
            val dir = File(path)
            if (dir.exists() && dir.isDirectory) {
                items.add(name)
                paths.add(path)
            }
        }
        
        // Add all subdirectories with videos
        rootDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { dir ->
            val videos = countVideos(dir)
            if (videos > 0) {
                items.add("${dir.name} ($videos 视频)")
                paths.add(dir.absolutePath)
            }
        }
        
        if (items.isEmpty()) {
            Toast.makeText(this, "未找到视频文件夹", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("选择文件夹")
            .setItems(items.toTypedArray()) { _, which ->
                val selectedPath = paths[which]
                
                // Check if already added
                if (customFolders.any { it.path == selectedPath }) {
                    Toast.makeText(this, "文件夹已添加", Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                
                val dir = File(selectedPath)
                val videoCount = countVideos(dir)
                customFolders.add(VideoFolder(dir.name, selectedPath, videoCount))
                saveFolders()
                adapter.notifyDataSetChanged()
                updateEmptyState()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun scanAvailableFolders() {
        val rootPath = Environment.getExternalStorageDirectory().absolutePath
        val rootDir = File(rootPath)
        val availableFolders = mutableListOf<Pair<String, String>>() // name to path
        
        // Scan for video folders
        if (rootDir.exists() && rootDir.isDirectory) {
            scanForVideoFolders(rootDir, availableFolders, 2)
        }
        
        if (availableFolders.isEmpty()) {
            Toast.makeText(this, "未找到视频文件夹", Toast.LENGTH_SHORT).show()
            return
        }
        
        val items = availableFolders.map { "${it.first} (${countVideos(File(it.second))} 视频)" }.toTypedArray()
        val paths = availableFolders.map { it.second }
        
        AlertDialog.Builder(this)
            .setTitle("扫描到的文件夹")
            .setItems(items) { _, which ->
                val selectedPath = paths[which]
                
                if (customFolders.any { it.path == selectedPath }) {
                    Toast.makeText(this, "文件夹已添加", Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                
                val dir = File(selectedPath)
                val videoCount = countVideos(dir)
                customFolders.add(VideoFolder(dir.name, selectedPath, videoCount))
                saveFolders()
                adapter.notifyDataSetChanged()
                updateEmptyState()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun scanForVideoFolders(dir: File, result: MutableList<Pair<String, String>>, maxDepth: Int) {
        if (maxDepth <= 0) return
        
        val videos = dir.listFiles()?.count {
            it.isFile && (it.extension.lowercase() in listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm"))
        } ?: 0
        
        if (videos > 0) {
            result.add(dir.name to dir.absolutePath)
        }
        
        dir.listFiles()?.filter { 
            it.isDirectory && !it.name.startsWith(".") && !it.name.contains("Android")
        }?.forEach { subDir ->
            scanForVideoFolders(subDir, result, maxDepth - 1)
        }
    }

    data class VideoFolder(val name: String, val path: String, val videoCount: Int = 0)
}

// Folder adapter
class FolderAdapter(
    private val folders: MutableList<FolderManagerActivity.VideoFolder>,
    private val onDeleteClick: (FolderManagerActivity.VideoFolder, Int) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<FolderAdapter.ViewHolder>() {

    class ViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
        val tvName: android.widget.TextView = view.findViewById(R.id.tvFolderName)
        val tvPath: android.widget.TextView = view.findViewById(R.id.tvFolderPath)
        val tvCount: android.widget.TextView = view.findViewById(R.id.tvVideoCount)
        val btnDelete: android.widget.Button = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_folder, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val folder = folders[position]
        holder.tvName.text = folder.name
        holder.tvPath.text = folder.path
        holder.tvCount.text = "${folder.videoCount} 个视频"
        holder.btnDelete.setOnClickListener { onDeleteClick(folder, position) }
    }

    override fun getItemCount() = folders.size
}
