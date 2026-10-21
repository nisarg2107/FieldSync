package dev.fieldsync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import dev.fieldsync.ui.BoardScreen
import dev.fieldsync.ui.BoardViewModel

class MainActivity : ComponentActivity() {
    private lateinit var model: BoardViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[BoardViewModel::class.java]
        setContent { BoardScreen(model) }
    }
    override fun onStop() {
        model.stopConnections()
        super.onStop()
    }
}

