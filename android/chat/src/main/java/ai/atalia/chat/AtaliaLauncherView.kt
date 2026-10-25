package ai.atalia.chat

import ai.atalia.chat.internal.ChannelBranding
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sign

/** Posição e comportamento do botão flutuante. A aparência vem do painel. */
data class AtaliaLauncherOptions(
    /** Lado da tela. Nulo = o lado do painel. */
    val side: Side? = null,
    /** Distância da lateral (em [Atalia.showLauncher]). */
    val horizontalMarginDp: Int = 16,
    /** Distância do fim da tela, além da barra de navegação (em [Atalia.showLauncher]). Aumente para ficar acima de uma barra de abas. */
    val bottomMarginDp: Int = 16,
    /** Título do botão aberto. Nulo = o do painel ("Fale conosco"). */
    val text: String? = null,
    /** Linha de baixo do botão aberto. Nulo = o nome do atendimento no painel; vazio = sem subtítulo. */
    val subtitle: String? = null,
    /** Cor do círculo e da aba. Nula = a cor do botão no painel. */
    @ColorInt val color: Int? = null,
    val collapse: Collapse = Collapse.CIRCLE,
    /** Tempo aberto antes de recolher. Volta a abrir quando chega mensagem nova. */
    val collapseAfterMs: Long = 5_000,
    /** O usuário pode arrastar o botão; ao soltar, ele gruda na lateral mais próxima. Só em [Atalia.showLauncher]. */
    val draggable: Boolean = false,
    /** Puxar o botão até a borda o esconde numa aba; tocar ou puxar a aba traz de volta. */
    val dockable: Boolean = true,
    /** Carrega o chat escondido enquanto o botão está na tela: o toque abre o chat pronto. */
    val preloadChat: Boolean = true,
    /** O que o toque faz. Nulo = [Atalia.open]. */
    val onClick: (() -> Unit)? = null,
) {
    enum class Side { START, END }

    /** Forma do botão depois de recolher. */
    enum class Collapse {
        /** Só o círculo com o ícone. */
        CIRCLE,

        /** Uma aba encostada na lateral; o toque mostra o botão de novo. */
        EDGE,

        /** Fica sempre aberto. */
        NEVER,
    }
}

/**
 * Botão flutuante que abre o chat. A aparência (cor, texto, nome do atendimento e lado) vem do
 * painel da Atalia; posição e comportamento são do app.
 *
 * Aberto, é uma pílula com o título e o nome do atendimento saindo de trás do círculo colorido. Com
 * o botão parado, puxar faz um efeito elástico; puxar até a borda o esconde numa aba, e tocar (ou
 * puxar) a aba o traz de volta.
 *
 * O jeito mais simples é [Atalia.showLauncher], que serve para telas com View e com Compose. Para
 * posicionar você mesmo, use como qualquer view (no Compose, dentro de um `AndroidView`).
 */
