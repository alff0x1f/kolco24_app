package ru.kolco24.kolco24.ui

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import ru.kolco24.kolco24.MainActivity
import ru.kolco24.kolco24.R

class SuccessActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sucess)

        val successNextButton: Button = findViewById(R.id.successNextButton)
        successNextButton.setOnClickListener {
            OnBackPressedDispatcher()
        }

    }
    fun OnBackPressedDispatcher () {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }

}