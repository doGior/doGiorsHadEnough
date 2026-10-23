package it.dogior.hadEnough.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.widget.ScrollView
import androidx.core.widget.doAfterTextChanged
import java.util.Calendar
import java.util.Locale
import android.text.InputType
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import it.dogior.hadEnough.StreamCenterHomeSectionDefinition
import it.dogior.hadEnough.StreamCenterPlugin

abstract class StreamCenterSectionEditingFragment : StreamCenterBaseSettingsFragment() {
    private var activeDialog: AlertDialog? = null
    private var titlePlaceholdersDialog: AlertDialog? = null

    protected abstract fun onSectionsEdited()

    override fun onDestroyView() {
        titlePlaceholdersDialog?.dismiss()
        titlePlaceholdersDialog = null
        activeDialog?.dismiss()
        activeDialog = null
        super.onDestroyView()
    }

    protected fun moveSection(key: String, target: Int) {
        val keys = StreamCenterPlugin.getOrderedHomeSections(sharedPref).map { it.key }.toMutableList()
        val index = keys.indexOf(key)
        if (index < 0 || target !in keys.indices || index == target) return
        keys.add(target, keys.removeAt(index))
        StreamCenterPlugin.saveHomeSectionOrder(sharedPref, keys)
        onSectionsEdited()
    }

