package com.example.junkcleaner

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Blue = Color(0xFF2F80ED)
private val Gray = Color(0xFF8A94A6)
private val CardBg = Color(0xFFF5F7FA)
private val Ink = Color(0xFF1F2937)

class MainActivity : ComponentActivity() {
    private val perm = mutableStateOf(false)
    private val legacyLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perm.value = hasStoragePermission(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        perm.value = hasStoragePermission(this)
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Blue, background = Color.White, surface = Color.White
                )
            ) {
                App(perm.value, ::requestPerm, version)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        perm.value = hasStoragePermission(this)
    }

    private fun requestPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val i = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            try { startActivity(i) } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            legacyLauncher.launch(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            )
        }
    }
}

private enum class Phase { IDLE, SCANNING, RESULT }

@Composable
fun App(hasPerm: Boolean, onRequestPerm: () -> Unit, version: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(Phase.IDLE) }
    val items = remember { mutableStateListOf<JunkItem>() }
    var scanned by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    val collapsed = remember { mutableStateListOf<Category>() }

    fun startScan() {
        phase = Phase.SCANNING
        items.clear()
        scanned = 0
        scope.launch {
            val r = JunkScanner.scan(ctx) { scanned = it }
            items.addAll(r)
            phase = Phase.RESULT
        }
    }

    // 授权后自动开始检测
    LaunchedEffect(hasPerm) { if (hasPerm && phase == Phase.IDLE) startScan() }

    val selected = items.filter { it.selected }
    val totalSize = items.sumOf { it.size }
    val selectedSize = selected.sumOf { it.size }

    Column(Modifier.fillMaxSize().background(Color.White).padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("垃圾清理", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.weight(1f))
            Text("v$version", fontSize = 13.sp, color = Gray)
        }
        Spacer(Modifier.height(16.dp))

        when {
            !hasPerm -> Center {
                Text("需要“所有文件访问”权限", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Ink)
                Spacer(Modifier.height(8.dp))
                Text("仅用于查找并清理垃圾文件，\n不会扫描系统文件。", color = Gray, fontSize = 14.sp)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onRequestPerm, shape = RoundedCornerShape(24.dp)) { Text("授予权限") }
            }

            phase == Phase.SCANNING -> Center {
                CircularProgressIndicator(color = Blue)
                Spacer(Modifier.height(20.dp))
                Text("正在检测垃圾…", fontSize = 16.sp, color = Ink)
                Spacer(Modifier.height(4.dp))
                Text("已检查 $scanned 个文件", fontSize = 13.sp, color = Gray)
            }

            else -> {
                // 汇总卡片
                Column(
                    Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(20.dp)).padding(20.dp)
                ) {
                    Text("共发现垃圾", fontSize = 13.sp, color = Gray)
                    Text(formatSize(totalSize), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Blue)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "已选择 ${selected.size} 项 · ${formatSize(selectedSize)}　（共 ${items.size} 项）",
                        fontSize = 13.sp, color = Gray
                    )
                    message?.let {
                        Spacer(Modifier.height(6.dp))
                        Text("✓ $it", fontSize = 13.sp, color = Color(0xFF27AE60))
                    }
                }
                Spacer(Modifier.height(8.dp))

                if (items.isEmpty()) {
                    Center { Text("手机很干净，没有发现垃圾 ✨", color = Gray) }
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        Category.values().forEach { cat ->
                            val list = items.filter { it.category == cat }
                            if (list.isEmpty()) return@forEach
                            item(key = "h_${cat.name}") {
                                val state = when {
                                    list.all { it.selected } -> ToggleableState.On
                                    list.none { it.selected } -> ToggleableState.Off
                                    else -> ToggleableState.Indeterminate
                                }
                                val open = cat !in collapsed
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { if (open) collapsed.add(cat) else collapsed.remove(cat) }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TriStateCheckbox(state = state, onClick = {
                                        val to = state != ToggleableState.On
                                        list.forEach { it.selected = to }
                                    })
                                    Column(Modifier.weight(1f)) {
                                        Text(cat.label, fontWeight = FontWeight.Medium, color = Ink)
                                        Text("${list.size} 项", fontSize = 12.sp, color = Gray)
                                    }
                                    Text(formatSize(list.sumOf { it.size }), color = Ink, fontSize = 14.sp)
                                    Text(if (open) "  ▾" else "  ▸", color = Gray)
                                }
                            }
                            if (cat !in collapsed) {
                                items(list, key = { it.file.absolutePath }) { it ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(start = 24.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(checked = it.selected, onCheckedChange = { c -> it.selected = c })
                                        Column(Modifier.weight(1f)) {
                                            Text(it.file.name, fontSize = 14.sp, color = Ink, maxLines = 1,
                                                overflow = TextOverflow.Ellipsis)
                                            Text(it.file.parent ?: "", fontSize = 11.sp, color = Gray, maxLines = 1,
                                                overflow = TextOverflow.Ellipsis)
                                        }
                                        Text(formatSize(it.size), fontSize = 13.sp, color = Gray)
                                    }
                                }
                            }
                            item(key = "d_${cat.name}") { HorizontalDivider(color = CardBg) }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = { message = null; startScan() },
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.height(48.dp)
                    ) { Text("重新检测") }
                    Button(
                        onClick = { confirm = true },
                        enabled = selected.isNotEmpty(),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) { Text("清理所选 (${formatSize(selectedSize)})") }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Color.White,
            title = { Text("确认删除？") },
            text = { Text("将删除 ${selected.size} 项，共 ${formatSize(selectedSize)}。\n删除后无法恢复，未勾选的项目不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch {
                        val targets = items.filter { it.selected }
                        val (done, freed) = withContext(Dispatchers.IO) {
                            var f = 0L
                            val ok = ArrayList<JunkItem>()
                            targets.forEach { if (JunkScanner.delete(it)) { f += it.size; ok += it } }
                            ok to f
                        }
                        items.removeAll(done.toSet())
                        message = "已清理 ${done.size} 项，释放 ${formatSize(freed)}"
                    }
                }) { Text("删除", color = Color(0xFFEB5757)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun ColumnScope.Center(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        content = content
    )
}
