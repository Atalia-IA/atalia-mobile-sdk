package ai.atalia.chat

import ai.atalia.chat.internal.ChannelBranding
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Posição e comportamento do botão flutuante. A aparência vem do painel. */
data class AtaliaLauncherOptions(
    /** Lado da tela. Nulo = o lado do painel. */
    val side: Side? = null,
    /** Distância da lateral (em [Atalia.showLauncher]). */
    val horizontalMarginDp: Int = 16,
    /** Distância do fim da tela, além da barra de navegação (em [Atalia.showLauncher]). Aumente para ficar acima de uma barra de abas. */
    val bottomMarginDp: Int = 16,
    /** Texto do botão aberto. Nulo = o do painel ("Fale conosco"). */
    val text: String? = null,
    /** Nula = a cor do botão no painel. */
    @ColorInt val color: Int? = null,
    val collapse: Collapse = Collapse.CIRCLE,
    /** Tempo com o texto antes de recolher. Volta a abrir quando chega mensagem nova. */
    val collapseAfterMs: Long = 5_000,
    /** O usuário pode arrastar o botão; ao soltar, ele gruda na lateral mais próxima. Só em [Atalia.showLauncher]. */
    val draggable: Boolean = false,
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

        /** Fica sempre com o texto. */
        NEVER,
    }
}

