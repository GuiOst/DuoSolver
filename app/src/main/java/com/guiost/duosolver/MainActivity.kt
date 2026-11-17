package com.guiost.duosolver

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun label(t: String, size: Float = 15f) = TextView(this).apply {
            text = t; textSize = size; setPadding(0, pad / 2, 0, pad / 4)
        }

        root.addView(label("DuoSolver", 26f))
        statusView = label("")
        root.addView(statusView)

        root.addView(label("Chave da API do Gemini (aistudio.google.com/apikey):"))
        val keyEdit = EditText(this).apply {
            setText(prefs.getString(Prefs.KEY, ""))
            hint = "AIza..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            isSingleLine = true
        }
        root.addView(keyEdit)

        root.addView(label("Modelo:"))
        val modelEdit = EditText(this).apply {
            setText(prefs.getString(Prefs.MODEL, Prefs.DEFAULT_MODEL))
            isSingleLine = true
        }
        root.addView(modelEdit)

        root.addView(label("Pausa entre ações (ms) — menor = mais rápido:"))
        val delayEdit = EditText(this).apply {
            setText(prefs.getInt(Prefs.DELAY, Prefs.DEFAULT_DELAY).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
        }
        root.addView(delayEdit)

        root.addView(Button(this).apply {
            text = "Salvar"
            setOnClickListener {
                prefs.edit()
                    .putString(Prefs.KEY, keyEdit.text.toString().trim())
                    .putString(Prefs.MODEL, modelEdit.text.toString().trim().ifEmpty { Prefs.DEFAULT_MODEL })
                    .putInt(Prefs.DELAY, delayEdit.text.toString().toIntOrNull()?.coerceIn(50, 3000) ?: Prefs.DEFAULT_DELAY)
                    .apply()
                Toast.makeText(this@MainActivity, "Salvo", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(Button(this).apply {
            text = "1) Ativar serviço de acessibilidade"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        root.addView(Button(this).apply {
            text = "Info do app (permitir configurações restritas)"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        })

        root.addView(Button(this).apply {
            text = "2) Abrir Duolingo"
            setOnClickListener {
                val i = packageManager.getLaunchIntentForPackage("com.duolingo")
                if (i != null) startActivity(i)
                else Toast.makeText(this@MainActivity, "Duolingo não encontrado", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(
            label(
                "Como usar:\n" +
                    "• Cole a chave do Gemini e toque em Salvar.\n" +
                    "• Ative \"DuoSolver\" em Acessibilidade. Se estiver bloqueado (Android 13+), " +
                    "abra Info do app → menu ⋮ → \"Permitir configurações restritas\" e tente de novo.\n" +
                    "• Vai aparecer um botão flutuante ▶. Entre numa lição do Duolingo e toque no ▶.\n" +
                    "• Escuta e fala são puladas com \"Não posso ouvir/falar agora\". " +
                    "Exercícios de escolher o pronome (yo/tú/nosotros…) são pulados com \"Pular\".\n" +
                    "• Arraste o botão para tirar da frente. Toque de novo para pausar.",
                14f
            )
        )

        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    override fun onResume() {
        super.onResume()
        statusView.text = if (SolverService.instance != null)
            "✅ Serviço ativo — botão flutuante disponível"
        else
            "⚠️ Serviço de acessibilidade desativado"
    }
}

object Prefs {
    const val NAME = "cfg"
    const val KEY = "api_key"
    const val MODEL = "model"
    const val DELAY = "delay"
    const val DEFAULT_MODEL = "gemini-2.5-flash"
    const val DEFAULT_DELAY = 150
}