    protected fun editSection(
        section: StreamCenterHomeSectionDefinition,
        countManagedBySource: Boolean = false,
        onDelete: (() -> Unit)? = null,
    ) {
        val sections = StreamCenterPlugin.getOrderedHomeSections(sharedPref)
        val defaultTitle = StreamCenterPlugin.getDefaultHomeSectionTitle(section)
        val title = input(StreamCenterPlugin.getHomeSectionTitleTemplate(sharedPref, section)).apply {
            layoutParams = verticalParams(top = 4)
        }
        val count = input(StreamCenterPlugin.getHomeSectionCount(sharedPref, section).toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = verticalParams(top = 4)
        }
        val position = input((sections.indexOfFirst { it.key == section.key } + 1).toString()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = verticalParams(top = 4)
        }
        resetOnLongPress(title, "Nome della sezione") { title.setText(defaultTitle) }
        resetOnLongPress(count, "Numero di titoli") { count.setText(section.defaultCount.toString()) }
        resetOnLongPress(position, "Posizione della sezione") {
            val defaults = StreamCenterPlugin.getAllHomeSections(sharedPref)
                .sortedBy { StreamCenterPlugin.homeSectionCategoryRank(sharedPref, it) }
            position.setText((defaults.indexOfFirst { it.key == section.key } + 1).toString())
        }
        val content = rootContainer().apply {
            addView(bodyText("Nome della sezione", 13)); addView(title)
            if (!countManagedBySource) {
                addView(bodyText("Numero massimo di titoli per pagina", 13)); addView(count)
            } else addView(bodyText("La quantità di titoli per pagina è gestita dalla fonte", 13))
            addView(bodyText("Posizione (1–${sections.size})", 13)); addView(position)
        }
        val header = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(18), dp(24), 0)
            addView(dialogTitle("Modifica sezione").apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(iconButton(
                symbol = "ⓘ",
                description = "Segnaposto disponibili per i titoli",
                accent = COLOR_ACCENT,
                size = 30,
            ) { showTitlePlaceholdersDialog() })
        }
        val builder = AlertDialog.Builder(requireContext()).setCustomTitle(header).setView(content)
            .setNegativeButton("Annulla", null)
            .setPositiveButton("Salva") { _, _ ->
                val maximum = if (section.key in setOf("tv_top10", "movie_top10")) 10 else StreamCenterPlugin.MAX_HOME_COUNT
                val amount = (count.text.toString().toIntOrNull() ?: section.defaultCount)
                    .coerceIn(StreamCenterPlugin.MIN_HOME_COUNT, maximum)
                sharedPref?.edit()?.apply {
                    putString(StreamCenterPlugin.sectionTitleKey(section.key), title.text.toString().trim().ifBlank { defaultTitle })
                    if (!countManagedBySource) putInt(StreamCenterPlugin.sectionCountKey(section.key), amount)
                }?.apply()
                val target = (position.text.toString().toIntOrNull() ?: 1).coerceIn(1, sections.size) - 1
                moveSection(section.key, target)
            }
        onDelete?.let { delete -> builder.setNeutralButton("Elimina") { _, _ -> delete() } }
        showDialog(builder.create())
    }

    private fun showTitlePlaceholdersDialog() {
        val ctx = context ?: return
        val accent = COLOR_ACCENT
        val calendar = Calendar.getInstance(Locale.ITALY).apply {
            firstDayOfWeek = Calendar.MONDAY
            minimalDaysInFirstWeek = 4
        }
        val dayOfMonth = calendar.get(Calendar.DAY_OF_MONTH)
        val monthNumber = calendar.get(Calendar.MONTH) + 1
        val weekday = listOf(
            "Domenica", "Lunedi", "Martedi", "Mercoledi",
            "Giovedi", "Venerdi", "Sabato",
        )[calendar.get(Calendar.DAY_OF_WEEK) - 1]
        val month = listOf(
            "Gennaio", "Febbraio", "Marzo", "Aprile", "Maggio", "Giugno",
            "Luglio", "Agosto", "Settembre", "Ottobre", "Novembre", "Dicembre",
        )[calendar.get(Calendar.MONTH)]
        val year = calendar.get(Calendar.YEAR)
        val paddedMonth = String.format(Locale.ITALY, "%02d", monthNumber)
        val placeholders = listOf(
            Triple(
                "%Data%",
                "Data completa",
                String.format(Locale.ITALY, "%02d/%02d/%04d", dayOfMonth, monthNumber, year),
            ),
            Triple("%d%", "Giorno del mese", "4"),
            Triple("%dd%", "Giorno del mese a due cifre", "04"),
            Triple("%ddd%", "Giorno della settimana abbreviato", weekday.take(3)),
            Triple("%dddd%", "Giorno della settimana completo", weekday),
            Triple("%m%", "Mese numerico", "5"),
            Triple("%mm%", "Mese numerico a due cifre", "05"),
            Triple("%mmm%", "Mese abbreviato", month.take(3)),
            Triple("%mmmm%", "Nome del mese completo", month),
            Triple("%yy%", "Anno a due cifre", String.format(Locale.ITALY, "%02d", year % 100)),
            Triple("%yyyy%", "Anno a quattro cifre", year.toString()),
            Triple("%Giorno%", "Giorno della settimana", weekday),
            Triple("%GiornoNumerico%", "Numero del giorno del mese", dayOfMonth.toString()),
            Triple("%Mese%", "Nome del mese", month),
            Triple("%MeseNumerico%", "Numero del mese a due cifre", paddedMonth),
            Triple("%Anno%", "Anno corrente", year.toString()),
            Triple("%Settimana%", "Numero della settimana corrente", calendar.get(Calendar.WEEK_OF_YEAR).toString()),
            Triple("%Canali%", "Numero dei canali selezionati: disponibile solo nelle sezioni Canali", ""),
            Triple("%Totale%", "Numero degli elementi trovati: disponibile in tutte le sezioni", ""),
        )
        fun resolvePreview(value: String): String {
            return StreamCenterPlugin.resolveHomeTitlePlaceholders(value, calendar)
        }
        val exampleTitle = "Anime: calendario (%Giorno%)"
        val previewText = titleText(resolvePreview(exampleTitle), 14, false).apply {
            setTextColor(Color.parseColor(COLOR_TEXT))
            setPadding(0, dp(4), 0, 0)
        }
        val titleInput = input("").apply {
            hint = "Es. Anime: calendario (%Giorno%)"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(5)
            }
            doAfterTextChanged { editable ->
                val value = editable?.toString().orEmpty()
                previewText.text = resolvePreview(value.ifBlank { exampleTitle })
                previewText.setTextColor(Color.parseColor(COLOR_TEXT))
            }
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(13))
                background = cardBackground(COLOR_CARD_ALT, tint(accent, "66"), 14)
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(iconBadge("✎", accent, size = 30, marginEnd = 9))
                    addView(titleText("Prova un nome", 13, true).apply {
                        setTextColor(Color.parseColor(accent))
                    })
                })
                addView(titleInput)
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(11), dp(8), dp(11), dp(9))
                    background = cardBackground(COLOR_INPUT_FILL, tint(accent, "44"), 10)
                    layoutParams = verticalParams(top = 10)
                    addView(bodyText("Anteprima", 10).apply {
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.parseColor(accent))
                    })
                    addView(previewText)
                })
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(11), dp(8), dp(11), dp(8))
                background = cardBackground(tint(accent, "12"), tint(accent, "44"), 11)
                layoutParams = verticalParams(top = 10)
                addView(iconBadge("ⓘ", accent, size = 28, marginEnd = 9))
                addView(bodyText("Tocca per copiare • Tieni premuto per inserire", 11).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
            })
            placeholders.forEach { (token, description, example) ->
                val summary = bodyText(description, 11).apply {
                    if (example.isNotBlank()) {
                        text = SpannableString("$description  •  $example").apply {
                            val exampleStart = length - example.length
                            setSpan(
                                StyleSpan(Typeface.BOLD),
                                exampleStart,
                                length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                            setSpan(
                                ForegroundColorSpan(Color.parseColor(accent)),
                                exampleStart,
                                length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                            )
                        }
                    }
                }
                val copyBadge = iconBadge("⧉", accent, size = 32, marginEnd = 0)
                val placeholderRow = settingsRow(
                    title = token,
                    accent = accent,
                    fillColor = COLOR_INPUT_FILL,
                    summaryView = summary,
                    trailingViews = listOf(copyBadge),
                    topMargin = 6,
                ) {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                        ?.setPrimaryClip(ClipData.newPlainText("Segnaposto", token))
                    saveToast("$token copiato")
                }.apply {
                    title.typeface = Typeface.MONOSPACE
                    title.setTextColor(Color.parseColor(accent))
                    view.contentDescription = "Copia $token. Tieni premuto per inserirlo nel nome"
                    view.setOnLongClickListener {
                        val editable = titleInput.text
                        val start = titleInput.selectionStart.takeIf { it >= 0 } ?: editable.length
                        val end = titleInput.selectionEnd.takeIf { it >= 0 } ?: start
                        val insertionStart = minOf(start, end)
                        editable.replace(insertionStart, maxOf(start, end), token)
                        titleInput.requestFocus()
                        titleInput.setSelection((insertionStart + token.length).coerceAtMost(editable.length))
                        saveToast("$token inserito")
                        true
                    }
                }
                addView(placeholderRow.view)
            }
        }
        val editor = content.getChildAt(0)
        content.removeViewAt(0)
        content.setPadding(0, 0, 0, 0)
        val placeholderScroll = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        val dialogContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(editor)
            addView(
                placeholderScroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(400)).apply {
                    topMargin = dp(8)
                },
            )
        }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Segnaposto disponibili"))
            .setView(dialogContent)
            .setPositiveButton("Chiudi", null)
            .create()
        titlePlaceholdersDialog = dialog
        dialog.setOnDismissListener { titlePlaceholdersDialog = null }
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    protected fun showDialog(dialog: AlertDialog, onShow: (() -> Unit)? = null) {
        activeDialog = dialog
        applyDialogBackdrop(dialog, onShow = onShow)
        dialog.show()
    }
}
