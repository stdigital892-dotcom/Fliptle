package com.test.hello

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

/** Shows how many domains/keywords are loaded and allows a manual update. */
class BlocklistStatusActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var countText: TextView
    private lateinit var updateButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocklist_status)
        countText = findViewById(R.id.blocklistCountText)
        updateButton = findViewById(R.id.updateNowButton)

        updateButton.setOnClickListener { updateNow() }

        // Load (if needed) off the main thread, then show the counts.
        io.execute {
            AdultBlocklist.loadBlocking(this)
            KeywordBlocklist.ensureLoaded(this)
            main.post { render() }
        }
        render()
    }

    private fun render() {
        val last = AdultBlocklist.lastUpdated(this)
        val lastStr = if (last == 0L) getString(R.string.never_updated)
        else DateFormat.getDateTimeInstance().format(Date(last))
        countText.text = getString(
            R.string.blocklist_counts,
            AdultBlocklist.count,
            KeywordBlocklist.count,
            lastStr
        )
    }

    private fun updateNow() {
        updateButton.isEnabled = false
        countText.text = getString(R.string.updating)
        io.execute {
            val result = AdultBlocklist.updateFromNetwork(this)
            main.post {
                updateButton.isEnabled = true
                val msg = if (result != null) getString(R.string.update_done, result)
                else getString(R.string.update_failed)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                render()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }
}
