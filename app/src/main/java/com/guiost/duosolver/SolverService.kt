package com.guiost.duosolver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class SolverService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: SolverService? = null
        const val DUO = "com.duolingo"

        val SKIP_AUDIO = listOf(
            "NAO POSSO OUVIR AGORA", "NAO POSSO FALAR AGORA", "NAO POSSO ESCUTAR AGORA",
            "CAN'T LISTEN NOW", "CANT LISTEN NOW", "CAN'T SPEAK NOW", "CANT SPEAK NOW",
            "NO PUEDO ESCUCHAR AHORA", "NO PUEDO HABLAR AHORA"
        )
        val CONTINUE = listOf(
            "CONTINUAR", "CONTINUE", "ENTENDI", "GOT IT", "PROSSEGUIR", "PROXIMO", "NEXT",
            "NAO, OBRIGADO", "NAO OBRIGADO", "NO THANKS", "AGORA NAO", "NOT NOW", "TALVEZ DEPOIS", "MAYBE LATER"
        )
        val CHECK = listOf("VERIFICAR", "CHECK", "COMPROBAR")
        val SKIP = listOf("PULAR", "SKIP", "SALTAR")
    }

    data class Item(val node: AccessibilityNodeInfo, val label: String, val norm: String, val rect: Rect, val editable: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    @Volatile private var running = false
    private var lastSig = ""
    private var sameCount = 0

    private var wm: WindowManager? = null
    private var overlay: LinearLayout? = null
    private var btn: TextView? = null
    private var statusTv: TextView? = null

    // ---------------- ciclo de vida ----------------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        createOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        instance = null
        try { overlay?.let { wm?.removeView(it) } } catch (_: Exception) {}
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---------------- overlay ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun createOverlay() {
        val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8); y = dp(140)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat(); setColor(Color.argb(210, 20, 20, 20))
            }
            setPadding(dp(4), dp(4), dp(10), dp(4))
        }
        val b = TextView(this).apply {
            text = "▶"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(88, 204, 2)) }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        val s = TextView(this).apply {
            text = "Parado"; textSize = 12f; setTextColor(Color.WHITE); setPadding(dp(8), 0, 0, 0)
            maxWidth = dp(170)
        }
        layout.addView(b); layout.addView(s)

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        b.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt(); params.y = startY + dy.toInt()
                        try { w.updateViewLayout(layout, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) toggle(); true }
                else -> false
            }
        }

        try { w.addView(layout, params) } catch (_: Exception) { return }
        overlay = layout; btn = b; statusTv = s
    }

    private fun toggle() {
        running = !running
        btn?.text = if (running) "⏸" else "▶"
        (btn?.background as? GradientDrawable)?.setColor(if (running) Color.rgb(255, 75, 75) else Color.rgb(88, 204, 2))
        if (running) {
            lastSig = ""; sameCount = 0
            status("Rodando…")
            main.post(tick)
        } else {
            main.removeCallbacks(tick)
            status("Parado")
        }
    }

    private fun status(t: String) = main.post { statusTv?.text = t }

    private fun prefs() = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    private fun delay() = prefs().getInt(Prefs.DELAY, Prefs.DEFAULT_DELAY).toLong()

    // ---------------- loop ----------------

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            if (busy.compareAndSet(false, true)) {
                worker.execute {
                    try { step() } catch (e: Exception) { status("Erro: ${e.message?.take(80)}") }
                    finally { busy.set(false) }
                }
            }
            main.postDelayed(this, 350)
        }
    }

    private fun norm(s: String): String {
        val n = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return n.uppercase().replace('’', '\'').trim()
    }

    private fun collect(root: AccessibilityNodeInfo): List<Item> {
        val out = ArrayList<Item>()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 60) return
            if (n.isVisibleToUser) {
                val t = n.text?.toString()?.trim().orEmpty()
                val d = n.contentDescription?.toString()?.trim().orEmpty()
                val label = when {
                    t.isNotEmpty() -> t
                    d.isNotEmpty() -> d
                    else -> ""
                }
                if (label.isNotEmpty() || n.isEditable) {
                    val r = Rect(); n.getBoundsInScreen(r)
                    if (r.width() > 0 && r.height() > 0)
                        out.add(Item(n, label, norm(label), r, n.isEditable))
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    private fun clickableOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var c: AccessibilityNodeInfo? = n
        var i = 0
        while (c != null && i < 6) {
            if (c.isClickable) return c
            c = c.parent; i++
        }
        return null
    }

    private fun isEnabled(it: Item): Boolean {
        val c = clickableOf(it.node) ?: it.node
        return it.node.isEnabled && c.isEnabled
    }

    private fun find(items: List<Item>, labels: List<String>): Item? =
        items.firstOrNull { item -> labels.any { item.norm == it } && isEnabled(item) }

    private fun tap(item: Item) {
        val c = clickableOf(item.node)
        val ok = try { c?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true } catch (_: Exception) { false }
        if (!ok) gestureTap(item.rect.centerX().toFloat(), item.rect.centerY().toFloat())
    }

    private fun gestureTap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 50))
            .build()
        dispatchGesture(g, null, null)
        Thread.sleep(80)
    }

    private fun step() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != DUO) {
            status("Abra o Duolingo")
            return
        }
        val items = collect(root)
        if (items.isEmpty()) return

        // 1) escuta / fala → "não posso ouvir/falar agora"
        find(items, SKIP_AUDIO)?.let {
            status("Pulando escuta/fala")
            tap(it); Thread.sleep(delay() + 200); return
        }

        // 2) telas de feedback / avisos → continuar
        find(items, CONTINUE)?.let {
            tap(it); lastSig = ""; sameCount = 0; Thread.sleep(delay() + 150); return
        }

        val sig = items.joinToString("|") { it.label }
        val check = find(items, CHECK)

        if (sig == lastSig) {
            sameCount++
            // já respondemos esta tela; se o "Verificar" ficou ativo, aperta
            if (check != null) { tap(check); Thread.sleep(delay()); return }
            if (sameCount > 25) { // ~9s travado
                find(items, SKIP)?.let { status("Travado → Pular"); tap(it) }
                sameCount = 0; lastSig = ""
            }
            return
        }

        // 3) nova tela → pergunta ao Gemini
        lastSig = sig; sameCount = 0
        val key = prefs().getString(Prefs.KEY, "").orEmpty()
        if (key.isEmpty()) { status("Configure a chave do Gemini no app"); return }
        val model = prefs().getString(Prefs.MODEL, Prefs.DEFAULT_MODEL).orEmpty().ifEmpty { Prefs.DEFAULT_MODEL }

        status("Pensando…")
        val t0 = System.currentTimeMillis()
        val raw = Gemini.ask(key, model, buildPrompt(items))
        val json = parseJson(raw) ?: run { status("Resposta inválida"); return }
        val ms = System.currentTimeMillis() - t0

        when (json.optString("action")) {
            "skip" -> {
                val s = find(items, SKIP) ?: find(collect(rootInActiveWindow ?: return), SKIP)
                if (s != null) { status("Pulando (${json.optString("kind")})"); tap(s); Thread.sleep(delay() + 200) }
                else status("Sem botão Pular — faça este manualmente")
                return
            }
            "answer" -> {
                status("Respondendo (${ms}ms)")
                val taps = json.optJSONArray("taps")
                if (taps != null) {
                    for (i in 0 until taps.length()) {
                        val idx = taps.optInt(i, -1)
                        if (idx in items.indices) { tap(items[idx]); Thread.sleep(delay()) }
                    }
                }
                val typeObj = json.optJSONObject("type")
                if (typeObj != null) {
                    val idx = typeObj.optInt("id", -1)
                    val target = items.getOrNull(idx)?.takeIf { it.editable } ?: items.firstOrNull { it.editable }
                    if (target != null) {
                        target.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, typeObj.optString("text"))
                        }
                        target.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        Thread.sleep(delay())
                    }
                }
                // aperta Verificar com a tela atualizada
                Thread.sleep(delay())
                val fresh = rootInActiveWindow?.let { collect(it) } ?: return
                find(fresh, CHECK)?.let { tap(it); Thread.sleep(delay()) }
                lastSig = fresh.joinToString("|") { it.label }
            }
            else -> status("Aguardando exercício…")
        }
    }

    private fun parseJson(raw: String): JSONObject? {
        val s = raw.indexOf('{'); val e = raw.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        return try { JSONObject(raw.substring(s, e + 1)) } catch (_: Exception) { null }
    }

    private fun buildPrompt(items: List<Item>): String {
        val sb = StringBuilder()
        sb.append(
            """
Você controla o app Duolingo (curso de ESPANHOL para falantes de português) lendo a árvore de acessibilidade da tela.
Abaixo estão os elementos visíveis: [id] texto {flags} @(x,y) (centro na tela, em pixels; y cresce para baixo).

Decida o que fazer e responda SOMENTE um JSON:
{"kind":"<tipo do exercício>","action":"answer"|"skip"|"none","taps":[ids em ordem],"type":{"id":<id do campo>,"text":"<texto>"}}

Regras:
- "none": não é um exercício (tela inicial, trilha, loja, perfil, carregando, etc).
- "skip": exercícios de ESCUTA (ouvir e escrever/selecionar o que ouviu), de FALA (falar/pronunciar), e exercícios cujo objetivo é escolher o PRONOME/SUJEITO correto (yo, tú, vos, él, ella, usted, nosotros, nosotras, vosotros, ellos, ellas, ustedes) para uma frase ou conjugação.
- "answer" para todo o resto:
  * Banco de palavras (montar frase tocando nas palavras): "taps" = ids das palavras do banco, na ordem correta da frase. Se já houver palavras erradas na área de resposta, toque nelas primeiro para removê-las. Palavras repetidas têm ids diferentes; use cada id uma vez.
  * Múltipla escolha / completar lacuna / escolher imagem: "taps" = [id da opção correta].
  * Combinar pares: "taps" = sequência de pares [esq1, dir1, esq2, dir2, ...] com todos os pares ainda disponíveis.
  * Campo de texto {edit}: use "type" com a tradução/resposta correta (sem tocar em nada); "taps" vazio.
- NUNCA inclua os botões Verificar/Check/Pular/Skip/Continuar nos taps; o app aperta sozinho.
- Ignore barra de progresso, corações, botões de áudio/alto-falante, dicas e o botão de fechar.

Elementos:
""".trimIndent()
        )
        sb.append('\n')
        items.forEachIndexed { i, it ->
            val flags = ArrayList<String>()
            if (it.editable) flags.add("edit")
            if (clickableOf(it.node) != null) flags.add("click")
            if (!isEnabled(it)) flags.add("disabled")
            sb.append("[$i] ${it.label.replace('\n', ' ')} {${flags.joinToString(",")}} @(${it.rect.centerX()},${it.rect.centerY()})\n")
        }
        return sb.toString()
    }
}
