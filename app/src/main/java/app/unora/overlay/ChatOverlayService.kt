package app.unora.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.unora.MainActivity
import app.unora.R
import app.unora.model.ChatMessage
import app.unora.model.PartyPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

class ChatOverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager
    private var currentView: View? = null
    private var currentParams: WindowManager.LayoutParams? = null
    private var messagesContainer: LinearLayout? = null
    private var partyTitle: TextView? = null
    private var stateJob: Job? = null
    private var latestMessages: List<ChatMessage> = emptyList()
    private var bubbleBadge: TextView? = null
    private var lastReadMessageCount = 0
    private var observedInitialState = false
    private var panelOpen = false
    private var bubbleX = 0
    private var bubbleY = 280

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        if (!Settings.canDrawOverlays(this) || ChatOverlayRuntime.partyState == null) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WindowManager::class.java)
        showBubble()
        observeParty()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val view = currentView ?: return
        val params = currentParams ?: return
        if (!panelOpen) {
            view.post {
                params.x = params.x.coerceIn(0, (resources.displayMetrics.widthPixels - view.width).coerceAtLeast(0))
                params.y = params.y.coerceIn(0, (resources.displayMetrics.heightPixels - view.height).coerceAtLeast(0))
                bubbleX = params.x
                bubbleY = params.y
                runCatching { windowManager.updateViewLayout(view, params) }
            }
        }
    }

    override fun onDestroy() {
        stateJob?.cancel()
        currentView?.let { runCatching { windowManager.removeView(it) } }
        currentView = null
        scope.cancel()
        super.onDestroy()
    }

    private fun observeParty() {
        stateJob?.cancel()
        val state = ChatOverlayRuntime.partyState ?: return
        stateJob = scope.launch {
            state.collectLatest { party ->
                if (party.phase in setOf(PartyPhase.Idle, PartyPhase.Ended) || party.partyId == null) {
                    stopSelf()
                } else {
                    latestMessages = party.chatMessages
                    if (!observedInitialState) {
                        observedInitialState = true
                        lastReadMessageCount = latestMessages.size
                    }
                    if (panelOpen) lastReadMessageCount = latestMessages.size
                    partyTitle?.text = "Party ${party.partyId}  •  ${party.participantCount} online"
                    renderMessages()
                    updateUnreadBadge()
                }
            }
        }
    }

    private fun showBubble() {
        panelOpen = false
        removeCurrentView()
        val size = dp(62)
        val bubble = FrameLayout(this).apply { elevation = dp(10).toFloat() }
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.unora_logo)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(128, 73, 255), Color.rgb(211, 73, 247)),
            ).apply { shape = GradientDrawable.OVAL }
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        bubble.addView(icon, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        bubbleBadge = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.rgb(255, 75, 105))
                shape = GradientDrawable.OVAL
            }
        }
        bubble.addView(bubbleBadge, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END))
        val params = overlayParams(size, size, focusable = false).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleX.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - size - dp(16))
            y = bubbleY
        }
        attachDragAndClick(bubble, params) { showPanel() }
        addView(bubble, params)
        updateUnreadBadge()
    }

    private fun showPanel() {
        panelOpen = true
        lastReadMessageCount = latestMessages.size
        currentParams?.let {
        bubbleX = it.x
            bubbleY = it.y
        }
        removeCurrentView()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBackground(Color.rgb(20, 21, 28), 24f, Color.rgb(64, 61, 76))
            elevation = dp(14).toFloat()
        }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        partyTitle = TextView(this).apply {
            text = "Chat da party"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        header.addView(partyTitle, LinearLayout.LayoutParams(0, dp(42), 1f))
        header.addView(smallButton("Abrir") { openApp() })
        header.addView(smallButton("—") { showBubble() })
        panel.addView(header, LinearLayout.LayoutParams.MATCH_PARENT, dp(46))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.TRANSPARENT)
        }
        messagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(8))
        }
        scroll.addView(messagesContainer, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val inputRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(this).apply {
            hint = "Mensagem…"
            setHintTextColor(Color.rgb(142, 145, 158))
            setTextColor(Color.WHITE)
            textSize = 15f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setPadding(dp(14), dp(8), dp(12), dp(8))
            background = roundedBackground(Color.rgb(30, 32, 42), 17f, Color.rgb(73, 69, 88))
            setOnEditorActionListener { view, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    val value = view.text.toString().trim()
                    if (value.isNotEmpty()) {
                        ChatOverlayRuntime.send(value)
                        view.text = ""
                    }
                    true
                } else false
            }
        }
        val send = Button(this).apply {
            text = "Enviar"
            isAllCaps = false
            setTextColor(Color.WHITE)
            textSize = 13f
            background = roundedBackground(Color.rgb(120, 76, 232), 17f)
            setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) {
                    ChatOverlayRuntime.send(value)
                    input.text.clear()
                }
            }
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, dp(50), 1f))
        inputRow.addView(send, LinearLayout.LayoutParams(dp(78), dp(46)).apply { marginStart = dp(8) })
        panel.addView(inputRow, LinearLayout.LayoutParams.MATCH_PARENT, dp(56))

        val width = minOf(resources.displayMetrics.widthPixels - dp(24), dp(370))
        val height = minOf(resources.displayMetrics.heightPixels - dp(120), dp(520))
        val params = overlayParams(width, height, focusable = true).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            x = dp(12)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        addView(panel, params)
        renderMessages()
        input.requestFocus()
        input.postDelayed({
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 180)
    }

    private fun renderMessages() {
        val container = messagesContainer ?: return
        container.removeAllViews()
        latestMessages.takeLast(30).forEach { message ->
            val item = TextView(this).apply {
                text = "${message.nickname}\n${message.text}"
                setTextColor(Color.WHITE)
                textSize = 14f
                setLineSpacing(0f, 1.08f)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = roundedBackground(Color.rgb(27, 29, 38), 14f)
            }
            container.addView(item, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            (item.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(7)
        }
        (container.parent as? ScrollView)?.post { (container.parent as ScrollView).fullScroll(View.FOCUS_DOWN) }
    }

    private fun addView(view: View, params: WindowManager.LayoutParams) {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        currentView = view
        currentParams = params
        runCatching { windowManager.addView(view, params) }
            .onFailure {
                currentView = null
                currentParams = null
                stopSelf()
            }
    }

    private fun removeCurrentView() {
        currentView?.let { runCatching { windowManager.removeView(it) } }
        currentView = null
        currentParams = null
        messagesContainer = null
        partyTitle = null
        bubbleBadge = null
    }

    private fun overlayParams(width: Int, height: Int, focusable: Boolean) = WindowManager.LayoutParams(
        width,
        height,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        if (focusable) WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    private fun attachDragAndClick(view: View, params: WindowManager.LayoutParams, onClick: () -> Unit) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + event.rawX - touchX).toInt().coerceIn(0, resources.displayMetrics.widthPixels - view.width)
                    params.y = (startY + event.rawY - touchY).toInt().coerceIn(0, resources.displayMetrics.heightPixels - view.height)
                    windowManager.updateViewLayout(view, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (abs(event.rawX - touchX) < dp(8) && abs(event.rawY - touchY) < dp(8)) onClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun smallButton(label: String, click: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(190, 168, 255))
        textSize = 13f
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { click() }
    }

    private fun roundedBackground(color: Int, radiusDp: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun updateUnreadBadge() {
        val badge = bubbleBadge ?: return
        val unread = (latestMessages.size - lastReadMessageCount).coerceAtLeast(0)
        badge.visibility = if (!panelOpen && unread > 0) View.VISIBLE else View.GONE
        badge.text = if (unread > 99) "99+" else unread.toString()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("Chat da party ativo")
        .setContentText("Toque na bolha para conversar sem sair do que está assistindo.")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(
            0,
            "Fechar chat",
            PendingIntent.getService(
                this,
                1,
                Intent(this, ChatOverlayService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Chat flutuante", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CHANNEL_ID = "unora_floating_chat"
        private const val NOTIFICATION_ID = 2301
        private const val ACTION_STOP = "app.unora.overlay.STOP"

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            ContextCompat.startForegroundService(context, Intent(context, ChatOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ChatOverlayService::class.java))
        }

        fun permissionIntent(context: Context) = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )
    }
}
