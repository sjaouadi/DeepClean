package com.deepclean.app

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.format.Formatter
import android.widget.*
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val BG = Color.parseColor("#0F172A")
    private val CARD = Color.parseColor("#1E293B")
    private val GREEN = Color.parseColor("#16A34A")
    private val GREY = Color.parseColor("#94A3B8")
    private lateinit var stTxt: TextView
    private lateinit var ramTxt: TextView
    private lateinit var stBar: ProgressBar
    private lateinit var ramBar: ProgressBar
    private lateinit var info: TextView
    private val junkExt = setOf("tmp", "temp", "log", "bak", "old", "chk", "dmp", "thumbdata", "cache")

    private class Item(val f: File, val size: Long)
    private class Row(val name: String, val pkg: String, val total: Long, val cache: Long)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun fmt(b: Long) = Formatter.formatFileSize(this, b)

    private fun tv(t: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun bar() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progressTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
        progressBackgroundTintList = ColorStateList.valueOf(CARD)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })

        fun btn(t: String, c: Int, f: () -> Unit) {
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(12)
            root.addView(Button(this).apply {
                text = t; isAllCaps = false; textSize = 16f; setTextColor(Color.WHITE)
                background = GradientDrawable().apply { setColor(c); cornerRadius = dp(14).toFloat() }
                setPadding(dp(16), dp(14), dp(16), dp(14))
                setOnClickListener { f() }
            }, lp)
        }

        root.addView(tv("Deep Clean", 30f, Color.WHITE, true))
        root.addView(tv("Free up space and keep your phone fast", 14f, GREY, false))
        stTxt = tv("", 14f, Color.WHITE, false).also { it.setPadding(0, dp(22), 0, dp(6)); root.addView(it) }
        stBar = bar().also { root.addView(it) }
        ramTxt = tv("", 14f, Color.WHITE, false).also { it.setPadding(0, dp(16), 0, dp(6)); root.addView(it) }
        ramBar = bar().also { root.addView(it) }

        btn("1. Allow full file access", CARD) { askPermission() }
        btn("2. Allow app usage access (for app sizes)", CARD) { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        btn("Scan junk files", GREEN) { scanJunk() }
        btn("Find biggest files", GREEN) { scanBig() }
        btn("Apps using the most space", GREEN) { scanApps() }
        btn("Add RAM (Memory extension)", CARD) { ramHelp() }
        info = tv("", 14f, GREY, false).also { it.setPadding(0, dp(20), 0, 0); root.addView(it) }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val sf = StatFs(Environment.getDataDirectory().path)
        val total = sf.blockCountLong * sf.blockSizeLong
        val free = sf.availableBytesLong
        stTxt.text = "Storage: ${fmt(total - free)} used of ${fmt(total)}  (${fmt(free)} free)"
        stBar.progress = ((total - free) * 100 / total).toInt()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        ramTxt.text = "RAM: ${fmt(mi.availMem)} free of ${fmt(mi.totalMem)}"
        ramBar.progress = ((mi.totalMem - mi.availMem) * 100 / mi.totalMem).toInt()
    }

    private fun hasAccess() = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun askPermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } else requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
    }

    private fun installed(pkg: String) = try { packageManager.getPackageInfo(pkg, 0); true } catch (e: PackageManager.NameNotFoundException) { false }

    private fun sizeOf(f: File): Long = if (f.isFile) f.length() else f.listFiles()?.sumOf { sizeOf(it) } ?: 0L

    private fun scanJunk() {
        if (!hasAccess()) { Toast.makeText(this, "Tap button 1 and allow file access first", Toast.LENGTH_LONG).show(); return }
        info.text = "Scanning junk files..."
        thread {
            val cats = linkedMapOf(
                "Junk files (tmp, log, bak, thumbnails, empty files)" to ArrayList<Item>(),
                "Empty folders" to ArrayList<Item>(),
                "Leftover folders of uninstalled apps" to ArrayList<Item>(),
                "APK installer files" to ArrayList<Item>()
            )
            val keys = cats.keys.toList()
            val pkgRegex = Regex("^[a-z][a-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
            fun walk(d: File, top: Boolean) {
                val kids = d.listFiles() ?: return
                if (!top && kids.isEmpty()) { cats[keys[1]]!!.add(Item(d, 0)); return }
                for (k in kids) {
                    if (top && k.name == "Android") continue
                    if (java.nio.file.Files.isSymbolicLink(k.toPath())) continue
                    if (k.isDirectory) {
                        if (top && pkgRegex.matches(k.name) && !installed(k.name)) { cats[keys[2]]!!.add(Item(k, sizeOf(k))); continue }
                        walk(k, false)
                    } else {
                        val ext = k.extension.lowercase()
                        if (ext == "apk") cats[keys[3]]!!.add(Item(k, k.length()))
                        else if (k.name != ".nomedia" && (k.length() == 0L || ext in junkExt || d.name == ".thumbnails")) cats[keys[0]]!!.add(Item(k, k.length()))
                    }
                }
            }
            walk(Environment.getExternalStorageDirectory(), true)
            runOnUiThread {
                val found = keys.filter { cats[it]!!.isNotEmpty() }
                if (found.isEmpty()) { info.text = "No junk found in shared storage. For app caches, use 'Apps using the most space'."; return@runOnUiThread }
                info.text = ""
                val checked = BooleanArray(found.size) { !found[it].startsWith("APK") }
                val labels = found.map { k -> "$k\n${cats[k]!!.size} items, ${fmt(cats[k]!!.sumOf { it.size })}" }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Junk found")
                    .setMultiChoiceItems(labels, checked) { _, i, v -> checked[i] = v }
                    .setPositiveButton("Delete selected") { _, _ ->
                        delete(found.filterIndexed { i, _ -> checked[i] }.flatMap { cats[it]!! })
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    private fun scanBig() {
        if (!hasAccess()) { Toast.makeText(this, "Tap button 1 and allow file access first", Toast.LENGTH_LONG).show(); return }
        info.text = "Searching for big files..."
        thread {
            val list = ArrayList<File>()
            fun walk(d: File, top: Boolean) {
                for (k in d.listFiles() ?: return) {
                    if (top && k.name == "Android") continue
                    if (java.nio.file.Files.isSymbolicLink(k.toPath())) continue
                    if (k.isDirectory) walk(k, false) else if (k.length() > 30L * 1024 * 1024) list.add(k)
                }
            }
            val root = Environment.getExternalStorageDirectory()
            walk(root, true)
            val top = list.sortedByDescending { it.length() }.take(40)
            runOnUiThread {
                if (top.isEmpty()) { info.text = "No files bigger than 30 MB found."; return@runOnUiThread }
                info.text = ""
                val checked = BooleanArray(top.size)
                val labels = top.map { "${it.name}\n${fmt(it.length())}  -  ${it.parent?.removePrefix(root.path)}" }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Biggest files - tick to delete")
                    .setMultiChoiceItems(labels, checked) { _, i, v -> checked[i] = v }
                    .setPositiveButton("Delete selected") { _, _ ->
                        delete(top.filterIndexed { i, _ -> checked[i] }.map { Item(it, it.length()) })
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    private fun hasUsage(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }

    private fun scanApps() {
        if (Build.VERSION.SDK_INT < 26) { Toast.makeText(this, "Needs Android 8 or newer", Toast.LENGTH_LONG).show(); return }
        if (!hasUsage()) {
            Toast.makeText(this, "Turn on usage access for Deep Clean, then come back", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); return
        }
        info.text = "Measuring apps..."
        thread {
            val ssm = getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            val rows = ArrayList<Row>()
            for (ai in packageManager.getInstalledApplications(0)) {
                try {
                    val s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, Process.myUserHandle())
                    rows.add(Row(ai.loadLabel(packageManager).toString(), ai.packageName, s.appBytes + s.dataBytes + s.cacheBytes, s.cacheBytes))
                } catch (e: Exception) { }
            }
            val top = rows.sortedByDescending { it.total }.take(30)
            runOnUiThread {
                info.text = ""
                val labels = top.map { "${it.name}\n${fmt(it.total)} total  -  cache ${fmt(it.cache)}" }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Tap an app > Storage > Clear cache")
                    .setItems(labels) { _, i ->
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${top[i].pkg}")))
                    }
                    .setNegativeButton("Close", null).show()
            }
        }
    }

    private fun ramHelp() {
        AlertDialog.Builder(this).setTitle("Add RAM from storage")
            .setMessage("Your phone has this built in. Open Settings > About phone > Memory extension (also called RAM Plus or Virtual RAM), choose 1 GB or 2 GB, then restart.\n\nNo app can do this safely without root, and it will not make the phone much faster, only keep more apps open.")
            .setPositiveButton("Open About phone") { _, _ -> startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) }
            .setNegativeButton("Close", null).show()
    }

    private fun delete(items: List<Item>) {
        info.text = "Deleting..."
        thread {
            var freed = 0L
            for (it in items) {
                if (it.f.exists()) { if (it.f.isDirectory) it.f.deleteRecursively() else it.f.delete() }
                freed += it.size
            }
            runOnUiThread { info.text = "Done. Freed about ${fmt(freed)}."; refresh() }
        }
    }
}
