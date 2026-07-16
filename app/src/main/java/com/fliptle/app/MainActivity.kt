package com.fliptle.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Launcher/router: sends first-time users to onboarding, everyone else home. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val destination = if (OnboardingState(this).complete) {
            HomeActivity::class.java
        } else {
            OnboardingActivity::class.java
        }
        startActivity(Intent(this, destination))
        finish()
    }
}
