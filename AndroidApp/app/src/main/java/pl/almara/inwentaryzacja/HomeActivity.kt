package pl.almara.inwentaryzacja

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import pl.almara.inwentaryzacja.databinding.ActivityHomeBinding

/** Ekran startowy: wybór modułu (Inwentaryzacje, RW, Ustawienia). */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.inventoryButton.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        binding.rwButton.setOnClickListener {
            startActivity(Intent(this, RwListActivity::class.java))
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }
}
