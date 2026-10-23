package it.dogior.hadEnough.settings

import it.dogior.hadEnough.*
import it.dogior.hadEnough.torrent.StreamCenterExtAvailability
import it.dogior.hadEnough.torrent.StreamCenterExtCloudflareSession
import it.dogior.hadEnough.torrent.StreamCenterExtContainLocation
import it.dogior.hadEnough.torrent.StreamCenterExtDomain
import it.dogior.hadEnough.torrent.StreamCenterExtDomainStatus
import it.dogior.hadEnough.torrent.StreamCenterExtTorrentClient
import it.dogior.hadEnough.torrent.StreamCenterTorrentKeywordGroup
import it.dogior.hadEnough.torrent.StreamCenterTorrentLanguageFilter
import it.dogior.hadEnough.torrent.StreamCenterTorrentMetadata
import it.dogior.hadEnough.torrent.StreamCenterTorrentPreferences
import it.dogior.hadEnough.torrent.StreamCenterTorrentVideoCodec
import it.dogior.hadEnough.util.StreamCenterVpnGuard
import it.dogior.hadEnough.util.StreamCenterLogger

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.edit
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.util.Locale

private const val CLOUDFLARE_CLEARANCE_POLL_INTERVAL_MS = 700L
private const val CLOUDFLARE_CLEARANCE_CLOSE_DELAY_MS = 900L
private const val CLOUDFLARE_ACCESS_CHECK_INTERVAL_MS = 5_000L

private data class TorrentChoice<T>(
    val title: String,
    val description: String,
    val value: T,
)

private data class TorrentToggleOption(
    val title: String,
    val enabled: Boolean,
    val onChanged: (Boolean) -> Unit,
)

private enum class TorrentSizeUnit(
    val label: String,
    val bytes: Long,
) {
    MB("MB", 1_048_576L),
    GB("GB", 1_073_741_824L),
}

class StreamCenterTorrentSettingsFragment : StreamCenterBaseSettingsFragment() {
    override val screenTitle: String = "Torrent"

    override fun screenAction(): SettingsScreenAction = SettingsScreenAction(
        label = "Ripristina",
        description = "Ripristina le impostazioni Torrent",
        onInvoke = { confirmTorrentReset() },
    )