/**
 * Botão flutuante que abre o chat. A aparência (cor, texto e lado) vem do painel da Atalia, a mesma
 * do botão do site; posição e comportamento são do app.
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
            expanded = true
            render(animate = true)
            scheduleCollapse()
        }

    private enum class Shape { PILL, CIRCLE, EDGE }

    private val shapeDrawable = GradientDrawable()
    private val body = LinearLayout(context)
    private val icon = ImageView(context)
    private val label = TextView(context)
    private val badge = TextView(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null

    private var branding: ChannelBranding? = null
    private var unread = 0
    private var lastUnread: Int? = null
    private var chatOpen = false
    private var expanded = true
    private var shape: Shape? = null
    private var side = AtaliaLauncherOptions.Side.END
    /** Lado escolhido pelo usuário ao arrastar (vence o das opções). */
    private var draggedSide: AtaliaLauncherOptions.Side? = null

    /** Posto por [Atalia.showLauncher]: o SDK cuida da gravidade, das margens e das barras do sistema. */
    internal var positionedBySdk = false
    private var systemBars = WindowInsetsCompat.CONSUMED.getInsets(WindowInsetsCompat.Type.systemBars())

    private val collapseRunnable = Runnable {
        expanded = false
        render(animate = true)
    }

    init {
        clipChildren = false
        clipToPadding = false
        // Espaço para a sombra e para o contador, que sai um pouco do botão.
        setPadding(dp(INSET_DP), dp(INSET_DP), dp(INSET_DP), dp(INSET_DP))

        body.orientation = LinearLayout.HORIZONTAL
        body.gravity = Gravity.CENTER_VERTICAL
        body.background = shapeDrawable
        body.elevation = dp(6).toFloat()
        body.isClickable = true
        body.isFocusable = true
        body.setOnClickListener { onTap() }
        icon.setImageResource(R.drawable.atalia_ic_chat)
        label.setTextColor(Color.WHITE)
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        label.typeface = Typeface.DEFAULT_BOLD
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        body.addView(icon, LinearLayout.LayoutParams(dp(24), dp(24)))
        body.addView(label, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        addView(body, LayoutParams(LayoutParams.WRAP_CONTENT, dp(HEIGHT_DP)))

        badge.setTextColor(Color.WHITE)
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        badge.typeface = Typeface.DEFAULT_BOLD
        badge.gravity = Gravity.CENTER
        badge.minWidth = dp(20)
        badge.setPadding(dp(5), 0, dp(5), 0)
        badge.background = GradientDrawable().apply {
            setColor(Color.parseColor("#E5484D"))
            cornerRadius = dp(10).toFloat()
            setStroke((1.5f * resources.displayMetrics.density).roundToInt(), Color.WHITE)
        }
        badge.elevation = dp(7).toFloat()
        badge.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, dp(20), Gravity.TOP or Gravity.END))

        setupDrag()
        render(animate = false)
    }

    /** Tira o botão da tela. */
    fun remove() {
        (parent as? ViewGroup)?.removeView(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        scope.launch { Atalia.brandingFlow.collect { branding = it; render(animate = false) } }
        scope.launch { Atalia.isChatOpen.collect { chatOpen = it; render(animate = false) } }
        scope.launch {
            Atalia.unreadCount.collect { count ->
                // Mensagem nova: o botão abre de novo com o texto, como um aviso.
                val previous = lastUnread
                lastUnread = count
                unread = count
                if (previous != null && count > previous) expand() else render(animate = false)
            }
        }
        if (positionedBySdk) ViewCompat.requestApplyInsets(this)
        scheduleCollapse()
    }

    override fun onDetachedFromWindow() {
        scope?.cancel()
        scope = null
        mainHandler.removeCallbacks(collapseRunnable)
        super.onDetachedFromWindow()
    }

    private fun onTap() {
        if (shape == Shape.EDGE) {
            expand()
            return
        }
        options.onClick?.invoke() ?: Atalia.open(context)
    }

    private fun expand() {
        expanded = true
        render(animate = true)
        scheduleCollapse()
    }

    private fun scheduleCollapse() {
        mainHandler.removeCallbacks(collapseRunnable)
        if (expanded && options.collapse != AtaliaLauncherOptions.Collapse.NEVER) {
            mainHandler.postDelayed(collapseRunnable, options.collapseAfterMs.coerceAtLeast(0))
        }
    }

    private fun render(animate: Boolean) {
        val branding = branding
        visibility = if (branding == null || chatOpen) View.GONE else View.VISIBLE
        if (branding == null) return

        side = draggedSide ?: options.side ?: if (branding.buttonOnStart) AtaliaLauncherOptions.Side.START else AtaliaLauncherOptions.Side.END
        val next = when {
            expanded -> Shape.PILL
            options.collapse == AtaliaLauncherOptions.Collapse.CIRCLE -> Shape.CIRCLE
            options.collapse == AtaliaLauncherOptions.Collapse.EDGE -> Shape.EDGE
            else -> Shape.PILL
        }
        if (animate && next != shape) TransitionManager.beginDelayedTransition(this, AutoTransition().setDuration(220))
        shape = next

        val text = options.text ?: branding.buttonText
        shapeDrawable.setColor(options.color ?: parseColor(branding.buttonColor) ?: DEFAULT_COLOR)
        label.text = text
        val start = side == AtaliaLauncherOptions.Side.START
        (body.layoutParams as LayoutParams).gravity = Gravity.BOTTOM or if (start) Gravity.START else Gravity.END
        when (next) {
            Shape.PILL -> {
                icon.setImageResource(R.drawable.atalia_ic_chat)
                icon.rotation = 0f
                label.visibility = View.VISIBLE
                shapeDrawable.cornerRadius = dp(HEIGHT_DP / 2).toFloat()
                body.layoutParams.width = LayoutParams.WRAP_CONTENT
                body.setPadding(dp(18), 0, dp(22), 0)
            }
            Shape.CIRCLE -> {
                icon.setImageResource(R.drawable.atalia_ic_chat)
                icon.rotation = 0f
                label.visibility = View.GONE
                shapeDrawable.cornerRadius = dp(HEIGHT_DP / 2).toFloat()
                body.layoutParams.width = dp(HEIGHT_DP)
                body.setPadding(dp(16), 0, dp(16), 0)
            }
            Shape.EDGE -> {
                // A parte de fora fica além da borda da tela: sobra uma aba com a ponta arredondada.
                icon.setImageResource(R.drawable.atalia_ic_chevron)
                icon.rotation = if (start) 180f else 0f
                label.visibility = View.GONE
                shapeDrawable.cornerRadius = dp(16).toFloat()
                body.layoutParams.width = dp(EDGE_WIDTH_DP)
                val visiblePad = (dp(EDGE_WIDTH_DP - EDGE_HIDDEN_DP) - dp(24)) / 2
                if (start) body.setPadding(dp(EDGE_HIDDEN_DP) + visiblePad, 0, 0, 0) else body.setPadding(visiblePad, 0, 0, 0)
            }
        }
        body.requestLayout()

        if (unread > 0 && next != Shape.EDGE) {
            badge.text = if (unread > 99) "99+" else unread.toString()
            badge.visibility = View.VISIBLE
            (badge.layoutParams as LayoutParams).gravity = Gravity.TOP or if (start && next == Shape.CIRCLE) Gravity.START else Gravity.END
        } else {
            badge.visibility = View.GONE
        }
        body.contentDescription = accessibilityText(text, next)

        if (positionedBySdk) applySdkPosition()
        translationX = edgeShift()
    }

    private fun accessibilityText(text: String, shape: Shape): String {
        val base = if (shape == Shape.EDGE) resources.getString(R.string.atalia_launcher_show, text.lowercase()) else text
        return if (unread > 0) resources.getQuantityString(R.plurals.atalia_launcher_unread, unread, base, unread) else base
    }

    /** Aba na borda: empurra o botão para fora até a parte escondida passar da tela. */
    private fun edgeShift(): Float {
        if (shape != Shape.EDGE) return 0f
        val lp = layoutParams as? MarginLayoutParams
        val start = side == AtaliaLauncherOptions.Side.START
        val margin = (if (start) lp?.marginStart else lp?.marginEnd) ?: 0
        val distance = margin + dp(INSET_DP) + dp(EDGE_HIDDEN_DP)
        return (if (start) -distance else distance).toFloat()
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

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDrag() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startTx = 0f
        var startTy = 0f
        var dragging = false
        body.setOnTouchListener { _, event ->
            if (!options.draggable || !positionedBySdk) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startTx = translationX
                    startTy = translationY
                    dragging = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        body.isPressed = false
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        translationX = startTx + dx
                        translationY = startTy + dy
                    }
                    dragging
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) return@setOnTouchListener false
                    dragging = false
                    settleAfterDrag()
                    true
                }
                else -> dragging
            }
        }
    }

    /** Solta: gruda na lateral mais próxima e fica na altura escolhida, sem sair da tela. */
    private fun settleAfterDrag() {
        val container = parent as? View ?: return
        val centerX = left + translationX + width / 2f
        draggedSide = if (centerX < container.width / 2f) AtaliaLauncherOptions.Side.START else AtaliaLauncherOptions.Side.END
        val minTy = -(top - systemBars.top).toFloat()
        translationY = translationY.coerceIn(minTy, 0f)
        render(animate = false)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun parseColor(hex: String?): Int? = hex?.let { runCatching { Color.parseColor(it) }.getOrNull() }

    private companion object {
        const val HEIGHT_DP = 56
        const val INSET_DP = 12
        const val EDGE_WIDTH_DP = 48
        const val EDGE_HIDDEN_DP = 16
        val DEFAULT_COLOR = Color.parseColor("#1036F2")
    }
}
