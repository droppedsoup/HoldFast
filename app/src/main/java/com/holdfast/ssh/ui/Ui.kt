package com.holdfast.ssh.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.holdfast.ssh.Prefs
import com.holdfast.ssh.R
import com.holdfast.ssh.data.Host
import com.holdfast.ssh.session.SessionManager
import com.holdfast.ssh.util.Tailnet

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
fun Context.copyText(label: String, text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    toast("Copied")
}

/** Tiny programmatic form builder for dialogs. */
class Form(val ctx: Context) {
    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(20), ctx.dp(8), ctx.dp(20), 0)
    }

    fun text(hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT, lines: Int = 1): EditText {
        val til = TextInputLayout(ctx)
        til.hint = hint
        val et = TextInputEditText(til.context)
        et.inputType = type or (if (lines > 1) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        if (lines > 1) { et.minLines = lines; et.maxLines = 12 }
        et.setText(value)
        if (type and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0) til.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        til.addView(et)
        root.addView(til, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ctx.dp(6) })
        return et
    }

    fun password(hint: String, value: String = "") =
        text(hint, value, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)

    fun number(hint: String, value: Int) = text(hint, value.toString(), InputType.TYPE_CLASS_NUMBER)

    fun check(label: String, value: Boolean) = CheckBox(ctx).apply {
        text = label; isChecked = value
        root.addView(this)
    }

    fun spinner(label: String, items: List<String>, selected: Int): Spinner {
        root.addView(TextView(ctx).apply {
            text = label; setPadding(0, ctx.dp(10), 0, 0)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
        })
        return Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, items)
            if (selected in items.indices) setSelection(selected)
            root.addView(this)
        }
    }

    fun note(text: String) = TextView(ctx).apply {
        this.text = text; setPadding(0, ctx.dp(8), 0, 0)
        root.addView(this)
    }

    fun scroll() = android.widget.ScrollView(ctx).apply { addView(root) }
}

fun Context.dialog(title: String) = MaterialAlertDialogBuilder(this).setTitle(title)

fun Context.confirm(title: String, msg: String, yes: String = "Delete", action: () -> Unit) {
    dialog(title).setMessage(msg).setPositiveButton(yes) { _, _ -> action() }.setNegativeButton("Cancel", null).show()
}

/** Generic list row (title, subtitle, chip, overflow menu) used by every list screen. */
data class Row(
    val id: String, val title: String, val subtitle: String = "", val chip: String? = null,
    val header: Boolean = false,
)

class RowAdapter(
    private val onClick: (Row) -> Unit,
    private val onMenu: ((Row, View) -> Unit)? = null,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var rows: List<Row> = emptyList()
        set(v) { field = v; notifyDataSetChanged() }

    override fun getItemViewType(position: Int) = if (rows[position].header) 1 else 0
    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        val v = inf.inflate(if (viewType == 1) R.layout.item_header else R.layout.item_row, parent, false)
        return object : RecyclerView.ViewHolder(v) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val r = rows[position]
        val v = holder.itemView
        v.findViewById<TextView>(R.id.title).text = r.title
        if (r.header) return
        v.findViewById<TextView>(R.id.subtitle).apply { text = r.subtitle; visibility = if (r.subtitle.isEmpty()) View.GONE else View.VISIBLE }
        v.findViewById<TextView>(R.id.chip).apply { text = r.chip; visibility = if (r.chip == null) View.GONE else View.VISIBLE }
        v.setOnClickListener { onClick(r) }
        v.findViewById<ImageButton>(R.id.menu).apply {
            visibility = if (onMenu == null) View.GONE else View.VISIBLE
            setOnClickListener { onMenu?.invoke(r, it) }
        }
        v.setOnLongClickListener { onMenu?.invoke(r, v.findViewById(R.id.menu)); true }
    }
}

object Launch {
    /**
     * One-tap connect. If the host has no saved credential, ask for the password once and
     * (by default) convert the host to key login so future connects need no typing.
     */
    fun connect(ctx: Context, host: Host) {
        askBatteryOnce(ctx)
        val needsPassword = (host.authType != "key" || host.keyId == null) && host.password == null
        if (!needsPassword) {
            SessionManager.open(host)
            ctx.startActivity(Intent(ctx, TerminalActivity::class.java))
            return
        }
        val f = Form(ctx)
        f.note("${host.username}@${host.address}" + if (Tailnet.isTailscale(host.address)) "  ·  via Tailscale" else "")
        val pw = f.password("Password")
        val setup = f.check("Set up key login (recommended: one tap from now on)", true)
        val save = f.check("Remember password instead", false)
        val dlg: AlertDialog = ctx.dialog("Log in to ${host.title}").setView(f.scroll())
            .setPositiveButton("Connect") { _, _ ->
                val p = pw.text.toString()
                if (save.isChecked && !setup.isChecked) {
                    com.holdfast.ssh.data.Store.host(host.id)?.let { it.password = p; com.holdfast.ssh.data.Store.save() }
                }
                SessionManager.open(host, setupKey = setup.isChecked, password = p)
                ctx.startActivity(Intent(ctx, TerminalActivity::class.java))
            }
            .setNegativeButton("Cancel", null).create()
        dlg.show()
        pw.requestFocus()
    }

    /** Ask once to exempt HoldFast from battery optimisation — the #1 cause of sessions dying when the phone locks. */
    fun askBatteryOnce(ctx: Context) {
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (Prefs.askedBattery || pm.isIgnoringBatteryOptimizations(ctx.packageName)) return
        Prefs.askedBattery = true
        requestBattery(ctx)
    }

    fun requestBattery(ctx: Context) {
        try {
            @Suppress("BatteryLife")
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        } catch (_: Exception) {
            ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