class AtaliaLauncherView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    options: AtaliaLauncherOptions = AtaliaLauncherOptions(),
) : FrameLayout(context, attrs) {

    /** Trocar as opções mostra o botão aberto de novo, e ele recolhe do jeito novo. */
    var options: AtaliaLauncherOptions = options
        set(value) {
            field = value
            if (state == State.DOCKED) undock(animate = false)
            expand()
        }

    private enum class State { EXPANDED, CIRCLE, DOCKED }

    private val circleSize = dp(CIRCLE_DP)
    private val circleDrawable = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private val haloDrawable = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private val tabDrawable = GradientDrawable()
    private val pillDrawable = GradientDrawable()

    private val pill = LinearLayout(context)
    private val title = TextView(context)
    private val subtitle = TextView(context)
    private val halo = View(context)
    private val circle = FrameLayout(context)
    private val icon = ImageView(context)
    private val badge = TextView(context)
    private val tab = FrameLayout(context)
    private val chevron = ImageView(context)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var preloadRegistered = false

    private var branding: ChannelBranding? = null
    private var unread = 0
    private var lastUnread: Int? = null
    private var chatOpen = false
    private var state = State.EXPANDED
    private var side = AtaliaLauncherOptions.Side.END
    /** Lado escolhido pelo usuário ao arrastar (vence o das opções). */
    private var draggedSide: AtaliaLauncherOptions.Side? = null

    /** Posto por [Atalia.showLauncher]: o SDK cuida da gravidade, das margens e das barras do sistema. */
    internal var positionedBySdk = false
    private var systemBars = WindowInsetsCompat.CONSUMED.getInsets(WindowInsetsCompat.Type.systemBars())

    private val collapseRunnable = Runnable { collapse() }

    init {
        clipChildren = false
        clipToPadding = false
        // Espaço para a sombra, o halo do toque e o contador, que saem um pouco do círculo.
        setPadding(dp(INSET_DP), dp(INSET_DP), dp(INSET_DP), dp(INSET_DP))

        // Pílula branca, por trás do círculo: título e nome do atendimento.
        pill.orientation = LinearLayout.VERTICAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = pillDrawable.apply { cornerRadius = circleSize / 2f }
        pill.elevation = dp(6).toFloat()
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        title.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        for (text in listOf(title, subtitle)) {
            text.maxLines = 1
            text.ellipsize = TextUtils.TruncateAt.END
            pill.addView(text, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        addView(pill, LayoutParams(LayoutParams.WRAP_CONTENT, circleSize, Gravity.CENTER_VERTICAL))

        // Halo do toque: aparece em volta do círculo enquanto o dedo está nele.
        halo.background = haloDrawable
        halo.alpha = 0f
        halo.scaleX = 0.7f
        halo.scaleY = 0.7f
        halo.elevation = dp(7).toFloat()
        addView(halo, LayoutParams(circleSize + dp(HALO_DP) * 2, circleSize + dp(HALO_DP) * 2, Gravity.CENTER_VERTICAL))

        circle.background = circleDrawable
        circle.elevation = dp(8).toFloat()
        icon.setImageResource(R.drawable.atalia_ic_chat)
        circle.addView(icon, LayoutParams(dp(26), dp(26), Gravity.CENTER))
        addView(circle, LayoutParams(circleSize, circleSize, Gravity.CENTER_VERTICAL))

        // Aba da borda: metade escondida além da tela, com a seta para dentro.
        tab.background = tabDrawable.apply { cornerRadius = dp(16).toFloat() }
        tab.elevation = dp(8).toFloat()
        chevron.setImageResource(R.drawable.atalia_ic_chevron)
        tab.addView(chevron, LayoutParams(dp(20), dp(20), Gravity.CENTER_VERTICAL))
        tab.visibility = GONE
        // A aba fica na faixa do gesto "voltar" do sistema: sem a exclusão, puxá-la fecha a tela do app.
        tab.addOnLayoutChangeListener { view, left, top, right, bottom, _, _, _, _ ->
            ViewCompat.setSystemGestureExclusionRects(view, listOf(Rect(0, 0, right - left, bottom - top)))
        }
        addView(tab, LayoutParams(dp(TAB_WIDTH_DP), circleSize, Gravity.CENTER_VERTICAL))

        badge.setTextColor(Color.WHITE)
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        badge.typeface = Typeface.DEFAULT_BOLD
        badge.gravity = Gravity.CENTER
        badge.minWidth = dp(20)
        badge.setPadding(dp(5), 0, dp(5), 0)
        badge.background = GradientDrawable().apply {
            setColor(Color.parseColor("#E5484D"))
            cornerRadius = dp(10).toFloat()
            setStroke((1.5f * resources.displayMetrics.density).roundToInt(), Color.WHITE)
        }
        badge.elevation = dp(9).toFloat()
        badge.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        badge.visibility = GONE
        addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, dp(20), Gravity.TOP))

        setupGestures()
        render()
    }

    /** Tira o botão da tela. */
    fun remove() {
        (parent as? ViewGroup)?.removeView(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        scope.launch {
            Atalia.brandingFlow.collect {
                val appearing = branding == null && it != null
                branding = it
                render()
                if (appearing && !chatOpen) appear()
            }
        }
        scope.launch {
            Atalia.isChatOpen.collect {
                val closed = chatOpen && !it
                chatOpen = it
                render()
                if (closed && branding != null) appear()
            }
        }
        scope.launch {
            Atalia.unreadCount.collect { count ->
                // Mensagem nova: o botão "pula" e abre de novo, como um aviso.
                val previous = lastUnread
                lastUnread = count
                unread = count
                render()
                if (previous != null && count > previous) {
                    if (state == State.DOCKED) undock(animate = true) else expand()
                    pop()
                }
            }
        }
        if (positionedBySdk) ViewCompat.requestApplyInsets(this)
        if (options.preloadChat) {
            preloadRegistered = true
            ChatPrewarm.launcherAttached(context)
        }
        scheduleCollapse()
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        mainHandler.removeCallbacks(collapseRunnable)
        if (preloadRegistered) {
            preloadRegistered = false
            ChatPrewarm.launcherDetached()
        }
        super.onDetachedFromWindow()
    }

    // Estados

    private fun expand() {
        if (state == State.DOCKED) return
        val wasExpanded = state == State.EXPANDED && pill.visibility == VISIBLE
        state = State.EXPANDED
        render()
        if (!wasExpanded) animatePillIn(delay = 0)
        scheduleCollapse()
    }

    /** Entrada na tela (e na volta do chat): o círculo cresce e a pílula sai de trás dele. */
    private fun appear() {
        if (state == State.DOCKED) return
        circle.scaleX = 0.2f
        circle.scaleY = 0.2f
        circle.animate().scaleX(1f).scaleY(1f).setDuration(340).setInterpolator(OvershootInterpolator(1.6f)).start()
        if (state == State.EXPANDED) animatePillIn(delay = 140)
        scheduleCollapse()
    }

    /** A pílula cresce a partir da ponta presa no círculo, e o texto entra logo depois. */
    private fun animatePillIn(delay: Long) {
        pill.alpha = 0f
        pill.doOnPreDraw {
            pill.pivotX = if (side == AtaliaLauncherOptions.Side.END) pill.width.toFloat() else 0f
            pill.scaleX = 0.3f
            pill.animate().scaleX(1f).alpha(1f).setStartDelay(delay).setDuration(340)
                .setInterpolator(OvershootInterpolator(1.1f)).start()
            for (text in listOf(title, subtitle)) {
                text.alpha = 0f
                text.translationX = dp(10) * if (side == AtaliaLauncherOptions.Side.END) 1f else -1f
                text.animate().alpha(1f).translationX(0f).setStartDelay(delay + 90).setDuration(240).start()
            }
        }
    }

    private fun collapse() {
        when (options.collapse) {
            AtaliaLauncherOptions.Collapse.NEVER -> return
            AtaliaLauncherOptions.Collapse.EDGE -> dock()
            AtaliaLauncherOptions.Collapse.CIRCLE -> {
                if (state != State.EXPANDED) return
                pill.animate().scaleX(0.3f).alpha(0f).setStartDelay(0).setDuration(180).setInterpolator(AccelerateInterpolator())
                    .withEndAction {
                        state = State.CIRCLE
                        render()
                    }.start()
            }
        }
    }

    /** Esconde na borda: o círculo sai pela lateral e a aba entra no lugar. */
    private fun dock() {
        if (state == State.DOCKED) return
        mainHandler.removeCallbacks(collapseRunnable)
        haptic(HapticFeedbackConstants.CONTEXT_CLICK)
        val out = dp(CIRCLE_DP + INSET_DP + options.horizontalMarginDp) * outwardSign()
        pill.animate().alpha(0f).setStartDelay(0).setDuration(120).start()
        circle.animate().translationX(out).setDuration(200).setInterpolator(AccelerateInterpolator()).withEndAction {
            circle.translationX = 0f
            state = State.DOCKED
            render()
            tab.translationX = dp(TAB_WIDTH_DP) * outwardSign()
            tab.animate().translationX(0f).setDuration(260).setInterpolator(OvershootInterpolator(1.2f)).start()
        }.start()
    }

    /** Volta da borda: a aba sai, o círculo entra girando o ícone e o botão abre. */
    private fun undock(animate: Boolean) {
        if (state != State.DOCKED) return
        haptic(HapticFeedbackConstants.CONTEXT_CLICK)
        state = State.CIRCLE
        render()
        if (animate) {
            circle.scaleX = 0.5f
            circle.scaleY = 0.5f
            icon.rotation = -120f * outwardSign()
            circle.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(1.6f)).start()
            icon.animate().rotation(0f).setDuration(380).setInterpolator(PathInterpolator(0.2f, 0.9f, 0.1f, 1f)).start()
            mainHandler.postDelayed({ expand() }, 160)
        } else {
            expand()
        }
    }

    /** Aviso de mensagem nova: o círculo cresce e volta. */
    private fun pop() {
        circle.animate().scaleX(1.14f).scaleY(1.14f).setDuration(120).withEndAction {
            circle.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2f)).start()
        }.start()
    }

    private fun scheduleCollapse() {
        mainHandler.removeCallbacks(collapseRunnable)
        if (state == State.EXPANDED && options.collapse != AtaliaLauncherOptions.Collapse.NEVER) {
            mainHandler.postDelayed(collapseRunnable, options.collapseAfterMs.coerceAtLeast(0))
        }
    }

    private fun render() {
        val branding = branding
        visibility = if (branding == null || chatOpen) GONE else VISIBLE
        if (branding == null) return

        side = draggedSide ?: options.side ?: if (branding.buttonOnStart) AtaliaLauncherOptions.Side.START else AtaliaLauncherOptions.Side.END
        val start = side == AtaliaLauncherOptions.Side.START
        val horizontal = if (start) Gravity.START else Gravity.END
        val color = options.color ?: parseColor(branding.buttonColor) ?: DEFAULT_COLOR
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        circleDrawable.setColor(color)
        haloDrawable.setColor(Color.argb(60, Color.red(color), Color.green(color), Color.blue(color)))
        tabDrawable.setColor(color)
        pillDrawable.setColor(if (night) Color.parseColor("#1F2937") else Color.WHITE)
        title.setTextColor(if (night) Color.WHITE else Color.parseColor("#111827"))
        subtitle.setTextColor(if (night) Color.parseColor("#9CA3AF") else Color.parseColor("#6B7280"))

        val titleText = options.text ?: branding.buttonText
        val subtitleText = options.subtitle ?: branding.botName.orEmpty()
        title.text = titleText
        subtitle.text = subtitleText
        subtitle.visibility = if (subtitleText.isBlank()) GONE else VISIBLE

        // A pílula fica por trás do círculo: a ponta dela some embaixo dele.
        val half = circleSize / 2
        (pill.layoutParams as LayoutParams).apply {
            gravity = horizontal or Gravity.CENTER_VERTICAL
            if (start) { marginStart = half; marginEnd = 0 } else { marginEnd = half; marginStart = 0 }
        }
        val near = dp(16)
        val far = half + dp(14)
        if (start) pill.setPadding(far, 0, near, 0) else pill.setPadding(near, 0, far, 0)
        (halo.layoutParams as LayoutParams).apply {
            gravity = horizontal or Gravity.CENTER_VERTICAL
            if (start) { marginStart = -dp(HALO_DP); marginEnd = 0 } else { marginEnd = -dp(HALO_DP); marginStart = 0 }
        }
        (circle.layoutParams as LayoutParams).gravity = horizontal or Gravity.CENTER_VERTICAL
        (tab.layoutParams as LayoutParams).gravity = horizontal or Gravity.CENTER_VERTICAL
        // Seta para dentro da tela, na parte da aba que aparece.
        chevron.rotation = if (start) 180f else 0f
        (chevron.layoutParams as LayoutParams).apply {
            val visiblePad = (dp(TAB_WIDTH_DP - EDGE_HIDDEN_DP) - dp(20)) / 2
            gravity = (if (start) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
            if (start) { marginEnd = visiblePad; marginStart = 0 } else { marginStart = visiblePad; marginEnd = 0 }
        }

        val docked = state == State.DOCKED
        pill.visibility = if (state == State.EXPANDED) VISIBLE else GONE
        circle.visibility = if (docked) GONE else VISIBLE
        halo.visibility = if (docked) GONE else VISIBLE
        tab.visibility = if (docked) VISIBLE else GONE

        if (unread > 0 && !docked) {
            badge.text = if (unread > 99) "99+" else unread.toString()
            badge.visibility = VISIBLE
            (badge.layoutParams as LayoutParams).apply {
                gravity = Gravity.TOP or horizontal
                if (start) { marginStart = circleSize - dp(16); marginEnd = 0 } else { marginEnd = -dp(4); marginStart = 0 }
            }
        } else {
            badge.visibility = GONE
        }

        val description = accessibilityText(titleText)
        circle.contentDescription = description
        pill.contentDescription = description
        tab.contentDescription = resources.getString(R.string.atalia_launcher_show, titleText.lowercase())
        requestLayout()

        if (positionedBySdk) applySdkPosition()
        translationX = edgeShift()
    }

    private fun accessibilityText(text: String): String =
        if (unread > 0) resources.getQuantityString(R.plurals.atalia_launcher_unread, unread, text, unread) else text

    /** Aba na borda: empurra o botão para fora até a parte escondida passar da tela. */
    private fun edgeShift(): Float {
        if (state != State.DOCKED) return 0f
        val lp = layoutParams as? MarginLayoutParams
        val start = side == AtaliaLauncherOptions.Side.START
        val margin = (if (start) lp?.marginStart else lp?.marginEnd) ?: 0
        return (margin + dp(INSET_DP) + dp(EDGE_HIDDEN_DP)) * outwardSign()
    }

    /** +1 empurra para a borda do lado do botão; -1, para dentro da tela. */
    private fun outwardSign(): Float = if (side == AtaliaLauncherOptions.Side.START) -1f else 1f

    // Gestos

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var baseTx = 0f
        var baseTy = 0f
        var moved = false
        var tracker: VelocityTracker? = null

        val button = OnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    baseTx = translationX
                    baseTy = translationY
                    moved = false
                    tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                    animate().cancel()
                    press(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (moved) {
                        if (options.draggable && positionedBySdk) {
                            translationX = baseTx + dx
                            translationY = baseTy + dy
                        } else {
                            // Parado: segue o dedo com resistência, como um elástico.
                            translationX = baseTx + rubber(dx)
                            translationY = baseTy + rubber(dy)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracker?.addMovement(event)
                    tracker?.computeCurrentVelocity(1000)
                    val velocityX = tracker?.xVelocity ?: 0f
                    tracker?.recycle()
                    tracker = null
                    press(false)
                    val dx = event.rawX - downX
                    val outward = (dx * outwardSign()) > 0
                    when {
                        !moved && event.actionMasked == MotionEvent.ACTION_UP -> {
                            touched.performClick()
                        }
                        options.dockable && outward && (abs(dx) > dp(DOCK_DISTANCE_DP) || abs(velocityX) > dp(DOCK_VELOCITY_DP)) -> {
                            springBack(baseTx, baseTy, quick = true)
                            dock()
                        }
                        options.draggable && positionedBySdk -> settleAfterDrag()
                        else -> springBack(baseTx, baseTy)
                    }
                }
            }
            true
        }
        circle.setOnTouchListener(button)
        pill.setOnTouchListener(button)
        circle.setOnClickListener { onTap() }
        pill.setOnClickListener { onTap() }

        // Aba: tocar ou puxar para dentro traz o botão de volta.
        tab.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    baseTx = translationX
                    moved = false
                    tab.animate().scaleX(1.08f).scaleY(1.08f).setDuration(90).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    if (!moved && abs(dx) > slop) moved = true
                    if (moved && dx * outwardSign() < 0) translationX = baseTx + rubber(dx)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tab.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                    val inward = (event.rawX - downX) * outwardSign() < -dp(UNDOCK_DISTANCE_DP)
                    if ((!moved && event.actionMasked == MotionEvent.ACTION_UP) || inward) {
                        if (!moved) touched.performClick()
                        translationX = baseTx
                        undock(animate = true)
                    } else {
                        springBack(baseTx, translationY)
                    }
                }
            }
            true
        }
        tab.setOnClickListener {}
    }

    private fun onTap() {
        haptic(HapticFeedbackConstants.VIRTUAL_KEY)
        options.onClick?.invoke() ?: Atalia.open(context)
    }

    /** Halo e leve encolhida enquanto o dedo está no botão. */
    private fun press(down: Boolean) {
        if (down) {
            circle.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
            halo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
        } else {
            circle.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(2.2f)).start()
            halo.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).setDuration(220).start()
        }
    }

    /** Deslocamento com resistência: cresce cada vez menos até [RUBBER_MAX_DP]. */
    private fun rubber(distance: Float): Float {
        val max = dp(RUBBER_MAX_DP).toFloat()
        return sign(distance) * max * (1f - exp(-abs(distance) / (max * 2.2f)))
    }

    private fun springBack(tx: Float, ty: Float, quick: Boolean = false) {
        animate().translationX(tx).translationY(ty).setDuration(if (quick) 120 else 380)
            .setInterpolator(if (quick) AccelerateInterpolator() else OvershootInterpolator(2.4f)).start()
    }

    /** Solta o arrasto livre: gruda na lateral mais próxima e fica na altura escolhida, sem sair da tela. */
    private fun settleAfterDrag() {
        val container = parent as? View ?: return
        val centerX = left + translationX + width / 2f
        draggedSide = if (centerX < container.width / 2f) AtaliaLauncherOptions.Side.START else AtaliaLauncherOptions.Side.END
        val minTy = -(top - systemBars.top).toFloat()
        translationY = translationY.coerceIn(minTy, 0f)
        render()
    }

    private fun haptic(feedback: Int) {
        performHapticFeedback(feedback)
    }

    // Posição dada pelo SDK (Atalia.showLauncher)

    internal fun positionOnScreen() {
        positionedBySdk = true
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            applySdkPosition()
            insets
        }
        applySdkPosition()
    }

    private fun applySdkPosition() {
        val lp = layoutParams as? LayoutParams ?: return
        val start = side == AtaliaLauncherOptions.Side.START
        val horizontal = (dp(options.horizontalMarginDp) - dp(INSET_DP)).coerceAtLeast(0)
        val gravity = Gravity.BOTTOM or if (start) Gravity.START else Gravity.END
        val marginStart = horizontal + systemBars.left
        val marginEnd = horizontal + systemBars.right
        val bottom = (dp(options.bottomMarginDp) - dp(INSET_DP)).coerceAtLeast(0) + systemBars.bottom
        if (lp.gravity == gravity && lp.marginStart == marginStart && lp.marginEnd == marginEnd && lp.bottomMargin == bottom) return
        lp.gravity = gravity
        lp.marginStart = marginStart
        lp.marginEnd = marginEnd
        lp.bottomMargin = bottom
        layoutParams = lp
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun parseColor(hex: String?): Int? = hex?.let { runCatching { Color.parseColor(it) }.getOrNull() }

    private companion object {
        const val CIRCLE_DP = 56
        const val INSET_DP = 12
        const val HALO_DP = 9
        const val TAB_WIDTH_DP = 48
        const val EDGE_HIDDEN_DP = 16
        const val RUBBER_MAX_DP = 26
        const val DOCK_DISTANCE_DP = 32
        const val DOCK_VELOCITY_DP = 600
        const val UNDOCK_DISTANCE_DP = 16
        val DEFAULT_COLOR = Color.parseColor("#1036F2")
    }
}