    private var content: LinearLayout? = null
    private val optionRows = mutableListOf<View>()
    private var isExtAvailabilityCheckRunning = false
    private val extDomainStatuses = mutableMapOf<StreamCenterExtDomain, StreamCenterExtDomainStatus>()
    private var extAvailabilityStatusViews: Map<StreamCenterExtDomain, TextView> = emptyMap()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val root = rootContainer().apply {
            setPadding(paddingLeft, 0, paddingRight, paddingBottom)
            minimumHeight = standardSubmenuMinimumHeight()
        }
        content = root
        render()
        return scroll(root, fixedSubmenuHeight = true)
    }

    override fun onDestroyView() {
        content = null
        optionRows.clear()
        extDomainStatuses.clear()
        extAvailabilityStatusViews = emptyMap()
        isExtAvailabilityCheckRunning = false
        super.onDestroyView()
    }

    private fun render() {
        val root = content ?: return
        root.removeAllViews()
        optionRows.clear()
        val enabled = StreamCenterPlugin.isTorrentEnabled(sharedPref)
        root.addView(switchRow(
            title = "Usa i torrent",
            summary = "Aggiunge ai risultati i torrent trovati su EXT",
            checked = enabled,
            defaultChecked = StreamCenterPlugin.isTorrentEnabled(null),
            icon = "🧲",
            topMargin = 0,
            dimWhenOff = false,
        ) { isEnabled ->
            StreamCenterPlugin.setTorrentEnabled(sharedPref, isEnabled)
            optionRows.forEach { animateRowEnabledAppearance(it, isEnabled) }
        })

        root.addView(torrentSectionLabel("Collegamento"))
        root.addView(torrentSettingsRow(
            title = "Endpoint EXT",
            summary = endpointSummary(),
            icon = "🔗",
            onReset = {
                StreamCenterExtDomain.entries.forEach { domain ->
                    StreamCenterTorrentPreferences.setDomainEnabled(sharedPref, domain, domain.defaultEnabled)
                }
                StreamCenterTorrentPreferences.setDomainOrder(sharedPref, StreamCenterExtDomain.entries)
                render()
            },
        ) { showExtDomainsDialog() })
        if (StreamCenterPlugin.isPerformanceModeEnabled(sharedPref)) {
            root.addView(bodyText(
                "Modalità Prestazioni attiva: la ricerca dura meno e rispetta il numero di torrent scelto",
                12,
            ).apply {
                setPadding(dp(6), dp(8), dp(6), 0)
            }.also(optionRows::add))
        }

        root.addView(torrentSectionLabel("Ricerca"))
        root.addView(torrentSettingsRow(
            title = "Dove cercare",
            summary = torrentSearchLocationSummary(),
            icon = "🔎",
            onReset = {
                StreamCenterTorrentPreferences.setContainLocation(
                    sharedPref,
                    StreamCenterTorrentPreferences.read(null).containLocation,
                )
                render()
            },
        ) {
            showTorrentChoiceDialog(
                title = "Dove cercare",
                options = StreamCenterExtContainLocation.entries.map { location ->
                    TorrentChoice(location.title, location.description, location)
                },
                selected = StreamCenterTorrentPreferences.read(sharedPref).containLocation,
                defaultValue = StreamCenterTorrentPreferences.read(null).containLocation,
                dismissLabel = "Chiudi",
            ) { selected ->
                StreamCenterTorrentPreferences.setContainLocation(sharedPref, selected.value)
                render()
            }
        })
        root.addView(torrentSettingsRow(
            title = "Lingua",
            summary = StreamCenterTorrentPreferences.read(sharedPref).language.title,
            icon = "🌐",
            onReset = {
                StreamCenterTorrentPreferences.setLanguage(sharedPref, StreamCenterTorrentPreferences.read(null).language)
                render()
            },
        ) { showTorrentLanguageDialog() })

        root.addView(torrentSectionLabel("Risultati"))
        root.addView(torrentSettingsRow(
            title = "Filtri",
            summary = torrentFiltersSummary(),
            icon = "🎚️",
            onReset = {
                sharedPref?.edit {
                    remove(StreamCenterTorrentPreferences.RESULT_LIMIT_KEY)
                    remove(StreamCenterTorrentPreferences.MINIMUM_SIZE_BYTES_KEY)
                    remove(StreamCenterTorrentPreferences.MAXIMUM_SIZE_BYTES_KEY)
                    remove(StreamCenterTorrentPreferences.MINIMUM_SEEDERS_KEY)
                    remove(StreamCenterTorrentPreferences.MAXIMUM_SEEDERS_KEY)
                    remove(StreamCenterTorrentPreferences.MINIMUM_RESOLUTION_KEY)
                    remove(StreamCenterTorrentPreferences.EXCLUDED_TERMS_KEY)
                    remove(StreamCenterTorrentPreferences.EXCLUDE_CINEMA_COPIES_KEY)
                }
                render()
            },
        ) { showTorrentFiltersDialog() })
        root.addView(torrentSettingsRow(
            title = "Codec video",
            summary = torrentVideoCodecsSummary(),
            icon = "🎞️",
            onReset = {
                sharedPref?.edit { remove(StreamCenterTorrentPreferences.BLOCKED_VIDEO_CODECS_KEY) }
                render()
            },
        ) { showTorrentVideoCodecsDialog() })

        if (!enabled) optionRows.forEach { it.alpha = settingsRowDisabledAlpha }
    }

    private fun endpointSummary(): String {
        val active = StreamCenterTorrentPreferences.domainOrder(sharedPref)
            .filter { StreamCenterTorrentPreferences.isDomainEnabled(sharedPref, it) }
        val first = active.firstOrNull() ?: return "Nessun endpoint attivo"
        val host = first.baseUrl.substringAfter("://").removePrefix("www.").trimEnd('/')
        return "${active.size} di ${StreamCenterExtDomain.entries.size} attivi, prima $host"
    }

    private fun torrentSettingsRow(
        title: String,
        summary: String,
        icon: String,
        onReset: () -> Unit,
        onClick: () -> Unit,
    ): View {
        val arrow = chevron(COLOR_ACCENT)
        return settingsRow(
            title = title,
            summary = summary,
            icon = icon,
            trailingViews = listOf(arrow),
            touchTarget = arrow,
            onReset = onReset,
            onClick = onClick,
        ).view.also(optionRows::add)
    }

    private fun compactTorrentSelector(
        label: String,
        contentDescription: String,
        onClick: () -> Unit,
    ): TextView = TextView(requireContext()).apply {
        text = "$label  ▾"
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor(COLOR_TEXT))
        this.contentDescription = contentDescription
        isClickable = true
        isFocusable = true
        minimumHeight = dp(44)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = interactiveBackground(
            fill = COLOR_CARD_ALT,
            accent = COLOR_ACCENT,
            radius = 10,
        )
        setOnClickListener { onClick() }
    }

    private fun torrentToggleGrid(options: List<TorrentToggleOption>): GridLayout {
        return GridLayout(requireContext()).apply {
            columnCount = 2
            alignmentMode = GridLayout.ALIGN_MARGINS
            useDefaultMargins = false
            options.forEach { option ->
                var enabled = option.enabled
                val item = TextView(requireContext()).apply {
                    text = option.title
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 2
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    isClickable = true
                    isFocusable = true
                    minimumHeight = dp(48)
                    layoutParams = GridLayout.LayoutParams().apply {
                        width = 0
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                        setMargins(dp(4), dp(4), dp(4), dp(4))
                    }
                }
                fun refreshAppearance() {
                    item.isSelected = enabled
                    item.alpha = if (enabled) 1f else 0.48f
                    item.setTextColor(Color.parseColor(if (enabled) COLOR_TEXT else COLOR_MUTED))
                    item.background = interactiveBackground(
                        fill = if (enabled) tint(COLOR_ACCENT, "24") else COLOR_CARD_ALT,
                        accent = COLOR_ACCENT,
                        radius = 12,
                    )
                    item.contentDescription = "${option.title}, ${if (enabled) "attivo" else "disattivato"}"
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        item.stateDescription = if (enabled) "Attivo" else "Disattivato"
                    }
                }
                item.setOnClickListener {
                    enabled = !enabled
                    refreshAppearance()
                    option.onChanged(enabled)
                }
                resetOnLongPress(item, option.title) {
                    enabled = true
                    refreshAppearance()
                    option.onChanged(enabled)
                }
                refreshAppearance()
                addView(item)
            }
        }
    }

    private fun torrentSectionLabel(value: String, top: Int = 16): View =
        sectionHeading(value, COLOR_ACCENT, topMargin = top)

    private fun confirmTorrentReset() {
        val ctx = context ?: return
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Ripristina Torrent"))
            .setMessage(
                "Ripristina la configurazione dei torrent ai valori predefiniti",
            )
            .setPositiveButton("Ripristina") { _, _ ->
                StreamCenterPlugin.resetTorrentConfiguration(sharedPref)
                extDomainStatuses.clear()
                render()
                saveToast("Impostazioni Torrent ripristinate")
            }
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun torrentDomainShortLabel(domain: StreamCenterExtDomain): String {
        val role = when (domain) {
            StreamCenterExtDomain.PRIMARY -> "Principale"
            StreamCenterExtDomain.SECONDARY -> "Secondario"
            StreamCenterExtDomain.PROXY -> "Proxy"
        }
        val host = domain.baseUrl.substringAfter("://").removePrefix("www.").trimEnd('/')
        return "$role, $host"
    }

    private fun moveExtDomain(domain: StreamCenterExtDomain, direction: Int) {
        val order = StreamCenterTorrentPreferences.domainOrder(sharedPref).toMutableList()
        val index = order.indexOf(domain)
        val target = index + direction
        if (index < 0 || target !in order.indices) return
        order[index] = order[target].also { order[target] = order[index] }
        StreamCenterTorrentPreferences.setDomainOrder(sharedPref, order)
    }

    private fun torrentSearchLocationSummary(): String =
        StreamCenterTorrentPreferences.read(sharedPref).containLocation.title

    private fun torrentFiltersSummary(): String {
        val filters = StreamCenterTorrentPreferences.read(sharedPref)
        val seed = when {
            filters.minimumSeeders != null && filters.maximumSeeders != null ->
                "Seeds ${filters.minimumSeeders}–${filters.maximumSeeders}"
            filters.minimumSeeders != null -> "Seeds ≥ ${filters.minimumSeeders}"
            filters.maximumSeeders != null -> "Seeds ≤ ${filters.maximumSeeders}"
            else -> "Seeds qualsiasi"
        }
        val quality = filters.minimumResolution.takeIf { value -> value > 0 }
            ?.let { value -> "Qualità ≥ ${value}p" }
            ?: "Qualità qualsiasi"
        return listOf("${filters.resultLimit} risultati", seed, quality).joinToString(", ")
    }

    private fun showTorrentFiltersDialog() {
        val ctx = context ?: return
        val filters = StreamCenterTorrentPreferences.read(sharedPref)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(20))
        }
        var persistIfValid: () -> Unit = {}
        var sizeUnit = torrentSizeUnitFor(filters.minimumSizeBytes ?: filters.maximumSizeBytes)
        val minimumSizeInput = input(formatTorrentSizeInput(filters.minimumSizeBytes, sizeUnit), widthDp = 92).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            gravity = Gravity.CENTER
            hint = "Minima"
            contentDescription = "Dimensione minima del torrent, ${sizeUnit.label}"
        }
        val maximumSizeInput = input(formatTorrentSizeInput(filters.maximumSizeBytes, sizeUnit), widthDp = 92).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            gravity = Gravity.CENTER
            hint = "Massima"
            contentDescription = "Dimensione massima del torrent, ${sizeUnit.label}"
        }
        lateinit var sizeUnitButton: TextView
        sizeUnitButton = compactTorrentSelector(
            label = sizeUnit.label,
            contentDescription = "Unità di misura: ${sizeUnit.label}",
        ) {
            PopupMenu(ctx, sizeUnitButton).apply {
                TorrentSizeUnit.entries.forEachIndexed { index, unit ->
                    menu.add(0, index, index, unit.label).apply {
                        isCheckable = true
                        isChecked = unit == sizeUnit
                    }
                }
                menu.setGroupCheckable(0, true, true)
                setOnMenuItemClickListener { item ->
                    val unit = TorrentSizeUnit.entries.getOrNull(item.itemId)
                        ?: return@setOnMenuItemClickListener false
                    val previousUnit = sizeUnit
                    sizeUnit = unit
                    minimumSizeInput.setText(
                        convertTorrentSizeInput(minimumSizeInput.text?.toString(), previousUnit, sizeUnit),
                    )
                    maximumSizeInput.setText(
                        convertTorrentSizeInput(maximumSizeInput.text?.toString(), previousUnit, sizeUnit),
                    )
                    minimumSizeInput.contentDescription = "Dimensione minima del torrent, ${sizeUnit.label}"
                    maximumSizeInput.contentDescription = "Dimensione massima del torrent, ${sizeUnit.label}"
                    sizeUnitButton.text = "${sizeUnit.label}  ▾"
                    sizeUnitButton.contentDescription = "Unità di misura: ${sizeUnit.label}"
                    persistIfValid()
                    true
                }
                show()
            }
        }
        content.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(titleText("Dimensione", 16, true).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            })
            addView(sizeUnitButton.apply {
                layoutParams = LinearLayout.LayoutParams(dp(88), dp(44)).apply {
                    marginStart = dp(8)
                }
            })
        })
        content.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = verticalParams(top = 8)
            addView(bodyText("Da", 12).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(minimumSizeInput.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(bodyText("a", 12).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(maximumSizeInput.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        })
        content.addView(titleText("Seeders", 16, true).apply { layoutParams = verticalParams(top = 14) })
        val minimumSeedInput = input(filters.minimumSeeders?.toString().orEmpty(), widthDp = 92).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            hint = "Minimi"
            contentDescription = "Numero minimo di seeders"
        }
        val maximumSeedInput = input(filters.maximumSeeders?.toString().orEmpty(), widthDp = 92).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            hint = "Massimi"
            contentDescription = "Numero massimo di seeders"
        }
        content.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = verticalParams(top = 8)
            addView(bodyText("Da", 12).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(minimumSeedInput.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(bodyText("a", 12).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            addView(maximumSeedInput.apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        })
        var selectedMinimumResolution = filters.minimumResolution
        fun resolutionLabel(value: Int): String = value.takeIf { it > 0 }?.let { "$it p" } ?: "Qualsiasi"
        lateinit var resolutionButton: TextView
        resolutionButton = compactTorrentSelector(
            label = resolutionLabel(selectedMinimumResolution),
            contentDescription = "Risoluzione minima: ${resolutionLabel(selectedMinimumResolution)}",
        ) {
            val options = StreamCenterTorrentPreferences.minimumResolutionOptions
            PopupMenu(ctx, resolutionButton).apply {
                options.forEachIndexed { index, resolution ->
                    menu.add(
                        0,
                        index,
                        index,
                        resolution.takeIf { it > 0 }?.let { "$it p" } ?: "Qualsiasi",
                    ).apply {
                        isCheckable = true
                        isChecked = resolution == selectedMinimumResolution
                    }
                }
                menu.setGroupCheckable(0, true, true)
                setOnMenuItemClickListener { item ->
                    val resolution = options.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
                    selectedMinimumResolution = resolution
                    resolutionButton.text = "${resolutionLabel(resolution)}  ▾"
                    resolutionButton.contentDescription = "Risoluzione minima: ${resolutionLabel(resolution)}"
                    StreamCenterTorrentPreferences.setMinimumResolution(sharedPref, resolution)
                    true
                }
                show()
            }
        }
        content.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = verticalParams(top = 16)
            addView(titleText("Risoluzione minima", 16, true).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            })
            addView(resolutionButton.apply {
                layoutParams = LinearLayout.LayoutParams(dp(104), dp(44)).apply {
                    marginStart = dp(8)
                }
            })
        })
        val resultLimitInput = input(filters.resultLimit.toString(), widthDp = 64).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            hint = "Da 1 a 100"
            this.filters = arrayOf(InputFilter.LengthFilter(3))
            contentDescription = "Numero di torrent da trovare, da 1 a 100"
        }
        content.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = verticalParams(top = 14)
            addView(titleText("Torrent da trovare", 16, true))
            addView(resultLimitInput.apply {
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(44)).apply {
                    marginStart = dp(8)
                }
            })
        })
        content.addView(titleText("Parole da escludere", 16, true).apply {
            layoutParams = verticalParams(top = 14)
        })
        val termsInput = input(filters.excludedTerms).apply {
            hint = "sample, trailer, cam"
            this.filters = arrayOf(InputFilter.LengthFilter(500))
            contentDescription = "Parole o frasi da escludere dai risultati Torrent"
            layoutParams = verticalParams(top = 8)
        }
        content.addView(termsInput)
        val termsCounter = counterText("0 esclusioni", 12).apply {
            layoutParams = verticalParams(top = 6)
        }
        fun updateTermsCounter() {
            val count = termsInput.text?.toString().orEmpty()
                .split(',', ';', '\n')
                .map(String::trim)
                .count(String::isNotBlank)
            termsCounter.text = if (count == 1) "1 esclusione" else "$count esclusioni"
        }
        termsInput.doAfterTextChanged {
            StreamCenterTorrentPreferences.setExcludedTerms(sharedPref, termsInput.text?.toString().orEmpty())
            updateTermsCounter()
        }
        updateTermsCounter()
        content.addView(termsCounter)
        content.addView(switchRow(
            title = "Escludi copie cinema",
            checked = filters.excludeCinemaCopies,
            defaultChecked = StreamCenterTorrentPreferences.read(null).excludeCinemaCopies,
            accent = COLOR_ACCENT,
            topMargin = 12,
        ) { enabled ->
            StreamCenterTorrentPreferences.setExcludeCinemaCopies(sharedPref, enabled)
        })
        persistIfValid = persist@ {
            val minimumSize = parseTorrentSize(minimumSizeInput.text?.toString(), sizeUnit)
            val maximumSize = parseTorrentSize(maximumSizeInput.text?.toString(), sizeUnit)
            val minimumSeeders = parseOptionalNonNegativeInt(minimumSeedInput.text?.toString())
            val maximumSeeders = parseOptionalNonNegativeInt(maximumSeedInput.text?.toString())
            val resultLimit = resultLimitInput.text?.toString()?.trim()?.toIntOrNull()
            val invalidMinimumSize = minimumSizeInput.text?.toString().orEmpty().trim().isNotBlank() &&
                minimumSize == null
            val invalidMaximumSize = maximumSizeInput.text?.toString().orEmpty().trim().isNotBlank() &&
                maximumSize == null
            val invalidMinimumSeeders = minimumSeedInput.text?.toString().orEmpty().trim().isNotBlank() &&
                minimumSeeders == null
            val invalidMaximumSeeders = maximumSeedInput.text?.toString().orEmpty().trim().isNotBlank() &&
                maximumSeeders == null
            val invalidResultLimit = resultLimit !in 1..100
            val invalidSizeBounds = minimumSize != null && maximumSize != null && minimumSize > maximumSize
            val invalidSeederBounds =
                minimumSeeders != null && maximumSeeders != null && minimumSeeders > maximumSeeders

            minimumSizeInput.error = when {
                invalidMinimumSize -> "Inserisci una dimensione positiva."
                invalidSizeBounds -> "Non può superare la dimensione massima."
                else -> null
            }
            maximumSizeInput.error = if (invalidMaximumSize) "Inserisci una dimensione positiva." else null
            minimumSeedInput.error = when {
                invalidMinimumSeeders -> "Inserisci un numero non negativo."
                invalidSeederBounds -> "Non può superare il numero massimo."
                else -> null
            }
            maximumSeedInput.error = if (invalidMaximumSeeders) "Inserisci un numero non negativo." else null
            resultLimitInput.error = if (invalidResultLimit) "Inserisci un valore da 1 a 100." else null

            if (
                invalidMinimumSize ||
                invalidMaximumSize ||
                invalidMinimumSeeders ||
                invalidMaximumSeeders ||
                invalidResultLimit ||
                invalidSizeBounds ||
                invalidSeederBounds
            ) return@persist
            StreamCenterTorrentPreferences.setSizeBounds(sharedPref, minimumSize, maximumSize)
            StreamCenterTorrentPreferences.setSeederBounds(sharedPref, minimumSeeders, maximumSeeders)
            StreamCenterTorrentPreferences.setResultLimit(sharedPref, resultLimit ?: return@persist)
        }
        listOf(
            minimumSizeInput,
            maximumSizeInput,
            minimumSeedInput,
            maximumSeedInput,
            resultLimitInput,
        ).forEach { input ->
            input.doAfterTextChanged { persistIfValid() }
        }
        val defaults = StreamCenterTorrentPreferences.read(null)
        val resetSizes = {
            sharedPref?.edit {
                remove(StreamCenterTorrentPreferences.MINIMUM_SIZE_BYTES_KEY)
                remove(StreamCenterTorrentPreferences.MAXIMUM_SIZE_BYTES_KEY)
            }
            minimumSizeInput.setText("")
            maximumSizeInput.setText("")
        }
        val resetSeeders = {
            sharedPref?.edit {
                remove(StreamCenterTorrentPreferences.MINIMUM_SEEDERS_KEY)
                remove(StreamCenterTorrentPreferences.MAXIMUM_SEEDERS_KEY)
            }
            minimumSeedInput.setText(defaults.minimumSeeders?.toString().orEmpty())
            maximumSeedInput.setText(defaults.maximumSeeders?.toString().orEmpty())
        }
        listOf(minimumSizeInput, maximumSizeInput).forEach { resetOnLongPress(it, "Dimensione", resetSizes) }
        listOf(minimumSeedInput, maximumSeedInput).forEach { resetOnLongPress(it, "Seeders", resetSeeders) }
        resetOnLongPress(resultLimitInput, "Torrent da trovare") {
            StreamCenterTorrentPreferences.setResultLimit(sharedPref, defaults.resultLimit)
            resultLimitInput.setText(defaults.resultLimit.toString())
        }
        resetOnLongPress(resolutionButton, "Risoluzione minima") {
            selectedMinimumResolution = defaults.minimumResolution
            StreamCenterTorrentPreferences.setMinimumResolution(sharedPref, selectedMinimumResolution)
            resolutionButton.text = "${resolutionLabel(selectedMinimumResolution)}  ▾"
            resolutionButton.contentDescription = "Risoluzione minima: ${resolutionLabel(selectedMinimumResolution)}"
        }
        resetOnLongPress(termsInput, "Parole da escludere") { termsInput.setText(defaults.excludedTerms) }
        val scroll = ScrollView(ctx).apply { addView(content) }
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Filtri e Risultati"))
            .setView(scroll)
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog, onDismiss = { render() })
        dialog.show()
    }

    private fun showExtDomainsDialog() {
        val ctx = context ?: return
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(20))
        }
        val statusViews = mutableMapOf<StreamCenterExtDomain, TextView>()
        fun rebuildDomainRows() {
            content.removeAllViews()
            statusViews.clear()
            val orderedDomains = StreamCenterTorrentPreferences.domainOrder(sharedPref)
            orderedDomains.forEachIndexed { index, domain ->
                val status = bodyText(torrentDomainStatusText(domain, extDomainStatuses[domain]), 12)
                statusViews[domain] = status
                val trailing = buildList<View> {
                    add(styledSwitch(
                        StreamCenterTorrentPreferences.isDomainEnabled(sharedPref, domain),
                        COLOR_ACCENT,
                        defaultChecked = domain.defaultEnabled,
                        resetTitle = torrentDomainShortLabel(domain),
                    ) { enabled ->
                        StreamCenterTorrentPreferences.setDomainEnabled(sharedPref, domain, enabled)
                        rebuildDomainRows()
                        render()
                    }.apply {
                        contentDescription = "Attiva o disattiva ${torrentDomainShortLabel(domain)}"
                    })
                    if (domain.requiresCloudflare) {
                        add(iconButton(
                            symbol = "🛡",
                            description = "Verifica Cloudflare per ${torrentDomainShortLabel(domain)}",
                            accent = COLOR_ACCENT,
                            size = 30,
                        ) {
                            showExtCloudflareVerification(domain)
                        })
                    } else {
                        add(View(ctx).apply {
                            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                                marginStart = dp(8)
                            }
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        })
                    }
                    add(reorderIconButton(
                        symbol = "↑",
                        description = "Alza la priorità di ${torrentDomainShortLabel(domain)}",
                        accent = COLOR_ACCENT,
                        enabled = index > 0,
                        size = 30,
                    ) {
                        moveExtDomain(domain, -1)
                        rebuildDomainRows()
                    })
                    add(reorderIconButton(
                        symbol = "↓",
                        description = "Abbassa la priorità di ${torrentDomainShortLabel(domain)}",
                        accent = COLOR_ACCENT,
                        enabled = index < orderedDomains.size - 1,
                        size = 30,
                    ) {
                        moveExtDomain(domain, 1)
                        rebuildDomainRows()
                    })
                }
                content.addView(endpointDomainCard(domain, index + 1, status, trailing))
            }
        }
        rebuildDomainRows()
        extAvailabilityStatusViews = statusViews
        refreshExtAvailabilityDialogState()
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Endpoint EXT"))
            .setView(ScrollView(ctx).apply { addView(content) })
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog, onDismiss = {
            extAvailabilityStatusViews = emptyMap()
        })
        dialog.show()
        startExtAvailabilityCheck()
    }

    private fun endpointDomainCard(
        domain: StreamCenterExtDomain,
        priority: Int,
        status: TextView,
        controls: List<View>,
    ): LinearLayout {
        val isEnabled = StreamCenterTorrentPreferences.isDomainEnabled(sharedPref, domain)
        val host = domain.baseUrl
            .substringAfter("://")
            .removePrefix("www.")
            .trimEnd('/')
        updateEndpointStatusChip(status, domain, extDomainStatuses[domain])
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground(
                fillColor = if (isEnabled) COLOR_CARD else COLOR_CARD_DISABLED,
                strokeColor = COLOR_CARD,
                radius = 14,
            )
            setPadding(dp(14), dp(13), dp(12), dp(12))
            layoutParams = verticalParams(top = 10)
            contentDescription = "${domain.title}, priorità $priority"
            controls.filterIsInstance<SwitchCompat>().firstOrNull()?.let { toggle ->
                StreamCenterSettingReset.bind(this) { toggle.performLongClick() }
            }

            addView(LinearLayout(requireContext()).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(chip(priority.toString(), COLOR_ACCENT).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        marginEnd = dp(10)
                    }
                })
                addView(LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    ).apply {
                        marginEnd = dp(8)
                    }
                    addView(titleText(domain.title, 15, true))
                    addView(bodyText(host, 11).apply {
                        setPadding(0, dp(3), 0, 0)
                    })
                })
                addView(status)
            })

            addView(LinearLayout(requireContext()).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, 0)
                addView(chip("PRIORITÀ $priority", COLOR_ACCENT))
                addView(View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
                })
                controls.forEach(::addView)
            })
        }
    }

    private fun updateEndpointStatusChip(
        target: TextView,
        domain: StreamCenterExtDomain,
        status: StreamCenterExtDomainStatus?,
        checking: Boolean = false,
    ) {
        val accent = when {
            checking -> COLOR_ACCENT
            !StreamCenterTorrentPreferences.isDomainEnabled(sharedPref, domain) -> COLOR_MUTED
            status?.availability == StreamCenterExtAvailability.AVAILABLE -> COLOR_SUCCESS
            status == null -> COLOR_ACCENT
            else -> COLOR_DANGER
        }
        target.text = if (checking) "Verifica in corso…" else torrentDomainStatusText(domain, status)
        target.visibility = if (target.text.isBlank()) View.GONE else View.VISIBLE
        target.maxLines = 1
        target.setTextColor(Color.parseColor(tint(accent, "C7")))
        target.background = outlined(tint(accent, "4D"), tint(accent, "14"), 999)
        target.setPadding(dp(8), dp(4), dp(8), dp(4))
    }

    private fun startExtAvailabilityCheck() {
        if (isExtAvailabilityCheckRunning) return
        if (!StreamCenterVpnGuard.canUseInternet(sharedPref)) {
            return
        }
        isExtAvailabilityCheckRunning = true
        refreshExtAvailabilityDialogState()
        viewLifecycleOwner.lifecycleScope.launch {
            val statuses = supervisorScope {
                StreamCenterExtDomain.entries.map { domain ->
                    async(Dispatchers.IO) {
                        val result = try {
                            Result.success(StreamCenterExtTorrentClient.checkAvailability(domain))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            Result.failure(error)
                        }
                        domain to result
                    }
                }.awaitAll()
            }
            statuses.forEach { (domain, result) ->
                extDomainStatuses[domain] = result.getOrElse { error ->
                    StreamCenterExtDomainStatus(
                        domain = domain,
                        availability = StreamCenterExtAvailability.UNAVAILABLE,
                        detail = error.message,
                    )
                }
            }
            isExtAvailabilityCheckRunning = false
            if (!isAdded) return@launch
            refreshExtAvailabilityDialogState()
        }
    }

    private fun refreshExtAvailabilityDialogState() {
        extAvailabilityStatusViews.forEach { (domain, view) ->
            updateEndpointStatusChip(
                target = view,
                domain = domain,
                status = extDomainStatuses[domain],
                checking = isExtAvailabilityCheckRunning,
            )
        }
    }

    private fun torrentDomainStatusText(
        domain: StreamCenterExtDomain,
        status: StreamCenterExtDomainStatus?,
    ): String {
        if (
            !StreamCenterTorrentPreferences.isDomainEnabled(sharedPref, domain)
        ) {
            return "Disattivato"
        }
        if (!domain.requiresCloudflare) {
            return when (status?.availability) {
                null -> "Non verificato"
                StreamCenterExtAvailability.AVAILABLE -> ""
                else -> "Non disponibile"
            }
        }
        val hasClearance = StreamCenterExtCloudflareSession.hasClearanceCookie(domain.baseUrl)
        return when {
            status == null -> if (hasClearance) "Accesso da verificare" else "Non verificato"
            status.availability == StreamCenterExtAvailability.AVAILABLE ->
                if (hasClearance) "Cloudflare ✓" else ""
            status.availability == StreamCenterExtAvailability.VERIFICATION_REQUIRED ->
                "Verifica Cloudflare richiesta"
            status.availability == StreamCenterExtAvailability.RATE_LIMITED ->
                "Temporaneamente limitato"
            else -> "Non disponibile"
        }
    }

    private fun showTorrentLanguageDialog() {
        val ctx = context ?: return
        val options = StreamCenterTorrentLanguageFilter.entries.map { language ->
            TorrentChoice(language.title, language.description, language)
        }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(20))
        }
        val optionsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(optionsContainer)
        val keywordsView = bodyText("", 12).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = cardBackground(COLOR_INPUT_FILL, COLOR_STROKE, 12)
            layoutParams = verticalParams(top = 12)
        }
        content.addView(keywordsView)
        content.addView(titleText("Personalizzate", 15, true).apply {
            layoutParams = verticalParams(top = 16)
        })
        val customInput = input(StreamCenterTorrentPreferences.read(sharedPref).customItalianTerms).apply {
            hint = "es. italiansubs, itasa, nostro"
            this.filters = arrayOf(InputFilter.LengthFilter(500))
            contentDescription = "Personalizzate considerate come italiano"
            layoutParams = verticalParams(top = 8)
        }
        content.addView(customInput)
        lateinit var dialog: AlertDialog
        var current = StreamCenterTorrentPreferences.read(sharedPref).language
        fun renderKeywords() {
            keywordsView.text = torrentLanguageKeywords(current)
        }
        fun renderOptions() {
            optionsContainer.removeAllViews()
            options.forEach { option ->
                optionsContainer.addView(torrentChoiceRow(option, option.value == current, onReset = {
                    current = StreamCenterTorrentPreferences.read(null).language
                    StreamCenterTorrentPreferences.setLanguage(sharedPref, current)
                    renderOptions()
                    renderKeywords()
                    render()
                }) {
                    StreamCenterTorrentPreferences.setLanguage(sharedPref, option.value)
                    current = option.value
                    renderOptions()
                    renderKeywords()
                    render()
                })
            }
        }
        customInput.doAfterTextChanged {
            StreamCenterTorrentPreferences.setCustomItalianTerms(
                sharedPref,
                customInput.text?.toString().orEmpty(),
            )
            renderKeywords()
        }
        resetOnLongPress(customInput, "Parole italiane personalizzate") { customInput.setText("") }
        renderOptions()
        renderKeywords()
        dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Lingua"))
            .setView(ScrollView(ctx).apply { addView(content) })
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun torrentLanguageKeywords(language: StreamCenterTorrentLanguageFilter): CharSequence {
        val groups = StreamCenterTorrentMetadata.discriminationKeywords(language).toMutableList()
        val custom = StreamCenterTorrentPreferences.read(sharedPref).customItalianTermSet()
        if (language != StreamCenterTorrentLanguageFilter.ANY && custom.isNotEmpty()) {
            groups.add(StreamCenterTorrentKeywordGroup("Personalizzate", custom.toList()))
        }
        if (groups.isEmpty()) {
            return "Nessun filtro di lingua: vengono mostrati tutti i risultati e non viene cercata nessuna parola"
        }
        val intro = if (language == StreamCenterTorrentLanguageFilter.ONLY_ITALIAN) {
            "Tiene solo le release che contengono queste parole (audio o sottotitoli italiani):"
        } else {
            "Porta in cima le release che contengono queste parole:"
        }
        return buildString {
            append(intro)
            groups.forEach { group ->
                append("\n\n")
                append(group.label)
                append(": ")
                append(group.words.joinToString(", "))
            }
        }
    }

    private fun torrentVideoCodecsSummary(): String {
        val blocked = StreamCenterTorrentPreferences.read(sharedPref).blockedVideoCodecs
        return when {
            blocked.isEmpty() -> "Tutti i codec consentiti"
            blocked.size >= StreamCenterTorrentVideoCodec.entries.size -> "Nessun codec consentito"
            else -> "Esclusi: " + blocked.joinToString(", ") { codec -> codec.displayName }
        }
    }

    private fun showTorrentVideoCodecsDialog() {
        val ctx = context ?: return
        val filters = StreamCenterTorrentPreferences.read(sharedPref)
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(12), dp(16))
        }
        content.addView(torrentToggleGrid(
            StreamCenterTorrentVideoCodec.entries.map { codec ->
                TorrentToggleOption(
                    title = codec.displayName,
                    enabled = codec !in filters.blockedVideoCodecs,
                    onChanged = { allowed ->
                        StreamCenterTorrentPreferences.setVideoCodecAllowed(sharedPref, codec, allowed)
                        render()
                    },
                )
            },
        ))
        val dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle("Codec video"))
            .setView(ScrollView(ctx).apply { addView(content) })
            .setNegativeButton("Chiudi", null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun <T> torrentChoiceRow(
        option: TorrentChoice<T>,
        isSelected: Boolean,
        onReset: (() -> Unit)? = null,
        onClick: () -> Unit,
    ): View {
        val selectedBadge = iconBadge("✓", COLOR_ACCENT, size = 30, marginEnd = 0).apply {
            visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
        }
        val row = settingsRow(
            title = option.title,
            summary = option.description,
            accent = COLOR_ACCENT,
            fillColor = COLOR_CARD_ALT,
            strokeColor = if (isSelected) COLOR_ACCENT else COLOR_STROKE,
            trailingViews = listOf(selectedBadge),
            touchTarget = selectedBadge,
            topMargin = 8,
            accessibilityState = { if (isSelected) "Selezionata" else "Non selezionata" },
            onReset = onReset,
        ) { onClick() }
        row.view.isSelected = isSelected
        return row.view
    }

    private fun <T> showTorrentChoiceDialog(
        title: String,
        options: List<TorrentChoice<T>>,
        selected: T,
        defaultValue: T,
        dismissLabel: String = "Chiudi",
        onSelected: (TorrentChoice<T>) -> Unit,
    ) {
        val ctx = context ?: return
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(20))
        }
        lateinit var dialog: AlertDialog
        options.forEach { option ->
            content.addView(torrentChoiceRow(option, option.value == selected, onReset = {
                options.firstOrNull { it.value == defaultValue }?.let(onSelected)
                dialog.dismiss()
            }) {
                onSelected(option)
                dialog.dismiss()
            })
        }
        dialog = AlertDialog.Builder(ctx)
            .setCustomTitle(dialogTitle(title))
            .setView(ScrollView(ctx).apply { addView(content) })
            .setNegativeButton(dismissLabel, null)
            .create()
        applyDialogBackdrop(dialog)
        dialog.show()
    }

    private fun torrentSizeUnitFor(bytes: Long?): TorrentSizeUnit =
        if ((bytes ?: 0L) >= TorrentSizeUnit.GB.bytes) TorrentSizeUnit.GB else TorrentSizeUnit.MB

    private fun formatTorrentSizeInput(bytes: Long?, unit: TorrentSizeUnit): String {
        val value = bytes ?: return ""
        return "%.3f".format(Locale.ROOT, value.toDouble() / unit.bytes)
            .trimEnd('0')
            .trimEnd('.')
    }

    private fun convertTorrentSizeInput(
        value: String?,
        from: TorrentSizeUnit,
        to: TorrentSizeUnit,
    ): String {
        val number = value?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return ""
        return "%.3f".format(Locale.ROOT, number * from.bytes / to.bytes)
            .trimEnd('0')
            .trimEnd('.')
    }

    private fun parseTorrentSize(value: String?, unit: TorrentSizeUnit): Long? {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return null
        val amount = text.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0.0 } ?: return null
        return (amount * unit.bytes)
            .takeIf { bytes -> bytes <= Long.MAX_VALUE.toDouble() }
            ?.toLong()
            ?.takeIf { bytes -> bytes > 0L }
    }

    private fun parseOptionalNonNegativeInt(value: String?): Int? {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return null
        return text.toIntOrNull()?.takeIf { number -> number >= 0 }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showExtCloudflareVerification(domain: StreamCenterExtDomain) {
        if (!StreamCenterVpnGuard.canUseInternet(sharedPref)) {
            Toast.makeText(requireContext(), "Attiva una VPN per usare la verifica Cloudflare", Toast.LENGTH_SHORT).show()
            return
        }
        val normalizedUrl = domain.baseUrl.trim().let { value ->
            when {
                value.startsWith("https://", ignoreCase = true) -> value
                value.startsWith("http://", ignoreCase = true) -> value
                else -> "https://$value"
            }
        }.trimEnd('/')
        val parsedUrl = Uri.parse(normalizedUrl)
        if (
            parsedUrl.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") ||
            parsedUrl.host.isNullOrBlank()
        ) {
            Toast.makeText(requireContext(), "Link EXT non valido", Toast.LENGTH_SHORT).show()
            return
        }
        val baseUrl = parsedUrl.toString()
        val verificationUrl = StreamCenterExtTorrentClient.verificationUrl(domain)

        val status = bodyText("Completa la verifica nella pagina qui sotto", 12).apply {
            setPadding(dp(18), dp(12), dp(18), dp(8))
        }
        val cursorHint = bodyText(
            "Se col telecomando non riesci a cliccare la casella, premi «Cursore TV»",
            11,
        ).apply {
            setPadding(dp(18), 0, dp(18), dp(8))
        }
        val webView = WebView(requireContext()).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val webHost = FrameLayout(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                minOf(dp(520), (resources.displayMetrics.heightPixels * 0.5f).toInt()),
            )
            addView(webView)
        }
        val tvCursor = StreamCenterTvTouchCursor(webHost, webView)
        val cookieManager = CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        webView.settings.userAgentString = StreamCenterExtCloudflareSession.verificationUserAgent(
            baseUrl, webView.settings.userAgentString,
        )
        StreamCenterExtCloudflareSession.beginVerification(baseUrl, webView.settings.userAgentString)

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(cursorHint)
            addView(webHost)
        }
        val dialog = AlertDialog.Builder(requireContext())
            .setCustomTitle(dialogTitle("Verifica Cloudflare: EXT"))
            .setView(content)
            .setNeutralButton("Cursore TV", null)
            .setNegativeButton("Chiudi", null)
            .create()
        tvCursor.onStateChanged = { active ->
            cursorHint.text = if (active) {
                "Cursore TV attivo, muovi il pallino col D-pad e premi OK per cliccare la casella"
            } else {
                "Se col telecomando non riesci a cliccare la casella, premi «Cursore TV» (o il tasto MENU)"
            }
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.text =
                if (active) "Cursore: ON" else "Cursore TV"
        }
        var clearanceHandled = false
        var pageFinished = false
        var pageGeneration = 0
        fun handleClearance() {
            if (clearanceHandled) return
            clearanceHandled = true
            status.text = "Accesso alla ricerca confermato. Chiudo…"
            viewLifecycleOwner.lifecycleScope.launch {
                delay(CLOUDFLARE_CLEARANCE_CLOSE_DELAY_MS)
                if (isAdded && dialog.isShowing) dialog.dismiss()
            }
        }
        val clearancePollJob = viewLifecycleOwner.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            var lastAttemptAt = 0L
            var lastAttemptCookie: String? = null
            var hasAttempted = false
            while (isActive && !clearanceHandled) {
                cookieManager.flush()
                val cookie = StreamCenterExtCloudflareSession.clearanceCookie(verificationUrl)
                val now = android.os.SystemClock.elapsedRealtime()
                if (pageFinished && (
                        !hasAttempted || cookie != lastAttemptCookie ||
                            now - lastAttemptAt >= CLOUDFLARE_ACCESS_CHECK_INTERVAL_MS
                        )) {
                    val attemptGeneration = pageGeneration
                    hasAttempted = true
                    lastAttemptAt = now
                    lastAttemptCookie = cookie
                    status.text = "Controllo l'accesso alla ricerca Torrent…"
                    val result = withContext(Dispatchers.IO) {
                        try {
                            StreamCenterExtTorrentClient.checkAvailability(domain)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            StreamCenterExtDomainStatus(
                                domain = domain,
                                availability = StreamCenterExtAvailability.UNAVAILABLE,
                                detail = error.javaClass.simpleName,
                            )
                        }
                    }
                    if (!pageFinished || attemptGeneration != pageGeneration) continue
                    extDomainStatuses[domain] = result
                    refreshExtAvailabilityDialogState()
                    StreamCenterLogger.logMenu(
                        action = "Verifica accesso ricerca EXT completata",
                        metadata = mapOf(
                            "dominio" to domain.baseUrl,
                            "stato" to result.availability.name.lowercase(Locale.ROOT),
                            "http" to result.httpCode,
                            "dettaglio" to result.detail,
                            "cookie_cloudflare_presente" to (cookie != null),
                        ),
                    )
                    if (result.availability == StreamCenterExtAvailability.AVAILABLE) {
                        handleClearance()
                        break
                    }
                    status.text = when (result.availability) {
                        StreamCenterExtAvailability.VERIFICATION_REQUIRED ->
                            "La ricerca richiede ancora la verifica. Completala nella pagina qui sotto"
                        StreamCenterExtAvailability.RATE_LIMITED ->
                            "EXT limita temporaneamente le richieste. Attendi…"
                        else -> "Accesso alla ricerca non riuscito. Riprovo…"
                    }
                }
                delay(CLOUDFLARE_CLEARANCE_POLL_INTERVAL_MS)
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                pageFinished = false
                pageGeneration++
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString() ?: return true
                if (clearanceHandled) return true
                val blocked = !isAllowedExtNavigation(baseUrl, target)
                if (blocked) status.text = "Navigazione esterna bloccata per sicurezza"
                return blocked
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val target = url ?: return true
                if (clearanceHandled) return true
                val blocked = !isAllowedExtNavigation(baseUrl, target)
                if (blocked) status.text = "Navigazione esterna bloccata per sicurezza"
                return blocked
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                cookieManager.flush()
                pageFinished = url != null && isAllowedExtNavigation(baseUrl, url)
                if (!clearanceHandled) {
                    status.text = "Completa la verifica nella pagina qui sotto"
                }
            }
        }
        applyDialogBackdrop(dialog, onDismiss = {
            clearancePollJob.cancel()
            cookieManager.flush()
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.removeAllViews()
            webView.destroy()
            if (isAdded) startExtAvailabilityCheck()
        })
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_MENU) {
                if (event.action == KeyEvent.ACTION_UP) tvCursor.toggle()
                true
            } else {
                tvCursor.onKeyEvent(keyCode, event)
            }
        }
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL)?.setOnClickListener { tvCursor.toggle() }
        if (isTvLikeDevice()) tvCursor.activate()
        webView.loadUrl(verificationUrl)
        clearancePollJob.start()
    }

    private fun isAllowedExtNavigation(baseUrl: String, targetUrl: String): Boolean {
        val base = Uri.parse(baseUrl)
        val target = Uri.parse(targetUrl)
        if (target.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) return false
        val baseHost = base.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return false
        val targetHost = target.host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: return false
        return baseHost == targetHost
    }
}
