package ai.atalia.chat.sample

import ai.atalia.chat.Atalia
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Loja fictícia para gravar os vídeos da documentação (só no build de debug): um app neutro por
 * trás do botão flutuante e da folha do chat, no lugar dos botões de teste do MainActivity.
 *
 *   adb shell am start -n ai.atalia.chat.sample/.DemoActivity
 */
class DemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F6F7FB"))
            setPadding(dp(20), dp(12), dp(20), dp(120))
            addView(text("Loja Exemplo", 26f, "#0F194A", bold = true))
            addView(text("Olá, Mariana", 15f, "#5A5F7D").apply { setPadding(0, dp(2), 0, dp(18)) })
            addView(banner())
            addView(text("Mais vendidos", 18f, "#0F194A", bold = true).apply { setPadding(0, dp(24), 0, dp(12)) })
            addView(products())
        }
        val scroll = ScrollView(this).apply { addView(content) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top)
            insets
        }
        setContentView(scroll)
        Atalia.showLauncher(this)
    }

    private fun banner(): View = FrameLayout(this).apply {
        background = rounded("#E5E9FF", 16)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Frete grátis", 18f, "#0F194A", bold = true))
            addView(text("Em compras acima de R$ 199", 14f, "#3A4063"))
        })
    }

    private fun products(): View = GridLayout(this).apply {
        columnCount = 2
        val items = listOf(
            "Caneca térmica" to "R$ 79,90", "Garrafa 750 ml" to "R$ 59,90",
            "Mochila urbana" to "R$ 189,00", "Fone sem fio" to "R$ 249,00",
            "Caderno pontilhado" to "R$ 39,90", "Luminária de mesa" to "R$ 129,00",
        )
        val tints = listOf("#FDE8D7", "#DDF3E6", "#E5E9FF", "#FFF0CC", "#F3E5FF", "#E0F2FE")
        items.forEachIndexed { i, (name, price) ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded("#FFFFFF", 14)
                setPadding(dp(10), dp(10), dp(10), dp(12))
                addView(View(context).apply { background = rounded(tints[i], 10) }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(96)))
                addView(text(name, 14f, "#0F194A").apply { setPadding(0, dp(10), 0, 0) })
                addView(text(price, 14f, "#0F194A", bold = true))
            }, GridLayout.LayoutParams(GridLayout.spec(i / 2), GridLayout.spec(i % 2, 1f)).apply {
                width = 0
                setMargins(if (i % 2 == 0) 0 else dp(6), 0, if (i % 2 == 0) dp(6) else 0, dp(12))
            })
        }
    }

    private fun text(value: String, size: Float, color: String, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.parseColor(color))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.START
    }

    private fun rounded(color: String, radiusDp: Int) = GradientDrawable().apply {
        setColor(Color.parseColor(color))
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
