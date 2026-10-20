package com.drops.gemini

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var geminiService: GeminiApiService
    private var textToSpeech: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    private lateinit var chatLayout: LinearLayout
    private lateinit var chatScrollView: ScrollView
    private lateinit var inputEditText: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var micButton: ImageButton
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var suggestionsLayout: LinearLayout
    private lateinit var vocabLayout: LinearLayout

    private val lesson = LessonContext(
        scenario = "Ordering at a cozy artisan café in London",
        targetWords = listOf("coffee", "delicious", "recommend", "morning")
    )
    private val activeTargetWords = mutableSetOf<String>()

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startSpeechRecognition()
        } else {
            Toast.makeText(this, "يلزم تصريح الميكروفون لاستخدام المحادثة الصوتية", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ضع مفتاح Google AI Studio API هنا، أو اتركه فارغاً للعمل في الوضع التجريبي المحلي
        val apiKey = "" 
        geminiService = GeminiApiService(apiKey)

        initViews()
        initTextToSpeech()
        initSpeechRecognizer()
        renderVocabularyBar()

        // رسالة ترحيبية أولى
        addMessageToChat(
            ChatMessage(
                role = MessageRole.MODEL,
                text = "Good morning! Welcome to our London café. What can I get started for you today?"
            )
        )
        updateQuickSuggestions(listOf(
            "Good morning! I would like a coffee, please.",
            "What delicious breakfast do you recommend?"
        ))
    }

    private fun initViews() {
        chatLayout = findViewById(R.id.chatLayout)
        chatScrollView = findViewById(R.id.chatScrollView)
        inputEditText = findViewById(R.id.inputEditText)
        sendButton = findViewById(R.id.sendButton)
        micButton = findViewById(R.id.micButton)
        loadingProgressBar = findViewById(R.id.loadingProgressBar)
        suggestionsLayout = findViewById(R.id.suggestionsLayout)
        vocabLayout = findViewById(R.id.vocabLayout)

        sendButton.setOnClickListener {
            val text = inputEditText.text.toString().trim()
            if (text.isNotEmpty()) {
                handleUserMessage(text)
                inputEditText.text.clear()
            }
        }

        micButton.setOnClickListener {
            toggleSpeechRecognition()
        }
    }

    private fun initTextToSpeech() {
        textToSpeech = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            textToSpeech?.language = Locale.US
            textToSpeech?.setSpeechRate(0.92f)
        }
    }

    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    micButton.setImageResource(android.R.drawable.ic_media_pause)
                    micButton.setBackgroundResource(R.drawable.bg_mic_active)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val spokenText = matches[0]
                        inputEditText.setText(spokenText)
                        handleUserMessage(spokenText)
                        inputEditText.text.clear()
                    }
                    stopSpeechRecognition()
                }

                override fun onError(error: Int) {
                    stopSpeechRecognition()
                }

                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun toggleSpeechRecognition() {
        if (isListening) {
            stopSpeechRecognition()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startSpeechRecognition()
            } else {
                requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun startSpeechRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "تحدث باللغة الإنجليزية...")
        }
        speechRecognizer?.startListening(intent)
    }

    private fun stopSpeechRecognition() {
        isListening = false
        speechRecognizer?.stopListening()
        micButton.setImageResource(android.R.drawable.ic_btn_speak_now)
        micButton.setBackgroundResource(R.drawable.bg_mic_button)
    }

    private fun handleUserMessage(messageText: String) {
        addMessageToChat(ChatMessage(role = MessageRole.USER, text = messageText))
        loadingProgressBar.visibility = View.VISIBLE
        suggestionsLayout.removeAllViews()

        lifecycleScope.launch {
            val result = geminiService.sendMessage(messageText, lesson)
            loadingProgressBar.visibility = View.GONE

            result.onSuccess { response ->
                addMessageToChat(
                    ChatMessage(
                        role = MessageRole.MODEL,
                        text = response.reply,
                        recastCorrection = response.recastCorrection,
                        grammarTip = response.grammarTip
                    )
                )

                // نطق الرد صوتياً
                textToSpeech?.speak(response.reply, TextToSpeech.QUEUE_FLUSH, null, "UTTERANCE_ID")

                // تحديث كلمات Drops المفعلة
                response.targetWordsDetected.forEach { word ->
                    activeTargetWords.add(word.lowercase())
                }
                renderVocabularyBar()

                // عرض الاقتراحات السريعة
                updateQuickSuggestions(response.suggestedQuickReplies)
            }.onFailure { ex ->
                addMessageToChat(
                    ChatMessage(
                        role = MessageRole.MODEL,
                        text = "عذراً، حدث خطأ: ${ex.message}"
                    )
                )
            }
        }
    }

    private fun addMessageToChat(msg: ChatMessage) {
        val inflater = LayoutInflater.from(this)
        val view = if (msg.role == MessageRole.USER) {
            inflater.inflate(R.layout.item_message_user, chatLayout, false)
        } else {
            inflater.inflate(R.layout.item_message_model, chatLayout, false)
        }

        val textMsg = view.findViewById<TextView>(R.id.textMessage)
        textMsg.text = msg.text

        // بطاقة التصحيح اللغوي الذكي (Recasting)
        val recastCard = view.findViewById<View?>(R.id.recastCard)
        if (recastCard != null) {
            if (!msg.recastCorrection.isNullOrBlank() || !msg.grammarTip.isNullOrBlank()) {
                recastCard.visibility = View.VISIBLE
                val textRecast = view.findViewById<TextView>(R.id.textRecastCorrection)
                val textTip = view.findViewById<TextView>(R.id.textGrammarTip)

                textRecast.text = "الصيغة الطبيعية: " + (msg.recastCorrection ?: "")
                textTip.text = msg.grammarTip ?: ""
            } else {
                recastCard.visibility = View.GONE
            }
        }

        chatLayout.addView(view)
        chatScrollView.post { chatScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun renderVocabularyBar() {
        vocabLayout.removeAllViews()
        for (word in lesson.targetWords) {
            val chip = TextView(this).apply {
                text = word
                textSize = 12f
                setPadding(24, 10, 24, 10)
                val isUsed = activeTargetWords.contains(word.lowercase())
                setBackgroundResource(if (isUsed) R.drawable.bg_pill_active else R.drawable.bg_pill)
                setTextColor(ContextCompat.getColor(this@MainActivity, if (isUsed) R.color.accent_green_text else R.color.text_light))
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 16 }
            vocabLayout.addView(chip, params)
        }
    }

    private fun updateQuickSuggestions(suggestions: List<String>) {
        suggestionsLayout.removeAllViews()
        for (sug in suggestions) {
            val btn = Button(this).apply {
                text = sug
                textSize = 11f
                isAllCaps = false
                setBackgroundResource(R.drawable.bg_suggestion_pill)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_purple_light))
                setOnClickListener {
                    handleUserMessage(sug)
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 14 }
            suggestionsLayout.addView(btn, params)
        }
    }

    override fun onDestroy() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        speechRecognizer?.destroy()
        super.onDestroy()
    }
}
