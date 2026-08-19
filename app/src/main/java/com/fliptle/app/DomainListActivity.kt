package com.fliptle.app

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Add/remove blocked domains. Built-in test domains are shown but not removable. */
class DomainListActivity : AppCompatActivity() {

    private lateinit var store: DomainBlocklist
    private lateinit var listView: ListView
    private lateinit var input: EditText
    private var domains: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_domain_list)
        store = DomainBlocklist(this)

        input = findViewById(R.id.domainInput)
        listView = findViewById(R.id.domainList)

        findViewById<Button>(R.id.addDomainButton).setOnClickListener {
            val text = input.text.toString()
            if (text.isBlank()) {
                Toast.makeText(this, R.string.enter_domain, Toast.LENGTH_SHORT).show()
            } else {
                // Adding a domain TIGHTENS, so it is always allowed — the lock
                // exists to stop loosening, never to stop blocking more.
                store.add(text)
                CloudState.backup(this)
                input.text.clear()
                refresh()
            }
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val domain = domains[position]
            if (store.isBuiltIn(domain)) {
                Toast.makeText(this, R.string.builtin_not_removable, Toast.LENGTH_SHORT).show()
            } else {
                // Removing a domain LOOSENS: committed, so frozen during the lock.
                if (!Commitment.guard(this)) return@setOnItemClickListener
                store.removeUser(domain)
                refresh()
                Commitment.onChanged(this)
            }
        }

        refresh()
    }

    private fun refresh() {
        domains = store.allDomains().sorted()
        val rows = domains.map { d ->
            if (store.isBuiltIn(d)) "$d  (built-in)" else "$d  (tap to remove)"
        }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }
}
