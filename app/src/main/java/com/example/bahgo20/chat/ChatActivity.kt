package com.example.bahgo20.chat

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.bahgo20.AboutActivity
import com.example.bahgo20.ConditionsActivity
import com.example.bahgo20.FloodAlerts
import com.example.bahgo20.R
import com.example.bahgo20.RecordedTraining
import com.example.bahgo20.Risk
import com.example.bahgo20.SensorTracker
import com.example.bahgo20.SettingsActivity
import com.example.bahgo20.TrendState
import com.example.bahgo20.databinding.ActivityChatBinding
import com.example.bahgo20.ui.StatusText
import com.example.bahgo20.utils.OnboardingManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.launch

/**
 * BahGo Assistant chat (opened from the chat button on Home).
 * Answers come from [ChatBot] on the phone; live answers use the same sensor readings as Home.
 * The conversation is kept while the app is open, so leaving and coming back keeps it.
 */
class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private val db = FirebaseDatabase
        .getInstance("https://bahgo-ee71b-default-rtdb.asia-southeast1.firebasedatabase.app/")
        .reference
    private val tracker = SensorTracker()
    private val listeners = mutableListOf<Pair<String, ValueEventListener>>()
    private val handler = Handler(Looper.getMainLooper())

    private val bot by lazy { ChatBot(::liveStatus, ::hasVehicle) { com.example.bahgo20.utils.HomeStreet.get(this) } }

    private class Message(val fromUser: Boolean, val text: String, val actions: List<BotAction> = emptyList())

    companion object {
        private const val TAG = "ChatActivity"
        private const val TYPING_MS = 550L
        private const val PREFS_NAME = "bahgo_monitor_prefs"
        private const val KEY_HAS_VEHICLE = "has_vehicle"

        // Kept for this app session
        private val history = mutableListOf<Message>()
        private var lastSuggestions: List<String> = ChatBot.STARTERS
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Keep the input above the keyboard and the navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, ime.bottom))
            scrollToEnd()
            insets
        }

        binding.ivBack.setOnClickListener { finish() }
        binding.btnSend.setOnClickListener { sendTyped() }
        binding.etMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false
        }

        if (history.isEmpty()) {
            history.add(Message(false, ChatBot.WELCOME.text))
            lastSuggestions = ChatBot.WELCOME.suggestions
        }
        history.forEach { addBubble(it) }
        showSuggestions(lastSuggestions)
        scrollToEnd()

        listenToSensors()
        lifecycleScope.launch { RecordedTraining.load() }   // forecast models (already loaded if Home did it)
    }

    override fun onDestroy() {
        listeners.forEach { (node, l) -> db.child(node).removeEventListener(l) }
        listeners.clear()
        handler.removeCallbacksAndMessages(null)
        tracker.saveHistory()
        super.onDestroy()
    }

    // ───────────────────────── Live data ─────────────────────────

    private fun listenToSensors() {
        (1..4).forEach { id ->
            val node = "Sensordata$id"
            val l = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) = tracker.setSensor(id, snapshot)
                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "$node cancelled: ${error.message}")
                    tracker.clearSensor(id)
                }
            }
            db.child(node).addValueEventListener(l)
            listeners.add(node to l)
        }
        val updated = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = tracker.setRootUpdated(snapshot)
            override fun onCancelled(error: DatabaseError) = tracker.setRootUpdated(null)
        }
        db.child("Last_Updated").addValueEventListener(updated)
        listeners.add("Last_Updated" to updated)
    }

    private fun liveStatus(): List<StreetStatus> = (1..4).map { id ->
        val r = tracker.reading(id)
        val online = r.risk != Risk.OFFLINE
        val trend = if (online) tracker.trend(id) else null
        val trendText = trend?.takeIf { it.state != TrendState.COLLECTING }?.let {
            val (value, detail) = StatusText.rise(this, it)
            "$value · $detail"
        }
        val rain = if (online) StatusText.rain(this, tracker.snapshot(id)).takeIf { it != "--" } else null
        val forecast = if (!online) null else RecordedTraining.levelForecaster()?.let { full ->
            tracker.forecast(id, full, RecordedTraining.levelEarlyForecaster())?.linear
        }
        StreetStatus(id, FloodAlerts.STREETS.getValue(id), r.risk, r.level, trendText, rain, forecast)
    }

    private fun hasVehicle() =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_HAS_VEHICLE, false)

    // ───────────────────────── Conversation ─────────────────────────

    private fun sendTyped() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty()) return
        binding.etMessage.text.clear()
        send(text)
    }

    private fun send(text: String) {
        val user = Message(true, text)
        history.add(user)
        addBubble(user)
        showSuggestions(emptyList())

        // Short "typing" pause so the answer doesn't feel jumpy
        val typing = addBubble(Message(false, "…"))
        scrollToEnd()
        handler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            binding.llMessages.removeView(typing)
            val reply = bot.reply(text)
            val msg = Message(false, reply.text, reply.actions)
            history.add(msg)
            addBubble(msg)
            lastSuggestions = reply.suggestions
            showSuggestions(reply.suggestions)
            scrollToEnd()
        }, TYPING_MS)
    }

    private fun addBubble(m: Message): View {
        val maxWidth = (resources.displayMetrics.widthPixels * 0.8).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (m.fromUser) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        column.addView(TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = m.text
            this.maxWidth = maxWidth
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(0f, 1.15f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setTextIsSelectable(!m.fromUser)
            if (m.fromUser) {
                setBackgroundResource(R.drawable.bg_chat_user)
                setTextColor(ContextCompat.getColor(context, R.color.white))
            } else {
                setBackgroundResource(R.drawable.bg_chat_bot)
                setTextColor(ContextCompat.getColor(context, R.color.text_on_card))
            }
        })
        m.actions.forEach { action ->
            column.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = action.label
                isAllCaps = false
                setTextColor(ContextCompat.getColor(context, R.color.text_on_card))
                backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.card_solid))
                strokeColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.button_primary))
                cornerRadius = dp(18)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
                setOnClickListener { run(action) }
            })
        }
        binding.llMessages.addView(column)
        return column
    }

    private fun showSuggestions(items: List<String>) {
        binding.cgSuggestions.removeAllViews()
        binding.hsvSuggestions.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        items.forEach { q ->
            binding.cgSuggestions.addView(Chip(this).apply {
                text = q
                chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.card_solid))
                chipStrokeColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.button_primary))
                chipStrokeWidth = dp(1).toFloat()
                setTextColor(ContextCompat.getColor(context, R.color.text_on_card))
                setOnClickListener { send(q) }
            })
        }
        binding.hsvSuggestions.scrollTo(0, 0)
    }

    private fun scrollToEnd() {
        binding.svMessages.post { binding.svMessages.fullScroll(View.FOCUS_DOWN) }
    }

    // ───────────────────────── Buttons under answers ─────────────────────────

    private fun run(action: BotAction) {
        when (action) {
            is BotAction.OpenStreet -> startActivity(Intent(this, ConditionsActivity::class.java).apply {
                putExtra("SENSOR_ID", action.id)
                putExtra("STREET_NAME", FloodAlerts.STREETS[action.id])
            })
            BotAction.OpenSettings -> startActivity(Intent(this, SettingsActivity::class.java))
            BotAction.OpenAbout -> startActivity(Intent(this, AboutActivity::class.java))
            BotAction.CallEmergency -> open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:911")))
            BotAction.Email -> open(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${ChatBot.CONTACT_EMAIL}")).apply {
                putExtra(Intent.EXTRA_SUBJECT, "BahGo question")
            })
            BotAction.ReplayTour -> {
                // Home shows the tour again when it comes back to the front
                OnboardingManager(this).setHomeTourDone(false)
                finish()
            }
        }
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
