package com.holdfast.ssh.ui

import android.app.Activity
import com.holdfast.ssh.session.UiPrompter

/** Shows host-key / password / passphrase questions coming from background connections. */
class PromptHandler(private val a: Activity) {
    fun handle(r: UiPrompter.Request) {
        if (a.isFinishing || r.answer.isDone) return
        when (r) {
            is UiPrompter.Request.HostKey -> {
                val changed = r.oldFp != null
                val msg = if (changed)
                    "WARNING: the host key for ${r.hostPort} has CHANGED.\n\nThis can mean someone is intercepting the connection, or the server was reinstalled.\n\nOld: ${r.oldFp}\nNew: ${r.fp} (${r.keyType})\n\nOnly continue if you know why it changed."
                else "First connection to ${r.hostPort}.\n\n${r.keyType}\n${r.fp}\n\nTrust this server?"
                a.dialog(if (changed) "Host key changed!" else "Verify server").setMessage(msg).setCancelable(false)
                    .setPositiveButton(if (changed) "Accept new key" else "Trust") { _, _ -> r.answer.complete("yes") }
                    .setNegativeButton("Cancel") { _, _ -> r.answer.complete("no") }.show()
            }
            is UiPrompter.Request.Password -> {
                val f = Form(a); val p = f.password("Password")
                a.dialog("Password for ${r.host.username}@${r.host.address}").setView(f.root).setCancelable(false)
                    .setPositiveButton("Log in") { _, _ -> r.answer.complete(p.text.toString()) }
                    .setNegativeButton("Cancel") { _, _ -> r.answer.complete(null) }.show()
                p.requestFocus()
            }
            is UiPrompter.Request.Passphrase -> {
                val f = Form(a); val p = f.password("Passphrase")
                a.dialog("Unlock key \"${r.keyName}\"").setView(f.root).setCancelable(false)
                    .setPositiveButton("Unlock") { _, _ -> r.answer.complete(p.text.toString()) }
                    .setNegativeButton("Cancel") { _, _ -> r.answer.complete(null) }.show()
            }
        }
    }
}
