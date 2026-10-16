package com.deepclean.app

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import android.widget.*
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {

    enum class Cat(val title: String, val default: Boolean) {
        JUNK("Junk files (.tmp .log .bak, thumbnails, empty files)", true),
        EMPTY("Empty folders", true),
        LEFTOVER("Leftover folders of uninstalled apps", true),
        APK("APK installer files", false)
    }

    private val found = mutableMapOf<Cat, MutableList<File>>()
    private val sizes = mutableMapOf<Cat, Long>()
    private val checks = mutableMapOf<Cat, CheckBox>()
    private lateinit var status: TextView
    private lateinit var ramInfo: TextView
    private lateinit var scanBtn: Button
    private lateinit var cleanBtn: Button
    private val junkExt = setOf("tmp", "temp", "log", "bak", "old", "chk", "dmp", "thumbdata", "cache")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 40, 40, 40) }
        val sv = ScrollView(this).apply { addView(root) }
        setContentView(sv)

        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; setOnClickListener { f() }; root.addView(this) }
        ramInfo = TextView(this).apply { textSize = 15f; root.addView(this) }
        btn("Open Android memory / app settings") { startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS)) }
        btn("Open storage settings") {
            try { startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) } catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }
        btn("1. Allow file access") { askPermission() }
        scanBtn = btn("2. Scan phone") { scan() }
        status = TextView(this).apply { textSize = 15f; text = "Not scanned yet."; setPadding(0, 20, 0, 20); root.addView(this) }
        for (c in Cat.values()) {
            val cb = CheckBox(this).apply { isChecked = c.default; text = c.title; visibility = android.view.View.GONE }
            checks[c] = cb; root.addView(cb)
        }
        cleanBtn = btn("3. Delete selected") { confirmClean() }
        cleanBtn.isEnabled = false
        btn("Clear this app's cache") { cacheDir.deleteRecursively(); Toast.makeText(this, "Done", Toast.LENGTH_SHORT).show() }
    }

    override fun onResume() { super.onResume(); refreshRam() }

    private fun refreshRam() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        ramInfo.text = "RAM: ${Formatter.formatFileSize(this, mi.availMem)} free of ${Formatter.formatFileSize(this, mi.totalMem)}\n" +
            "Note: Android manages RAM itself; apps cannot safely add RAM without root."
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

    private fun scan() {
        if (!hasAccess()) { Toast.makeText(this, "Allow file access first", Toast.LENGTH_LONG).show(); return }
        found.clear(); sizes.clear(); scanBtn.isEnabled = false; cleanBtn.isEnabled = false
        status.text = "Scanning..."
        thread {
            val root = Environment.getExternalStorageDirectory()
            val pkgRegex = Regex("^[a-z][a-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
            fun add(c: Cat, f: File, s: Long) { found.getOrPut(c) { mutableListOf() }.add(f); sizes[c] = (sizes[c] ?: 0) + s }
            fun walk(d: File, top: Boolean) {
                val kids = d.listFiles() ?: return
                if (!top && kids.isEmpty()) { add(Cat.EMPTY, d, 0); return }
                for (k in kids) {
                    if (top && k.name == "Android") continue
                    if (java.nio.file.Files.isSymbolicLink(k.toPath())) continue
                    if (k.isDirectory) {
                        if (top && pkgRegex.matches(k.name) && !installed(k.name)) { add(Cat.LEFTOVER, k, sizeOf(k)); continue }
                        walk(k, false)
                    } else {
                        val ext = k.extension.lowercase()
                        when {
                            ext == "apk" -> add(Cat.APK, k, k.length())
                            k.name == ".nomedia" -> {}
                            k.length() == 0L || ext in junkExt || d.name == ".thumbnails" -> add(Cat.JUNK, k, k.length())
                        }
                    }
                }
            }
            walk(root, true)
            runOnUiThread { showResults() }
        }
    }

    private fun showResults() {
        val sb = StringBuilder("Scan finished:\n")
        for (c in Cat.values()) {
            val n = found[c]?.size ?: 0
            sb.append("- ${c.title}: $n items, ${Formatter.formatFileSize(this, sizes[c] ?: 0)}\n")
            checks[c]!!.visibility = if (n > 0) android.view.View.VISIBLE else android.view.View.GONE
        }
        status.text = sb.toString(); scanBtn.isEnabled = true; cleanBtn.isEnabled = found.isNotEmpty()
    }

    private fun confirmClean() {
        AlertDialog.Builder(this).setTitle("Delete selected items?")
            .setMessage("This permanently deletes the checked categories. It cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> clean() }.setNegativeButton("Cancel", null).show()
    }

    private fun clean() {
        cleanBtn.isEnabled = false
        thread {
            var freed = 0L
            for (c in Cat.values()) {
                if (checks[c]?.isChecked != true) continue
                for (f in found[c].orEmpty()) { if (f.exists()) { if (f.isDirectory) f.deleteRecursively() else f.delete() } }
                freed += sizes[c] ?: 0
            }
            runOnUiThread {
                status.text = "Cleaned. Freed about ${Formatter.formatFileSize(this, freed)}."
                for (c in Cat.values()) checks[c]!!.visibility = android.view.View.GONE
                found.clear()
            }
        }
    }
}
